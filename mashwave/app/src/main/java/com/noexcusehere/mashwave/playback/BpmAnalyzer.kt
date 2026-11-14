package com.noexcusehere.mashwave.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Works out a song's tempo (beats per minute) so two decks can be matched.
 *
 * It decodes about a minute of the track, turns it into a "how loud / how punchy" curve,
 * then looks for the beat spacing at which that curve repeats most strongly.
 */
object BpmAnalyzer {
    /** Envelope samples per second. 5 ms steps are fine enough to place beats precisely. */
    const val ENVELOPE_RATE = 200f
    private const val ANALYSE_SECONDS = 60
    private const val TAG = "Mashwave"

    /** Stop after this long if we already have a usable amount of audio ... */
    private const val SOFT_TIME_LIMIT_MS = 20_000L

    /** ... and never spend longer than this, even on a very slow phone. */
    private const val HARD_TIME_LIMIT_MS = 90_000L
    private const val USEFUL_SECONDS = 15

    fun analyze(context: Context, uri: String): Float? {
        if (!uri.startsWith("content://") && !uri.startsWith("file://") && !uri.startsWith("/")) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)

            // Skip the intro: the middle of a song has the steadiest beat.
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs > 100_000_000L) {
                extractor.seekTo(min(30_000_000L, durationUs / 4), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var isFloat = false
            var envelope = Envelope(sampleRate)
            val wanted = (ANALYSE_SECONDS * ENVELOPE_RATE).toInt()
            val info = MediaCodec.BufferInfo()
            val started = System.currentTimeMillis()
            var inputDone = false
            var outputDone = false

            while (!outputDone && envelope.size < wanted) {
                if (Thread.interrupted()) return null
                val elapsed = System.currentTimeMillis() - started
                val useful = envelope.size >= USEFUL_SECONDS * envelope.rate
                if (elapsed > HARD_TIME_LIMIT_MS || (useful && elapsed > SOFT_TIME_LIMIT_MS)) break
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val read = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (read < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        val newRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (newRate != sampleRate && envelope.size == 0) {
                            sampleRate = newRate
                            envelope = Envelope(sampleRate)
                        }
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0 && channels > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            if (isFloat) {
                                val floats = buffer.asFloatBuffer()
                                val frames = floats.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (ch in 0 until channels) sum += floats.get()
                                    envelope.add(sum / channels)
                                }
                            } else {
                                val shorts = buffer.asShortBuffer()
                                val frames = shorts.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0
                                    for (ch in 0 until channels) sum += shorts.get()
                                    envelope.add(sum / (channels * 32768f))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            val bpm = estimate(envelope.full(), envelope.low(), envelope.rate)
            Log.d(TAG, "Tempo of $uri: $bpm (from ${(envelope.size / envelope.rate).toInt()} s of audio)")
            return bpm
        } catch (e: Exception) {
            Log.w(TAG, "Could not analyse $uri", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Collects two energy curves from raw audio: the whole signal, and just the bass (kick drum).
     * Values are mean power per 5 ms step.
     */
    class Envelope(sampleRate: Int) {
        private val hop = max(1, (sampleRate / ENVELOPE_RATE).toInt())

        /** The real number of envelope values per second (the hop is a whole number of samples). */
        val rate: Float = sampleRate.toFloat() / hop
        private val alpha = (2 * Math.PI * 150.0 / sampleRate).toFloat().coerceAtMost(1f)
        private var lowPass = 0f
        private var sumFull = 0f
        private var sumLow = 0f
        private var count = 0
        private var fullValues = FloatArray(4096)
        private var lowValues = FloatArray(4096)
        var size = 0
            private set

        fun add(sample: Float) {
            lowPass += alpha * (sample - lowPass)
            sumFull += sample * sample
            sumLow += lowPass * lowPass
            if (++count == hop) {
                if (size == fullValues.size) {
                    fullValues = fullValues.copyOf(size * 2)
                    lowValues = lowValues.copyOf(size * 2)
                }
                fullValues[size] = sumFull / hop
                lowValues[size] = sumLow / hop
                size++
                count = 0
                sumFull = 0f
                sumLow = 0f
            }
        }

        fun full(): FloatArray = fullValues.copyOf(size)
        fun low(): FloatArray = lowValues.copyOf(size)
    }

    /** Returns the tempo folded into the 80-160 BPM range, or null when no steady beat is found. */
    fun estimate(full: FloatArray, low: FloatArray, rate: Float = ENVELOPE_RATE): Float? {
        val n = min(full.size, low.size)
        if (n < rate * 8) return null

        // Average the energy over ~40 ms so a held bass note (whose wave is longer than one
        // 5 ms step) isn't mistaken for a rhythm, then convert to loudness.
        val window = max(1, Math.round(rate * 0.04f))
        val loudFull = loudness(full, n, window)
        val loudLow = loudness(low, n, window)

        // "Onsets": moments where the sound suddenly gets louder.
        val onset = FloatArray(n)
        var mean = 0f
        for (i in 1 until n) {
            val rise = max(0f, loudFull[i] - loudFull[i - 1]) + max(0f, loudLow[i] - loudLow[i - 1])
            onset[i] = rise
            mean += rise
        }
        mean /= n
        if (mean <= 1e-6f) return null
        for (i in 0 until n) onset[i] -= mean

        val minBpm = 60f
        val maxBpm = 200f
        // Compare the curve with itself shifted by up to 8 seconds: long shifts pin the tempo down precisely.
        val maxLag = min(n / 2, ceil(rate * 8f).toInt())
        val ac = FloatArray(maxLag + 1)
        for (lag in 0..maxLag) {
            var sum = 0f
            val limit = n - lag
            for (i in 0 until limit) sum += onset[i] * onset[i + lag]
            ac[lag] = sum / limit
        }
        if (ac[0] <= 0f) return null

        // Rough pass: which beat spacing lines up best over the next four beats?
        var best = 0f
        var bestScore = Float.NEGATIVE_INFINITY
        var bpm = minBpm
        while (bpm <= maxBpm) {
            val score = combScore(ac, 60f / bpm * rate, 4)
            if (score > bestScore) {
                bestScore = score
                best = bpm
            }
            bpm += 0.1f
        }
        // A weak repeat means there's no clear beat (speech, ambient, classical ...).
        if (best <= 0f || bestScore / ac[0] < 0.05f) return null

        // Fine pass around the winner, using every beat that fits in the 8 second window.
        var fine = best
        var fineScore = Float.NEGATIVE_INFINITY
        bpm = best - 1f
        while (bpm <= best + 1f) {
            val period = 60f / bpm * rate
            val score = combScore(ac, period, floor(maxLag / period).toInt().coerceAtLeast(4))
            if (score > fineScore) {
                fineScore = score
                fine = bpm
            }
            bpm += 0.02f
        }

        while (fine < 80f) fine *= 2f
        while (fine >= 160f) fine /= 2f
        // Most recorded music sits on a whole number, so lean towards it when we're very close.
        val whole = Math.round(fine).toFloat()
        return if (abs(fine - whole) <= 0.12f) whole else Math.round(fine * 10f) / 10f
    }

    private fun combScore(ac: FloatArray, period: Float, beats: Int): Float {
        var score = 0f
        var used = 0
        for (k in 1..beats) {
            val lag = period * k
            if (lag >= ac.size - 1) break
            score += sample(ac, lag)
            used++
        }
        return if (used == 0) Float.NEGATIVE_INFINITY else score / used
    }

    /** How sharply the loudness of [power] rises at each step (0 while steady or falling). */
    internal fun rise(power: FloatArray, rate: Float): FloatArray {
        val n = power.size
        val loud = loudness(power, n, max(1, Math.round(rate * 0.04f)))
        val out = FloatArray(n)
        for (i in 1 until n) out[i] = max(0f, loud[i] - loud[i - 1])
        return out
    }

    /** Moving-average the power over [window] steps, then compress it like the ear does. */
    private fun loudness(power: FloatArray, n: Int, window: Int): FloatArray {
        val out = FloatArray(n)
        val half = window / 2
        var sum = 0f
        var count = 0
        var lo = 0
        var hi = -1
        for (i in 0 until n) {
            val wantLo = max(0, i - half)
            val wantHi = min(n - 1, i + (window - 1 - half))
            while (hi < wantHi) {
                hi++
                sum += power[hi]
                count++
            }
            while (lo < wantLo) {
                sum -= power[lo]
                lo++
                count--
            }
            out[i] = ln(1f + 100f * sqrt(max(0f, sum / count)))
        }
        return out
    }

    private fun sample(values: FloatArray, position: Float): Float {
        val i = floor(position).toInt()
        if (i + 1 >= values.size) return values.last()
        val t = position - i
        return values[i] * (1 - t) + values[i + 1] * t
    }
}
