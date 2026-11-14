package com.noexcusehere.mashwave.automash

import com.noexcusehere.mashwave.playback.BpmAnalyzer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** All mashup audio is handled as 44.1 kHz stereo. */
const val MIX_RATE = 44_100

/** What the auto-mixer knows about one song after listening to it. */
class SongAnalysis(
    /** Length in 44.1 kHz frames. */
    val frames: Long,
    /** Tempo in beats per minute, folded into 80-160. */
    val bpm: Float,
    /** Time of every beat, in seconds. */
    val beats: DoubleArray,
    /** Index (0-3) of a beat that starts a bar. */
    val downbeat: Int,
    /** Index (0-31) of a beat that starts an eight-bar phrase. */
    val phrase: Int,
    /** Musical key: 0 = C, 1 = C sharp ... 11 = B. */
    val keyRoot: Int,
    val keyMinor: Boolean,
    /** How clearly the key stood out (0 = a coin toss). */
    val keyConfidence: Float,
    /** Average loudness, for balancing songs against each other. */
    val rms: Float,
    /** Bass, vocal-range and overall energy of each beat. */
    val beatLow: FloatArray,
    val beatMid: FloatArray,
    val beatFull: FloatArray,
    /** How strongly the music pulses on the beat grid; low values mean "no steady beat". */
    val beatStrength: Float,
    /** How much of each of the 12 notes (C, C sharp ... B) the song contains overall. */
    val notes: FloatArray,
    /** Times (seconds) of the beats that carry a clear drum hit; the mixer pins these to the grid. */
    val hits: DoubleArray,
) {
    val keyName: String
        get() = NOTE_NAMES[keyRoot] + if (keyMinor) " minor" else " major"

    companion object {
        val NOTE_NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    }
}

/**
 * Listens to a song as it is decoded and collects the raw measurements the analysis needs:
 * loudness curves (overall, bass, vocal range) and which notes are sounding.
 */
class FeatureCollector {
    private val hop = MIX_RATE / 200
    val rate = MIX_RATE.toFloat() / hop

    private var full = FloatArray(16_384)
    private var low = FloatArray(16_384)
    private var mid = FloatArray(16_384)
    private var size = 0
    private var count = 0
    private var sumFull = 0f
    private var sumLow = 0f
    private var sumMid = 0f

    // One-pole filters: bass below ~150 Hz, "vocal range" roughly 300 Hz - 3 kHz.
    private val aLow = (2 * PI * 130 / MIX_RATE).toFloat()
    private val aHp = (2 * PI * 300 / MIX_RATE).toFloat()
    private val aLp = (2 * PI * 3000 / MIX_RATE).toFloat().coerceAtMost(1f)
    private var lpLow = 0f
    private var lpLow2 = 0f
    private var lp300 = 0f
    private var lp3000 = 0f

    // Note detection works on a 4x slower copy of the signal, 4096 samples at a time.
    private val fftSize = 4096
    private val fftHop = 2048
    private val ring = FloatArray(fftSize)
    private var ringFill = 0
    private var decimSum = 0f
    private var decimCount = 0
    private val fft = Fft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / fftSize)).toFloat() }
    private val binClass = IntArray(fftSize / 2) { k ->
        val hz = k * (MIX_RATE / 4.0) / fftSize
        if (hz < 100 || hz > 2000) -1 else ((69 + 12 * ln(hz / 440.0) / ln(2.0)).roundToInt() % 12 + 12) % 12
    }
    val chroma = ArrayList<FloatArray>()

    private var sumSquares = 0.0
    var frames = 0L
        private set

    /** Feed [frameCount] stereo frames (left, right, left, right ...). */
    fun add(stereo: ShortArray, frameCount: Int) {
        var i = 0
        repeat(frameCount) {
            val sample = (stereo[i] + stereo[i + 1]) * (0.5f / 32768f)
            i += 2
            lpLow += aLow * (sample - lpLow)
            lpLow2 += aLow * (lpLow - lpLow2)
            lp300 += aHp * (sample - lp300)
            lp3000 += aLp * ((sample - lp300) - lp3000)
            sumFull += sample * sample
            sumLow += lpLow2 * lpLow2
            sumMid += lp3000 * lp3000
            if (++count == hop) {
                if (size == full.size) {
                    full = full.copyOf(size * 2)
                    low = low.copyOf(size * 2)
                    mid = mid.copyOf(size * 2)
                }
                full[size] = sumFull / hop
                low[size] = sumLow / hop
                mid[size] = sumMid / hop
                sumSquares += sumFull
                size++
                count = 0
                sumFull = 0f
                sumLow = 0f
                sumMid = 0f
            }
            decimSum += sample
            if (++decimCount == 4) {
                ring[ringFill++] = decimSum * 0.25f
                decimSum = 0f
                decimCount = 0
                if (ringFill == fftSize) {
                    chroma += chromaOfRing()
                    System.arraycopy(ring, fftHop, ring, 0, fftSize - fftHop)
                    ringFill = fftSize - fftHop
                }
            }
        }
        frames += frameCount
    }

    private fun chromaOfRing(): FloatArray {
        val re = FloatArray(fftSize) { ring[it] * window[it] }
        val im = FloatArray(fftSize)
        fft.transform(re, im)
        val out = FloatArray(12)
        for (k in 1 until fftSize / 2) {
            val pc = binClass[k]
            if (pc >= 0) out[pc] += sqrt(re[k] * re[k] + im[k] * im[k])
        }
        return out
    }

    /** Seconds from the start of the song to the middle of chroma frame [index]. */
    fun chromaTime(index: Int): Double = (index * fftHop + fftSize / 2) * 4.0 / MIX_RATE

    fun full(): FloatArray = full.copyOf(size)
    fun low(): FloatArray = low.copyOf(size)
    fun mid(): FloatArray = mid.copyOf(size)
    fun rms(): Float = if (size == 0) 0f else sqrt(sumSquares / (size.toDouble() * hop)).toFloat()
}

