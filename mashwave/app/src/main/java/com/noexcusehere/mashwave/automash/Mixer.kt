package com.noexcusehere.mashwave.automash

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Random access to a decoded song (44.1 kHz stereo). Reads outside the song return silence. */
interface PcmSource {
    val frames: Long
    fun read(start: Long, count: Int, out: ShortArray)
}

class ArrayPcmSource(private val stereo: ShortArray) : PcmSource {
    override val frames: Long = stereo.size / 2L

    override fun read(start: Long, count: Int, out: ShortArray) {
        for (i in 0 until count) {
            val f = start + i
            if (f < 0 || f >= frames) {
                out[2 * i] = 0
                out[2 * i + 1] = 0
            } else {
                out[2 * i] = stereo[(2 * f).toInt()]
                out[2 * i + 1] = stereo[(2 * f + 1).toInt()]
            }
        }
    }
}

/**
 * Plays a song at a different speed without changing its pitch, and/or at a different pitch
 * without changing its speed, following an exact time map so its beats stay locked to the mix.
 *
 * It works by cutting the sound into short overlapping grains and re-laying them closer
 * together or further apart, each time nudging the grain a few milliseconds so the waves line
 * up with what was just played (the "WSOLA" method). The pitch change is a plain resample
 * afterwards, with the grain spacing adjusted beforehand to compensate.
 */
