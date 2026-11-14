package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.automash.ArrayPcmSource
import com.noexcusehere.mashwave.automash.FeatureCollector
import com.noexcusehere.mashwave.automash.Fft
import com.noexcusehere.mashwave.automash.MIX_RATE
import com.noexcusehere.mashwave.automash.MixPlan
import com.noexcusehere.mashwave.automash.MixPlanner
import com.noexcusehere.mashwave.automash.MixRenderer
import com.noexcusehere.mashwave.automash.PcmSink
import com.noexcusehere.mashwave.automash.Role
import com.noexcusehere.mashwave.automash.SongAnalysis
import com.noexcusehere.mashwave.automash.SongAnalyzer
import com.noexcusehere.mashwave.automash.Stretcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class AutoMashTest {

    /**
     * A small pretend song: kick on every beat, snare on beats 2 and 4, hi-hat between the
     * beats, and a four-chord loop (I - V - vi - IV) in the given major key.
     */
    private fun song(bpm: Double, seconds: Int, keyRoot: Int, seed: Int): ShortArray {
        val n = seconds * MIX_RATE
        val out = FloatArray(n)
        val random = Random(seed)
        val beat = 60.0 / bpm
        val kickLength = (0.22 * MIX_RATE).toInt()
        val kick = FloatArray(kickLength) {
            val t = it.toDouble() / MIX_RATE
            (sin(2 * PI * (48 + 70 * exp(-t * 28)) * t) * exp(-t * 13)).toFloat()
        }
        val snareLength = (0.15 * MIX_RATE).toInt()
        val hatLength = (0.04 * MIX_RATE).toInt()
        var b = 0
        while (b * beat < seconds) {
            val at = (b * beat * MIX_RATE).roundToInt()
            for (i in 0 until kickLength) if (at + i < n) out[at + i] += kick[i] * 0.8f
            if (b % 2 == 1) {
                for (i in 0 until snareLength) if (at + i < n) {
                    out[at + i] += (random.nextFloat() - 0.5f) * exp(-i / (0.03f * MIX_RATE)) * 0.7f
                }
            }
            val off = ((b + 0.5) * beat * MIX_RATE).roundToInt()
            for (i in 0 until hatLength) if (off + i < n) {
                out[off + i] += (random.nextFloat() - 0.5f) * exp(-i / (0.008f * MIX_RATE)) * 0.3f
            }
            b++
        }
        // Chords: one per bar. Semitone offsets from the key note for I, V, vi, IV.
        val chords = listOf(listOf(0, 4, 7), listOf(7, 11, 14), listOf(9, 12, 16), listOf(5, 9, 12))
        val base = 130.81 * 2.0.pow(keyRoot / 12.0)
        for (i in 0 until n) {
            val t = i.toDouble() / MIX_RATE
            val chord = chords[((t / (4 * beat)).toInt()) % 4]
            var v = 0.0
            for (semi in chord) {
                val f = base * 2.0.pow(semi / 12.0)
                v += sin(2 * PI * f * t) + 0.5 * sin(2 * PI * 2 * f * t)
            }
            out[i] += (v * 0.05).toFloat()
        }
        val stereo = ShortArray(n * 2)
        for (i in 0 until n) {
            val s = (out[i].coerceIn(-1f, 1f) * 30000).toInt().toShort()
            stereo[2 * i] = s
            stereo[2 * i + 1] = s
        }
        return stereo
    }

    private fun analyse(stereo: ShortArray): SongAnalysis {
        val collector = FeatureCollector()
        collector.add(stereo, stereo.size / 2)
        val analysis = SongAnalyzer.analyze(collector)
        assertNotNull("song should have a steady beat", analysis)
        return analysis!!
    }

    private class Capture : PcmSink {
        var data = ShortArray(1 shl 20)
        var frames = 0
        override fun write(stereo: ShortArray, frames: Int) {
            while ((this.frames + frames) * 2 > data.size) data = data.copyOf(data.size * 2)
            System.arraycopy(stereo, 0, data, this.frames * 2, frames * 2)
            this.frames += frames
        }
    }

    /**
     * Where a kick lands relative to [frame], in ms: the moment the bass energy (smoothed over
     * 12 ms) is halfway up its rise. Looks 60 ms either side.
     */
    private fun kickOffsetMs(capture: Capture, frame: Long): Double? {
        val step = 44
        val reach = 60
        val first = frame - (reach + 30L) * step
        if (first < 0 || frame + (reach + 30L) * step >= capture.frames) return null
        val blocks = 2 * reach + 60
        val energy = DoubleArray(blocks)
        var low = 0.0
        var i = (first - 4000).coerceAtLeast(0)
        while (i < first) {
            low += 0.02 * (capture.data[(2 * i).toInt()] / 32768.0 - low)
            i++
        }
        for (b in 0 until blocks) {
            var sum = 0.0
            repeat(step) {
                low += 0.02 * (capture.data[(2 * i).toInt()] / 32768.0 - low)
                sum += low * low
                i++
            }
            energy[b] = sum
        }
        // 12 ms sliding average, centred.
        val smooth = DoubleArray(blocks) { b ->
            var sum = 0.0
            for (k in -6..5) sum += energy[(b + k).coerceIn(0, blocks - 1)]
            sum / 12
        }
        val from = 30
        val to = blocks - 30
        var peak = from
        for (b in from until to) if (smooth[b] > smooth[peak]) peak = b
        var floor = peak
        for (b in from..peak) if (smooth[b] < smooth[floor]) floor = b
        if (smooth[peak] < 1e-5 || smooth[peak] < 3 * smooth[floor]) return null
        val half = (smooth[peak] + smooth[floor]) / 2
        for (b in floor until peak) {
            if (smooth[b] <= half && smooth[b + 1] > half) {
                val fine = (half - smooth[b]) / (smooth[b + 1] - smooth[b])
                return (b + fine - (reach + 30)) * step * 1000.0 / MIX_RATE
            }
        }
        return null
    }

    @Test
    fun fftFindsATone() {
        val n = 1024
        val re = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() }
        val im = FloatArray(n)
        Fft(n).transform(re, im)
        val magnitudes = FloatArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(37, magnitudes.indices.maxBy { magnitudes[it] })
    }

    @Test
    fun analysisFindsTempoBeatsBarsAndKey() {
        val a = analyse(song(bpm = 124.0, seconds = 70, keyRoot = 7, seed = 1))
        assertEquals(124f, a.bpm, 0.3f)
        // Beats sit on the kicks (which are at exact multiples of the beat length) ...
        val beat = 60.0 / 124.0
        val errors = a.beats.drop(8).dropLast(8).map { t ->
            val nearest = (t / beat).roundToInt() * beat
            t - nearest
        }
        assertTrue("beats within 6 ms of the kicks", errors.all { abs(it) < 0.006 })
        // ... a bar starts where the chord changes (every fourth kick, starting from the first) ...
        val firstBeatNumber = (a.beats[0] / beat).roundToInt()
        assertEquals(0, (firstBeatNumber + a.downbeat) % 4)
        // ... and the key is G major (7 semitones above C).
        assertEquals("G major", a.keyName)
    }

    @Test
    fun picksATempoThatNeedsLittleStretching() {
        val (tempo, multipliers) = MixPlanner.chooseTempo(listOf(120f, 128f))
        assertEquals(123.9f, tempo, 0.2f)
        assertEquals(listOf(1f, 1f), multipliers)

        // An 80 BPM song under a 150 BPM one is counted in double time rather than stretched 2x.
        val (mixed, counts) = MixPlanner.chooseTempo(listOf(80f, 150f))
        val stretches = listOf(80f * counts[0], 150f * counts[1]).map { mixed / it }
        assertTrue("stretches $stretches", stretches.all { it in 0.93f..1.07f })
    }

    @Test
    fun shiftsSongsIntoMatchingKeys() {
        // Song B uses the same notes as song A, three semitones higher.
        val a = floatArrayOf(5f, 0f, 2f, 0f, 4f, 3f, 0f, 5f, 0f, 3f, 0f, 1f)
        val b = FloatArray(12) { a[((it - 3) % 12 + 12) % 12] }
        val shifts = MixPlanner.chooseShifts(listOf(a, b))
        assertEquals("B must come down 3 relative to A", -3, shifts[1] - shifts[0])
        assertTrue(shifts.all { abs(it) <= 3 })
        // Identical songs are left alone.
        assertEquals(listOf(0, 0), MixPlanner.chooseShifts(listOf(a, a)))
    }

    @Test
    fun stretcherLeavesSoundUntouchedAtNormalSpeed() {
        val random = Random(5)
        val frames = MIX_RATE * 2
        val stereo = ShortArray(frames * 2) { ((sin(it * 0.01) * 8000) + random.nextInt(-3000, 3000)).toInt().toShort() }
        val keys = doubleArrayOf(0.0, frames.toDouble())
        val stretcher = Stretcher(ArrayPcmSource(stereo), keys, keys, 1.0)
        val left = FloatArray(4096)
        val right = FloatArray(4096)
        var at = 0
        while (at + 4096 <= frames - 8192) {
            stretcher.read(left, right, 4096)
            for (i in 0 until 4096) {
                assertEquals(stereo[2 * (at + i)] / 32768f, left[i], 2e-4f)
                assertEquals(stereo[2 * (at + i) + 1] / 32768f, right[i], 2e-4f)
            }
            at += 4096
        }
    }

    @Test
    fun stretcherChangesSpeedAndPitchIndependently() {
        val frames = MIX_RATE * 6
        val stereo = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val s = (sin(2 * PI * 440 * i / MIX_RATE) * 12000).toInt().toShort()
            stereo[2 * i] = s
            stereo[2 * i + 1] = s
        }
        fun pitchOf(speed: Double, semitones: Int): Double {
            // Output frame n plays song frame n * speed.
            val keys = doubleArrayOf(0.0, 4.0 * MIX_RATE)
            val src = doubleArrayOf(0.0, 4.0 * MIX_RATE * speed)
            val stretcher = Stretcher(ArrayPcmSource(stereo), keys, src, 2.0.pow(semitones / 12.0))
            val left = FloatArray(MIX_RATE * 3)
            val right = FloatArray(MIX_RATE * 3)
            stretcher.read(left, right, left.size)
            var crossings = 0
            for (i in MIX_RATE until left.size) if (left[i - 1] < 0 && left[i] >= 0) crossings++
            return crossings / 2.0
        }
        assertEquals("faster, same pitch", 440.0, pitchOf(1.12, 0), 2.0)
        assertEquals("slower, same pitch", 440.0, pitchOf(0.9, 0), 2.0)
        assertEquals("same speed, two semitones up", 493.9, pitchOf(1.0, 2), 2.5)
        assertEquals("faster and three semitones down", 370.0, pitchOf(1.08, -3), 2.5)
    }

    @Test
    fun planGivesEverySectionOneBasslineAndOneTop() {
        val songs = listOf(
            analyse(song(120.0, 80, 0, 1)),
            analyse(song(126.0, 80, 5, 2)),
            analyse(song(118.0, 80, 9, 3)),
        )
        val plan = MixPlanner.plan(songs, seed = 4)
        assertTrue("about three minutes: ${plan.seconds}", plan.seconds in 120.0..260.0)
        assertTrue(plan.songs.all { it.stretch in 0.9f..1.1f })

        // In the middle of every eight-bar section exactly one song supplies the bass and at
        // least one supplies the middle.
        var beat = MixPlanner.SEGMENT / 2.0
        while (beat < plan.totalBeats - 8) {
            var bass = 0f
            var middle = 0f
            for (clip in plan.clips) {
                if (beat < clip.startBeat || beat >= clip.endBeat) continue
                val key = clip.keys.last { it.beat <= beat }
                bass += if (key.low > 0.5f) 1f else 0f
                middle += if (key.mid > 0.5f) 1f else 0f
            }
            assertEquals("bass lines at beat $beat", 1f, bass)
            assertTrue("someone on top at beat $beat", middle >= 1f)
            beat += MixPlanner.SEGMENT
        }
        // Each clip moves through its song one beat per mix beat.
        for (clip in plan.clips) {
            val view = songs[clip.song]
            val perBeat = (clip.mapSeconds.last() - clip.mapSeconds.first()) / (clip.mapBeats.last() - clip.mapBeats.first())
            assertEquals(60.0 / view.bpm, perBeat, 0.004)
        }
        assertEquals(Role.TOP.low, 0f)
    }

    @Test
    fun renderedMixKeepsBothSongsOnTheSameBeat() {
        val pcm = listOf(song(120.0, 75, 0, 11), song(127.0, 75, 5, 12))
        val analyses = pcm.map { analyse(it) }
        val plan: MixPlan = MixPlanner.plan(analyses, seed = 2)
        val sources = pcm.map { ArrayPcmSource(it) }

        val offsets = ArrayList<List<Double>>()
        for (songIndex in pcm.indices) {
            val capture = Capture()
            assertTrue(MixRenderer(plan, sources).render(capture, solo = songIndex))
            assertEquals(Math.round(plan.frameOf(plan.totalBeats)), capture.frames.toLong())
            // Look at every beat where this song is carrying the bass.
            val found = ArrayList<Double>()
            for (clip in plan.clips.filter { it.song == songIndex }) {
                var beat = kotlin.math.ceil(clip.startBeat) + 2
                while (beat < clip.endBeat - 2) {
                    val key = clip.keys.last { it.beat <= beat }
                    if (key.low > 0.5f) kickOffsetMs(capture, plan.frameOf(beat).toLong())?.let { found += it }
                    beat += 1
                }
            }
            assertTrue("song $songIndex should have many bass beats", found.size > 40)
            offsets += found.sorted()
        }
        val medians = offsets.map { it[it.size / 2] }
        // Each song's kicks land on the mix's beats, and on each other.
        assertTrue("kick positions $medians", medians.all { abs(it) < 10.0 })
        assertTrue("songs agree: $medians", abs(medians[0] - medians[1]) < 4.0)
        // ... and consistently so, not just on average.
        for (list in offsets) {
            val spread = list[(list.size * 0.9).toInt()] - list[(list.size * 0.1).toInt()]
            assertTrue("spread $spread ms", spread < 6.0)
        }
    }

    @Test
    fun mixIsNeverSilentAndNeverClipsHard() {
        val pcm = listOf(song(100.0, 70, 2, 21), song(104.0, 70, 9, 22))
        val plan = MixPlanner.plan(pcm.map { analyse(it) }, seed = 9)
        val capture = Capture()
        MixRenderer(plan, pcm.map { ArrayPcmSource(it) }).render(capture)
        val second = MIX_RATE * 2
        var at = 0
        var clipped = 0
        while (at + second <= capture.frames * 2 - 8 * second) {
            var energy = 0.0
            for (i in at until at + second) {
                val v = capture.data[i] / 32768.0
                energy += v * v
                if (abs(capture.data[i].toInt()) >= 32767) clipped++
            }
            assertTrue("second ${at / second} is not silent", energy / second > 1e-4)
            at += second
        }
        assertTrue("clipped samples: $clipped", clipped < capture.frames / 500)
    }
}