/** Plain radix-2 FFT, enough for note detection. */
class Fft(private val n: Int) {
    private val cosTable = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val reversed = IntArray(n).also { table ->
        var bits = 0
        while (1 shl bits < n) bits++
        for (i in 0 until n) {
            var r = 0
            for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            table[i] = r
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val c = cosTable[k]
                    val s = -sinTable[k]
                    val xr = re[j + half] * c - im[j + half] * s
                    val xi = re[j + half] * s + im[j + half] * c
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += len
            }
            len *= 2
        }
    }
}

object SongAnalyzer {
    // How well each note fits a major / minor key (Krumhansl & Kessler's listening tests).
    private val MAJOR = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
    private val MINOR = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)

    /** Turns the collected measurements into a beat grid, bar positions and a key. Null if there is no steady beat. */
    fun analyze(c: FeatureCollector): SongAnalysis? {
        val full = c.full()
        val low = c.low()
        val mid = c.mid()
        val rate = c.rate
        val n = full.size
        if (n < rate * 20) return null

        // Tempo from the middle of the song, where the beat is steadiest.
        val span = min(n, (rate * 150).toInt())
        val from = (n - span) / 2
        val bpm = BpmAnalyzer.estimate(full.copyOfRange(from, from + span), low.copyOfRange(from, from + span), rate) ?: return null

        val riseFull = BpmAnalyzer.rise(full, rate)
        val riseLow = BpmAnalyzer.rise(low, rate)
        val riseMid = BpmAnalyzer.rise(mid, rate)
        val onset = FloatArray(n) { riseFull[it] + riseLow[it] }

        var beatIndex = BeatTracker.track(onset, 60f / bpm * rate)
        if (beatIndex.size < 48) return null
        if (isOnOffBeats(beatIndex, riseLow, riseMid)) {
            beatIndex = DoubleArray(beatIndex.size - 1) { (beatIndex[it] + beatIndex[it + 1]) / 2 }
        }
        // Line every song's grid up on the same thing - the instant the kick drum (or, failing
        // that, the overall sound) hits - so that two songs' beats really coincide in the mix.
        val nudge = hitOffset(beatIndex, low) ?: hitOffset(beatIndex, full) ?: 0.0
        if (nudge != 0.0) beatIndex = DoubleArray(beatIndex.size) { beatIndex[it] + nudge }
        val beats = DoubleArray(beatIndex.size) { beatIndex[it] / rate }
        val m = beats.size

        // How much more "hit" there is on the beats than in general.
        var onBeat = 0.0
        for (b in beatIndex) onBeat += peakAround(onset, b.roundToInt(), 3)
        val beatStrength = ((onBeat / m) / (onset.average() + 1e-9)).toFloat()

        // Per-beat measurements.
        val beatLow = FloatArray(m)
        val beatMid = FloatArray(m)
        val beatFull = FloatArray(m)
        val kick = FloatArray(m)
        val snare = FloatArray(m)
        for (j in 0 until m) {
            val a = beatIndex[j].roundToInt().coerceIn(0, n - 1)
            val b = (if (j + 1 < m) beatIndex[j + 1].roundToInt() else a + (60f / bpm * rate).roundToInt()).coerceIn(a + 1, n)
            var sl = 0f
            var sm = 0f
            var sf = 0f
            for (i in a until b) {
                sl += low[i]; sm += mid[i]; sf += full[i]
            }
            beatLow[j] = sl / (b - a)
            beatMid[j] = sm / (b - a)
            beatFull[j] = sf / (b - a)
            kick[j] = peakAround(riseLow, a, 6)
            snare[j] = peakAround(riseMid, a, 6)
        }

        // Which notes sound during each beat.
        val beatChroma = Array(m) { FloatArray(12) }
        var frame = 0
        for (j in 0 until m) {
            val end = if (j + 1 < m) beats[j + 1] else beats[j] + 60.0 / bpm
            var used = 0
            while (frame < c.chroma.size && c.chromaTime(frame) < end) {
                if (c.chromaTime(frame) >= beats[j]) {
                    for (k in 0 until 12) beatChroma[j][k] += c.chroma[frame][k]
                    used++
                }
                frame++
            }
            if (used == 0 && c.chroma.isNotEmpty()) {
                val nearest = c.chroma[min(frame, c.chroma.size - 1)]
                for (k in 0 until 12) beatChroma[j][k] = nearest[k]
            }
        }

        val downbeat = findDownbeat(kick, snare, beatChroma)
        val phrase = findPhrase(downbeat, beatFull, beatChroma)
        val key = findKey(c.chroma)

        return SongAnalysis(
            frames = c.frames,
            bpm = bpm,
            beats = beats,
            downbeat = downbeat,
            phrase = phrase,
            keyRoot = key.first,
            keyMinor = key.second,
            keyConfidence = key.third,
            rms = c.rms(),
            beatLow = beatLow,
            beatMid = beatMid,
            beatFull = beatFull,
            beatStrength = beatStrength,
            notes = noteMix(c.chroma),
            hits = clearHits(beats, kick),
        )
    }

    private fun clearHits(beats: DoubleArray, kick: FloatArray): DoubleArray {
        val sorted = kick.sorted()
        val strong = sorted[(sorted.size * 3) / 4]
        if (strong <= 0f) return DoubleArray(0)
        return beats.filterIndexed { j, _ -> kick[j] >= 0.4f * strong }.toDoubleArray()
    }

    /**
     * A beat tracker can lock on halfway between the real beats. The kick drum settles it when
     * it clearly favours one side; otherwise the snare / clap range does.
     */
    private fun isOnOffBeats(beats: DoubleArray, riseLow: FloatArray, riseMid: FloatArray): Boolean {
        var kickOn = 0f
        var kickOff = 0f
        var midOn = 0f
        var midOff = 0f
        for (j in 0 until beats.size - 1) {
            val on = beats[j].roundToInt()
            val off = ((beats[j] + beats[j + 1]) / 2).roundToInt()
            kickOn += peakAround(riseLow, on, 5)
            kickOff += peakAround(riseLow, off, 5)
            midOn += peakAround(riseMid, on, 5)
            midOff += peakAround(riseMid, off, 5)
        }
        if (kickOn >= 1.5f * kickOff) return false
        if (kickOff >= 1.5f * kickOn) return true
        return midOff >= 1.2f * midOn
    }

    /**
     * Where, relative to the grid, does the hit typically land? Averages the energy around
     * every beat into one "typical hit" and returns the moment it is halfway up its rise -
     * a point that doesn't depend on how loud or how long the drum is. In curve steps;
     * null when there is no clear hit near the beats.
     */
    private fun hitOffset(beats: DoubleArray, power: FloatArray): Double? {
        val reach = 16
        val profile = DoubleArray(2 * reach + 1)
        for (b in beats) {
            val base = kotlin.math.floor(b).toInt()
            val t = b - base
            for (d in -reach..reach) {
                val i = base + d
                if (i < 0 || i + 1 >= power.size) continue
                profile[d + reach] += power[i] * (1 - t) + power[i + 1] * t
            }
        }
        var peak = 0
        for (i in profile.indices) if (profile[i] > profile[peak]) peak = i
        if (peak == 0) return null
        var floor = 0
        for (i in 0..peak) if (profile[i] < profile[floor]) floor = i
        val low = profile[floor]
        val high = profile[peak]
        // A real hit at least doubles the energy.
        if (high < 2 * low || high <= 0.0) return null
        val half = (low + high) / 2
        for (i in floor until peak) {
            if (profile[i] <= half && profile[i + 1] > half) {
                val fine = (half - profile[i]) / (profile[i + 1] - profile[i])
                return i + fine - reach
            }
        }
        return null
    }

    private fun noteMix(frames: List<FloatArray>): FloatArray {
        val total = FloatArray(12)
        for (f in frames) for (k in 0 until 12) total[k] += f[k]
        val sum = total.sum()
        if (sum > 0f) for (k in 0 until 12) total[k] /= sum
        return total
    }

    private fun peakAround(values: FloatArray, center: Int, radius: Int): Float {
        var best = 0f
        for (i in max(0, center - radius)..min(values.size - 1, center + radius)) best = max(best, values[i])
        return best
    }

    private fun sumRange(source: Array<FloatArray>, from: Int, to: Int): FloatArray {
        val out = FloatArray(12)
        for (j in max(0, from) until min(source.size, to)) for (k in 0 until 12) out[k] += source[j][k]
        return out
    }

    private fun difference(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (k in a.indices) {
            dot += a[k] * b[k]; na += a[k] * a[k]; nb += b[k] * b[k]
        }
        return if (na <= 0f || nb <= 0f) 0f else 1f - dot / sqrt(na * nb)
    }

    /**
     * Beat one of the bar is usually where the chords change and where the kick drum lands;
     * the snare tends to sit on beats two and four.
     */
    private fun findDownbeat(kick: FloatArray, snare: FloatArray, chroma: Array<FloatArray>): Int {
        val m = kick.size
        val harmony = FloatArray(4)
        val kicks = FloatArray(4)
        val snares = FloatArray(4)
        val counts = IntArray(4)
        for (j in 2 until m - 2) {
            val r = j % 4
            harmony[r] += difference(sumRange(chroma, j - 2, j), sumRange(chroma, j, j + 2))
            kicks[r] += kick[j]
            snares[r] += snare[j]
            counts[r]++
        }
        for (r in 0 until 4) if (counts[r] > 0) {
            harmony[r] /= counts[r]; kicks[r] /= counts[r]; snares[r] /= counts[r]
        }
        fun spread(v: FloatArray): Float = (v.max() - v.min()).coerceAtLeast(1e-9f)
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (r in 0 until 4) {
            val kickScore = (kicks[r] + kicks[(r + 2) % 4] - kicks[(r + 1) % 4] - kicks[(r + 3) % 4]) / (2 * spread(kicks))
            val snareScore = (snares[(r + 1) % 4] + snares[(r + 3) % 4] - snares[r] - snares[(r + 2) % 4]) / (2 * spread(snares))
            val harmonyScore = (harmony[r] - harmony.average().toFloat()) / spread(harmony)
            val score = harmonyScore + 0.6f * kickScore + 0.6f * snareScore
            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }
        return best
    }

    /** Songs are built in eight-bar blocks; the block edges are where the energy and chords shift most. */
    private fun findPhrase(downbeat: Int, energy: FloatArray, chroma: Array<FloatArray>): Int {
        val m = energy.size
        val meanEnergy = energy.average().toFloat().coerceAtLeast(1e-12f)
        val prefix = FloatArray(m + 1)
        for (j in 0 until m) prefix[j + 1] = prefix[j] + energy[j]
        var best = downbeat
        var bestScore = Float.NEGATIVE_INFINITY
        var q = downbeat
        while (q < 32) {
            var total = 0f
            var count = 0
            var j = q
            while (j < m) {
                if (j >= 16 && j + 16 <= m) {
                    val before = (prefix[j] - prefix[j - 16]) / 16
                    val after = (prefix[j + 16] - prefix[j]) / 16
                    total += abs(after - before) / meanEnergy +
                        difference(sumRange(chroma, j - 16, j), sumRange(chroma, j, j + 16)) * 2f
                    count++
                }
                j += 32
            }
            val score = if (count == 0) Float.NEGATIVE_INFINITY else total / count
            if (score > bestScore) {
                bestScore = score
                best = q
            }
            q += 4
        }
        return best
    }

    /** Compares the notes heard across the song with the typical note mix of all 24 keys. */
    fun findKey(frames: List<FloatArray>): Triple<Int, Boolean, Float> {
        val total = FloatArray(12)
        for (f in frames) for (k in 0 until 12) total[k] += f[k]
        if (total.sum() <= 0f) return Triple(0, false, 0f)
        var best = Triple(0, false, -2f)
        var second = -2f
        for (root in 0 until 12) {
            for (minor in listOf(false, true)) {
                val profile = if (minor) MINOR else MAJOR
                val score = correlation(total, profile, root)
                if (score > best.third) {
                    second = best.third
                    best = Triple(root, minor, score)
                } else if (score > second) {
                    second = score
                }
            }
        }
        return Triple(best.first, best.second, best.third - second)
    }

    private fun correlation(chroma: FloatArray, profile: FloatArray, root: Int): Float {
        val meanC = chroma.average().toFloat()
        val meanP = profile.average().toFloat()
        var num = 0f
        var dc = 0f
        var dp = 0f
        for (k in 0 until 12) {
            val a = chroma[(k + root) % 12] - meanC
            val b = profile[k] - meanP
            num += a * b; dc += a * a; dp += b * b
        }
        return if (dc <= 0f) 0f else num / sqrt(dc * dp)
    }
}