class Stretcher(
    private val source: PcmSource,
    /** Time map: output frame [outKeys] of this clip corresponds to song frame [srcKeys]. */
    private val outKeys: DoubleArray,
    private val srcKeys: DoubleArray,
    private val pitch: Double,
    /**
     * Song frames of drum hits (sorted). Grains containing one are placed exactly on the time
     * map instead of being nudged, so every hit comes out precisely on the beat.
     */
    private val hits: LongArray = LongArray(0),
) {
    private val window = FloatArray(GRAIN) { (0.5 - 0.5 * cos(2 * PI * it / GRAIN)).toFloat() }
    private val tailL = FloatArray(HOP)
    private val tailR = FloatArray(HOP)
    private var grainIndex = -1
    private var previous = 0L
    private var keyCursor = 0
    private var hitCursor = 0

    // Stretched (not yet pitch-shifted) audio waiting to be read.
    private var queueL = FloatArray(HOP * 8)
    private var queueR = FloatArray(HOP * 8)
    private var queueStart = 0L
    private var queueCount = 0
    private var outPosition = 0L

    private val block = ShortArray((GRAIN + 2 * SEARCH + 4 * HOP) * 2)
    private var mono = FloatArray(block.size / 8 + 4)

    private fun sourceAt(outFrame: Double): Double {
        val last = outKeys.size - 1
        while (keyCursor < last - 1 && outFrame >= outKeys[keyCursor + 1]) keyCursor++
        while (keyCursor > 0 && outFrame < outKeys[keyCursor]) keyCursor--
        val a = keyCursor
        val t = (outFrame - outKeys[a]) / (outKeys[a + 1] - outKeys[a])
        return srcKeys[a] + t * (srcKeys[a + 1] - srcKeys[a])
    }

    /** Lays down the next grain and queues another [HOP] frames of stretched audio. */
    private fun produce() {
        val nominal = sourceAt(grainIndex.toLong() * HOP / pitch).roundToLong()
        var position = nominal
        while (hitCursor < hits.size && hits[hitCursor] < nominal) hitCursor++
        val holdsHit = hitCursor < hits.size && hits[hitCursor] < nominal + GRAIN
        if (grainIndex >= 0 && !holdsHit) {
            // Ideally the new grain continues exactly where the last one left off; look around the
            // wanted position for the spot whose sound best matches that continuation.
            val target = previous + HOP
            val lo = min(nominal - SEARCH, target)
            val hi = max(nominal + SEARCH, target) + GRAIN
            val count = (hi - lo).toInt()
            if (count * 2 <= block.size) {
                source.read(lo, count, block)
                val steps = count / 4
                if (mono.size < steps) mono = FloatArray(steps)
                for (j in 0 until steps) {
                    var s = 0
                    val base = j * 8
                    for (k in 0 until 8) s += block[base + k]
                    mono[j] = s.toFloat()
                }
                val length = HOP / 4
                val targetAt = ((target - lo) / 4).toInt()
                var targetEnergy = 0f
                for (i in 0 until length) targetEnergy += mono[targetAt + i] * mono[targetAt + i]
                if (targetEnergy > 1f) {
                    val firstAt = ((nominal - SEARCH - lo) / 4).toInt()
                    val choices = 2 * SEARCH / 4
                    var bestScore = -2f
                    var bestOffset = SEARCH / 4
                    for (c in 0..choices) {
                        val at = firstAt + c
                        var dot = 0f
                        var energy = 0f
                        for (i in 0 until length) {
                            val v = mono[at + i]
                            dot += v * mono[targetAt + i]
                            energy += v * v
                        }
                        // Slight preference for staying on time when several spots match equally.
                        val score = dot / sqrt(energy * targetEnergy + 1f) - CENTER_BIAS * abs(c - SEARCH / 4) / (SEARCH / 4)
                        if (score > bestScore) {
                            bestScore = score
                            bestOffset = c
                        }
                    }
                    // The rough search moved in steps of four frames; settle the last few frames exactly.
                    val coarse = lo + (firstAt + bestOffset) * 4L
                    val targetOffset = (target - lo).toInt()
                    var fineEnergy = 0f
                    for (i in 0 until HOP step 2) {
                        val v = monoAt(targetOffset + i)
                        fineEnergy += v * v
                    }
                    var fineBest = -2f
                    position = coarse
                    for (d in FINE_ORDER) {
                        val offset = (coarse + d - lo).toInt()
                        if (offset < 0 || offset + GRAIN > count) continue
                        var dot = 0f
                        var energy = 0f
                        for (i in 0 until HOP step 2) {
                            val v = monoAt(offset + i)
                            dot += v * monoAt(targetOffset + i)
                            energy += v * v
                        }
                        val score = dot / sqrt(energy * fineEnergy + 1f)
                        if (score > fineBest + 1e-6f) {
                            fineBest = score
                            position = coarse + d
                        }
                    }
                }
            }
        }

        source.read(position, GRAIN, block)
        if (grainIndex >= 0) {
            if (queueCount + HOP > queueL.size) {
                queueL = queueL.copyOf(queueL.size * 2)
                queueR = queueR.copyOf(queueR.size * 2)
            }
            for (i in 0 until HOP) {
                val w = window[i]
                queueL[queueCount + i] = tailL[i] + block[2 * i] * w * SCALE
                queueR[queueCount + i] = tailR[i] + block[2 * i + 1] * w * SCALE
            }
            queueCount += HOP
        }
        for (i in 0 until HOP) {
            val w = window[HOP + i]
            tailL[i] = block[2 * (HOP + i)] * w * SCALE
            tailR[i] = block[2 * (HOP + i) + 1] * w * SCALE
        }
        previous = position
        grainIndex++
    }

    private fun monoAt(frame: Int): Float = (block[2 * frame] + block[2 * frame + 1]).toFloat()

    private fun sample(queue: FloatArray, index: Long): Float {
        val i = (index - queueStart).toInt()
        return if (i < 0) queue[0] else queue[i]
    }

    /** Fills [left] and [right] with the next [count] frames of this clip. */
    fun read(left: FloatArray, right: FloatArray, count: Int) {
        for (n in 0 until count) {
            val x = (outPosition + n) * pitch
            val base = floor(x).toLong()
            while (queueStart + queueCount < base + 3) produce()
            if (pitch == 1.0) {
                left[n] = sample(queueL, base)
                right[n] = sample(queueR, base)
            } else {
                // Smooth curve through the four nearest points.
                val t = (x - base).toFloat()
                left[n] = hermite(sample(queueL, base - 1), sample(queueL, base), sample(queueL, base + 1), sample(queueL, base + 2), t)
                right[n] = hermite(sample(queueR, base - 1), sample(queueR, base), sample(queueR, base + 1), sample(queueR, base + 2), t)
            }
        }
        outPosition += count
        // Drop what can no longer be needed.
        val keepFrom = floor(outPosition * pitch).toLong() - 2
        val drop = (keepFrom - queueStart).toInt()
        if (drop > HOP) {
            System.arraycopy(queueL, drop, queueL, 0, queueCount - drop)
            System.arraycopy(queueR, drop, queueR, 0, queueCount - drop)
            queueStart += drop
            queueCount -= drop
        }
    }

    private fun hermite(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val c1 = 0.5f * (c - a)
        val c2 = a - 2.5f * b + 2f * c - 0.5f * d
        val c3 = 0.5f * (d - a) + 1.5f * (b - c)
        return ((c3 * t + c2) * t + c1) * t + b
    }

    companion object {
        /** Grain length (46 ms) and spacing; grains overlap by half. */
        const val GRAIN = 2048
        const val HOP = GRAIN / 2

        /** How far (10 ms) a grain may be nudged to line up with the previous one. */
        const val SEARCH = 440
        private const val SCALE = 1f / 32768f

        /** How strongly a grain prefers to stay exactly on time over a slightly better wave match. */
        private const val CENTER_BIAS = 0.05f
        private val FINE_ORDER = intArrayOf(0, -1, 1, -2, 2, -3, 3, -4, 4)
    }
}

