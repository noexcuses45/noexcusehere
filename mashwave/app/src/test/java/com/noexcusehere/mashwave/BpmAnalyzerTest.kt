package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.playback.BpmAnalyzer
import com.noexcusehere.mashwave.playback.MashupEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BpmAnalyzerTest {
    /** A simple drum loop: kick on every beat, a quieter hi-hat on the off-beats, plus hiss. */
    private fun drumLoop(bpm: Float, seconds: Int = 40, sampleRate: Int = 22_050): BpmAnalyzer.Envelope {
        val envelope = BpmAnalyzer.Envelope(sampleRate)
        val random = Random(7)
        val beat = 60.0 / bpm
        for (i in 0 until seconds * sampleRate) {
            val t = i.toDouble() / sampleRate
            val sinceBeat = t % beat
            val sinceOff = (t + beat / 2) % beat
            val kick = sin(2 * PI * 60 * sinceBeat) * exp(-sinceBeat * 18)
            val hat = (random.nextDouble() - 0.5) * exp(-sinceOff * 60) * 0.5
            val hiss = (random.nextDouble() - 0.5) * 0.02
            envelope.add((kick * 0.8 + hat + hiss).toFloat())
        }
        return envelope
    }

    private fun detect(bpm: Float): Float? {
        val envelope = drumLoop(bpm)
        return BpmAnalyzer.estimate(envelope.full(), envelope.low(), envelope.rate)
    }

    @Test
    fun findsTheTempoOfASteadyBeat() {
        for (bpm in listOf(90f, 100f, 120f, 128f, 140f, 96.5f, 133.3f)) {
            val found = detect(bpm)
            assertNotNull("no tempo found for $bpm", found)
            assertEquals("tempo for $bpm", bpm, found!!, 0.25f)
        }
    }

    @Test
    fun foldsFastAndSlowTemposIntoTheUsualRange() {
        // 174 BPM (drum & bass) is reported as its half-time feel, 87.
        val fast = detect(174f)!!
        assertEquals(87f, fast, 0.25f)
        // 70 BPM is reported at double time, 140.
        val slow = detect(70f)!!
        assertEquals(140f, slow, 0.25f)
    }

    @Test
    fun givesUpOnSoundWithNoBeat() {
        val envelope = BpmAnalyzer.Envelope(22_050)
        val random = Random(3)
        repeat(30 * 22_050) { envelope.add((random.nextDouble() - 0.5).toFloat() * 0.3f) }
        assertNull(BpmAnalyzer.estimate(envelope.full(), envelope.low(), envelope.rate))
    }

    @Test
    fun givesUpOnVeryShortClips() {
        val envelope = drumLoop(120f, seconds = 4)
        assertNull(BpmAnalyzer.estimate(envelope.full(), envelope.low(), envelope.rate))
    }

    @Test
    fun crossfaderKeepsBothDecksFullInTheMiddle() {
        assertEquals(1f to 1f, MashupEngine.crossfadeGains(0.5f))
        assertEquals(1f to 0f, MashupEngine.crossfadeGains(0f))
        assertEquals(0f to 1f, MashupEngine.crossfadeGains(1f))
        val (a, b) = MashupEngine.crossfadeGains(0.75f)
        assertEquals(0.5f, a, 1e-4f)
        assertEquals(1f, b, 1e-4f)
        assertEquals(true, abs(MashupEngine.crossfadeGains(2f).second - 1f) < 1e-4f)
    }
}