/**
 * Finds every beat of a song: walks the "how sharply did the sound rise" curve and picks the
 * chain of evenly spaced moments that collects the most hits, allowing the spacing to breathe
 * a little so a tempo that drifts slightly is still followed.
 */
object BeatTracker {
    /** Returns beat positions measured in curve steps (fractional after smoothing). */
    fun track(onset: FloatArray, period: Float): DoubleArray {
        val n = onset.size
        if (n < period * 8) return DoubleArray(0)
        var sum = 0.0
        var sq = 0.0
        for (v in onset) {
            sum += v; sq += v.toDouble() * v
        }
        val std = sqrt(max(1e-12, sq / n - (sum / n) * (sum / n))).toFloat()

        val lo = max(1, (period * 0.5f).roundToInt())
        val hi = (period * 2f).roundToInt()
        // Cost of a gap between beats that is not exactly one beat long.
        val penalty = FloatArray(hi + 1) { g ->
            if (g < lo) 0f else {
                val x = ln(g / period)
                -TIGHTNESS * x * x
            }
        }
        val score = FloatArray(n)
        val back = IntArray(n) { -1 }
        for (i in 0 until n) {
            var best = 0f
            var from = -1
            val start = i - hi
            for (j in max(0, start)..(i - lo)) {
                val v = score[j] + penalty[i - j]
                if (v > best) {
                    best = v
                    from = j
                }
            }
            score[i] = onset[i] / std + best
            back[i] = from
        }
        // The best chain ends somewhere in the last two beats of the song.
        var end = n - 1
        for (i in max(0, n - 2 * period.roundToInt()) until n) if (score[i] > score[end]) end = i
        val reversed = ArrayList<Int>()
        var at = end
        while (at >= 0) {
            reversed += at
            at = back[at]
        }
        // The curve only has 5 ms steps: find where between the steps each hit really peaks.
        val raw = DoubleArray(reversed.size) { index ->
            var i = reversed[reversed.size - 1 - index]
            for (d in -2..2) {
                val j = reversed[reversed.size - 1 - index] + d
                if (j in 1 until n - 1 && onset[j] > onset[i]) i = j
            }
            if (i < 1 || i >= n - 1) i.toDouble() else {
                val bend = onset[i - 1] - 2 * onset[i] + onset[i + 1]
                if (bend < 0f) i + 0.5 * (onset[i - 1] - onset[i + 1]) / bend else i.toDouble()
            }
        }

        // Fit a short straight line through neighbouring beats to get a steadier position
        // for each one (single hits can be early or late; the pulse is not).
        val m = raw.size
        val smooth = DoubleArray(m)
        for (j in 0 until m) {
            val a = max(0, j - 8)
            val b = min(m - 1, j + 8)
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            val count = b - a + 1
            for (k in a..b) {
                sx += k; sy += raw[k]; sxx += k.toDouble() * k; sxy += k.toDouble() * raw[k]
            }
            val denom = count * sxx - sx * sx
            smooth[j] = if (denom == 0.0) raw[j] else {
                val slope = (count * sxy - sx * sy) / denom
                val intercept = (sy - slope * sx) / count
                slope * j + intercept
            }
        }

        // Extend the grid to the very start and end of the song at the local spacing.
        val out = ArrayList<Double>()
        val firstGap = if (m > 8) (smooth[8] - smooth[0]) / 8 else period.toDouble()
        var t = smooth[0] - firstGap
        val head = ArrayList<Double>()
        while (t >= 0) {
            head += t
            t -= firstGap
        }
        out += head.reversed()
        for (v in smooth) out += v
        val lastGap = if (m > 8) (smooth[m - 1] - smooth[m - 9]) / 8 else period.toDouble()
        t = smooth[m - 1] + lastGap
        while (t < n) {
            out += t
            t += lastGap
        }
        return out.toDoubleArray()
    }

    private const val TIGHTNESS = 400f
}