/** A decoded song kept in a temporary file (raw 16-bit stereo) and read through a memory map. */
class FilePcmSource(file: File) : PcmSource {
    private val channel = RandomAccessFile(file, "r").channel
    private val shorts = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    override val frames: Long = channel.size() / 4

    override fun read(start: Long, count: Int, out: ShortArray) {
        val from = max(0L, start)
        val to = min(frames, start + count)
        if (to <= from) {
            java.util.Arrays.fill(out, 0, count * 2, 0)
            return
        }
        val lead = ((from - start) * 2).toInt()
        val body = ((to - from) * 2).toInt()
        if (lead > 0) java.util.Arrays.fill(out, 0, lead, 0)
        shorts.position((from * 2).toInt())
        shorts.get(out, lead, body)
        if (lead + body < count * 2) java.util.Arrays.fill(out, lead + body, count * 2, 0)
    }

    fun close() {
        runCatching { channel.close() }
    }
}

/**
 * Splits sound into bass / middle / treble so each can be turned up or down separately - the
 * same thing a DJ does with the three EQ knobs. Uses steep (24 dB per octave) crossover filters.
 */
class BandSplitter(lowHz: Double = 200.0, highHz: Double = 5000.0) {
    private val low = Array(2) { arrayOf(Biquad.lowPass(lowHz), Biquad.lowPass(lowHz)) }
    private val rest = Array(2) { arrayOf(Biquad.highPass(lowHz), Biquad.highPass(lowHz)) }
    private val mid = Array(2) { arrayOf(Biquad.lowPass(highHz), Biquad.lowPass(highHz)) }
    private val high = Array(2) { arrayOf(Biquad.highPass(highHz), Biquad.highPass(highHz)) }

    /** One sample of one channel in, the three band-weighted parts summed back out. */
    fun process(channel: Int, x: Float, gLow: Float, gMid: Float, gHigh: Float): Float {
        val xd = x.toDouble()
        val l = low[channel][1].step(low[channel][0].step(xd))
        val r = rest[channel][1].step(rest[channel][0].step(xd))
        val m = mid[channel][1].step(mid[channel][0].step(r))
        val h = high[channel][1].step(high[channel][0].step(r))
        return (l * gLow + m * gMid + h * gHigh).toFloat()
    }
}

