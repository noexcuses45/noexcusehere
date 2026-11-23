package com.noexcusehere.mashwave.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The arithmetic of finding a song's tempo from its "how loud / how punchy" curve.
 * Plain Kotlin, shared by the phone app and the computer app.
 */
object BpmMaths {
    /** Envelope samples per second. 5 ms steps are fine enough to place beats precisely. */
    const val ENVELOPE_RATE = 200f

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
    fun rise(power: FloatArray, rate: Float): FloatArray {
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