class Biquad(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun step(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    companion object {
        private const val Q = 0.70710678

        fun lowPass(hz: Double): Biquad {
            val w = 2 * PI * hz / MIX_RATE
            val alpha = sin(w) / (2 * Q)
            val a0 = 1 + alpha
            val c = cos(w)
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(hz: Double): Biquad {
            val w = 2 * PI * hz / MIX_RATE
            val alpha = sin(w) / (2 * Q)
            val a0 = 1 + alpha
            val c = cos(w)
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/** Receives the finished mix a block at a time (stereo, 16-bit). */
interface PcmSink {
    fun write(stereo: ShortArray, frames: Int)
}

/** Turns a [MixPlan] into sound: stretches each clip, shapes its bands, and adds everything up. */
class MixRenderer(private val plan: MixPlan, private val sources: List<PcmSource>) {
    private class Active(val clip: Clip, val start: Long, val end: Long, val stretcher: Stretcher, val keyFrames: DoubleArray) {
        val bands = BandSplitter()
        var key = 0
    }

    /**
     * Renders the whole mix into [sink]. [onProgress] gets 0..1; return false from
     * [keepGoing] to stop early. [solo] keeps only one song's clips (used by tests).
     */
    fun render(sink: PcmSink, onProgress: (Float) -> Unit = {}, keepGoing: () -> Boolean = { true }, solo: Int = -1): Boolean {
        val total = plan.frameOf(plan.totalBeats).roundToLong()
        val pending = plan.clips.filter { solo < 0 || it.song == solo }.sortedBy { it.startBeat }.toMutableList()
        val active = ArrayList<Active>()
        val mixL = FloatArray(BLOCK)
        val mixR = FloatArray(BLOCK)
        val tmpL = FloatArray(BLOCK)
        val tmpR = FloatArray(BLOCK)
        val out = ShortArray(BLOCK * 2)

        var position = 0L
        while (position < total) {
            if (!keepGoing()) return false
            val count = min(BLOCK.toLong(), total - position).toInt()
            java.util.Arrays.fill(mixL, 0f)
            java.util.Arrays.fill(mixR, 0f)

            while (pending.isNotEmpty() && plan.frameOf(pending.first().startBeat) < position + count) {
                val clip = pending.removeAt(0)
                val start = max(0L, plan.frameOf(clip.startBeat).roundToLong())
                val end = min(total, plan.frameOf(clip.endBeat).roundToLong())
                if (end <= start) continue
                val outKeys = DoubleArray(clip.mapBeats.size) { plan.frameOf(clip.mapBeats[it]) - start }
                val srcKeys = DoubleArray(clip.mapSeconds.size) { clip.mapSeconds[it] * MIX_RATE }
                val hits = LongArray(clip.hits.size) { (clip.hits[it] * MIX_RATE).roundToLong() }
                val stretcher = Stretcher(sources[clip.song], outKeys, srcKeys, 2.0.pow(clip.semitones / 12.0), hits)
                active += Active(clip, start, end, stretcher, DoubleArray(clip.keys.size) { plan.frameOf(clip.keys[it].beat) })
            }

            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val a = iterator.next()
                val from = max(position, a.start)
                val to = min(position + count, a.end)
                if (to > from) {
                    val n = (to - from).toInt()
                    val offset = (from - position).toInt()
                    a.stretcher.read(tmpL, tmpR, n)
                    val keys = a.clip.keys
                    val last = keys.size - 1
                    val gain = a.clip.gain
                    for (i in 0 until n) {
                        val frame = (from + i).toDouble()
                        while (a.key < last && frame >= a.keyFrames[a.key + 1]) a.key++
                        val k0 = keys[a.key]
                        var gl = k0.low
                        var gm = k0.mid
                        var gh = k0.high
                        if (frame < a.keyFrames[0]) {
                            // before the first key: keep its values
                        } else if (a.key < last) {
                            val k1 = keys[a.key + 1]
                            val t = ((frame - a.keyFrames[a.key]) / (a.keyFrames[a.key + 1] - a.keyFrames[a.key])).toFloat()
                            gl += (k1.low - gl) * t
                            gm += (k1.mid - gm) * t
                            gh += (k1.high - gh) * t
                        }
                        mixL[offset + i] += a.bands.process(0, tmpL[i], gl, gm, gh) * gain
                        mixR[offset + i] += a.bands.process(1, tmpR[i], gl, gm, gh) * gain
                    }
                }
                if (a.end <= position + count) iterator.remove()
            }

            for (i in 0 until count) {
                // A few milliseconds of fade at the very start avoids a click.
                val fade = if (position + i < FADE_IN) (position + i).toFloat() / FADE_IN else 1f
                out[2 * i] = toSample(mixL[i] * MASTER * fade)
                out[2 * i + 1] = toSample(mixR[i] * MASTER * fade)
            }
            sink.write(out, count)
            position += count
            onProgress(position.toFloat() / total)
        }
        return true
    }

    /** Rounds off peaks that would otherwise clip, leaving everything quieter untouched. */
    private fun toSample(x: Float): Short {
        val a = abs(x)
        val shaped = if (a <= KNEE) x else {
            val over = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
            if (x < 0) -over else over
        }
        return (shaped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    companion object {
        private const val BLOCK = 4096
        private const val FADE_IN = 220
        private const val MASTER = 1.35f
        private const val KNEE = 0.75f
    }
}

/** Writes the mix as a plain WAV file (used by tests, and as a fallback if AAC encoding fails). */
class WavSink(private val file: File) : PcmSink {
    private val out = RandomAccessFile(file, "rw")
    private var bytes = 0L
    private var buffer = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)

    init {
        out.setLength(0)
        out.write(ByteArray(44))
    }

    override fun write(stereo: ShortArray, frames: Int) {
        if (buffer.capacity() < frames * 4) buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        for (i in 0 until frames * 2) buffer.putShort(stereo[i])
        out.write(buffer.array(), 0, frames * 4)
        bytes += frames * 4
    }

    fun close() {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(MIX_RATE)
            .putInt(MIX_RATE * 4).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(bytes.toInt())
        out.seek(0)
        out.write(header.array())
        out.close()
    }
}
