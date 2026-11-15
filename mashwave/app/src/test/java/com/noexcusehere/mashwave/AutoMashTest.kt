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
    private companion object {
        /** The sections of a structured test song that are choruses. */
        val CHORUSES = setOf(2, 4, 6)
    }

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

    /**
     * A pretend song with a shape: eight-bar sections - intro (drums only), verse, chorus,
     * verse, chorus, bridge, chorus, outro. Every verse and the bridge have their own chords
     * and a quiet lead; the chorus always has the same chords, is louder and has a strong lead.
     * [CHORUSES] lists which sections are choruses.
     */
    private fun structuredSong(bpm: Double, keyRoot: Int, seed: Int): ShortArray {
        val beat = 60.0 / bpm
        val sectionBeats = 32
        val sections = 8
        val n = (sections * sectionBeats * beat * MIX_RATE).toInt() + MIX_RATE
        val out = FloatArray(n)
        val random = Random(seed)
        val kickLength = (0.22 * MIX_RATE).toInt()
        val kick = FloatArray(kickLength) {
            val t = it.toDouble() / MIX_RATE
            (sin(2 * PI * (48 + 70 * exp(-t * 28)) * t) * exp(-t * 13)).toFloat()
        }
        val snareLength = (0.15 * MIX_RATE).toInt()
        val hatLength = (0.04 * MIX_RATE).toInt()
        var b = 0
        while (b * beat * MIX_RATE < n) {
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
        // Chord roots (semitones above the key note) for each bar of each kind of section.
        val chorus = listOf(0, 7, 9, 5, 0, 7, 9, 5)
        val others = listOf(
            listOf(9, 9, 5, 5, 2, 2, 7, 7),
            listOf(5, 2, 9, 7, 5, 2, 0, 0),
            listOf(2, 5, 7, 2, 9, 0, 5, 7),
            listOf(7, 0, 2, 9, 7, 5, 2, 2),
        )
        val base = 130.81 * 2.0.pow(keyRoot / 12.0)
        for (i in 0 until n) {
            val t = i.toDouble() / MIX_RATE
            val beatNumber = t / beat
            val section = (beatNumber / sectionBeats).toInt().coerceAtMost(sections - 1)
            val bar = ((beatNumber % sectionBeats) / 4).toInt().coerceIn(0, 7)
            val isChorus = section in CHORUSES
            if (section == 0 || section == sections - 1) continue
            val root = if (isChorus) chorus[bar] else others[section % others.size][bar]
            var v = 0.0
            for (semi in listOf(0, 4, 7)) {
                val f = base * 2.0.pow((root + semi) / 12.0)
                v += sin(2 * PI * f * t) + 0.5 * sin(2 * PI * 2 * f * t)
            }
            // The "voice": a held note in the vocal range that follows the chord.
            val lead = sin(2 * PI * base * 4 * 2.0.pow((root + 7) / 12.0) * t)
            out[i] += (v * (if (isChorus) 0.07 else 0.035) + lead * (if (isChorus) 0.16 else 0.05)).toFloat()
        }
        val stereo = ShortArray(n * 2)
        for (i in 0 until n) {
            val s = (out[i].coerceIn(-1f, 1f) * 30000).toInt().toShort()
            stereo[2 * i] = s
            stereo[2 * i + 1] = s
        }
        return stereo
    }

    /** Which section of a structured song a moment (in seconds) falls in. */
    private fun sectionAt(seconds: Double, bpm: Double): Int = (seconds / (32 * 60.0 / bpm)).toInt()

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
    fun findsTheChorusAndWhereTheSingingIs() {
        val bpm = 120.0
        val a = analyse(structuredSong(bpm, 0, 11))
        fun average(values: FloatArray, sections: Set<Int>): Float {
            var total = 0f
            var count = 0
            for (j in a.beats.indices) if (sectionAt(a.beats[j], bpm) in sections) {
                total += values[j]; count++
            }
            return total / count
        }
        // The chorus comes back three times: it must stand out as the hook.
        val hookInChorus = average(a.beatHook, CHORUSES)
        val hookElsewhere = average(a.beatHook, setOf(1, 3, 5))
        assertTrue("chorus $hookInChorus vs verses $hookElsewhere", hookInChorus > hookElsewhere + 0.25f)
        // Singing is strongest in the chorus and absent in the drums-only intro.
        val voiceInChorus = average(a.beatSustain, CHORUSES)
        val voiceInIntro = average(a.beatSustain, setOf(0))
        assertTrue("chorus $voiceInChorus vs intro $voiceInIntro", voiceInChorus > 4 * voiceInIntro)
    }

    @Test
    fun lengthFollowsWhatWasAskedFor() {
        val pool = listOf(
            analyse(structuredSong(120.0, 0, 1)),
            analyse(structuredSong(124.0, 5, 2)),
            analyse(structuredSong(118.0, 9, 3)),
            analyse(structuredSong(122.0, 2, 4)),
            analyse(structuredSong(126.0, 7, 5)),
            analyse(structuredSong(116.0, 4, 6)),
        )
        for (count in listOf(2, 3, 6)) {
            val songs = pool.take(count)
            for (target in listOf(60, 120, 180, 300, 420)) {
                val plan = MixPlanner.plan(songs, seed = 3, targetSeconds = target.toFloat())
                // Within half an eight-bar phrase of what was asked (a phrase is about 16 s).
                assertEquals("$count songs, $target s asked", target.toDouble(), plan.seconds, 9.0)
                // Whole bars, and nothing reaches past the end.
                assertEquals(0.0, plan.totalBeats % 16, 1e-9)
                assertTrue(plan.clips.all { it.endBeat <= plan.totalBeats + 1e-9 })
                // Every song is heard.
                assertEquals((0 until count).toSet(), plan.clips.map { it.song }.toSet())
                // The story runs intro ... outro, in time order, and ends with the last song at full power.
                val scenes = plan.scenes
                assertEquals(com.noexcusehere.mashwave.automash.SceneKind.INTRO, scenes.first().kind)
                assertEquals(com.noexcusehere.mashwave.automash.SceneKind.OUTRO, scenes.last().kind)
                assertTrue(scenes.zipWithNext().all { (x, y) -> x.beat <= y.beat })
                assertTrue("a drop somewhere", scenes.any { it.kind == com.noexcusehere.mashwave.automash.SceneKind.DROP })
            }
        }
        // Left to itself it still lands in a sensible range.
        assertTrue(MixPlanner.plan(pool.take(2), seed = 3).seconds in 130.0..170.0)
        // Asked for less time than it takes to hear six songs, it uses the shortest that fits.
        assertTrue(MixPlanner.plan(pool, seed = 3, targetSeconds = 20f).seconds < 75.0)
    }

    @Test
    fun dropsLandOnTheChorus() {
        val bpms = listOf(120.0, 124.0, 118.0)
        val songs = bpms.mapIndexed { i, bpm -> analyse(structuredSong(bpm, i * 5, 20 + i)) }
        val plan = MixPlanner.plan(songs, seed = 7, targetSeconds = 200f)
        val drops = plan.scenes.filter { it.kind == com.noexcusehere.mashwave.automash.SceneKind.DROP }
        assertTrue(drops.isNotEmpty())
        for (drop in drops) {
            // Four beats into the drop, which section of the song is playing?
            val at = drop.beat + 4
            val clip = plan.clips.first { it.song == drop.song && at >= it.startBeat && at < it.endBeat }
            val i = clip.mapBeats.indexOfFirst { it >= at }
            val t = (at - clip.mapBeats[i - 1]) / (clip.mapBeats[i] - clip.mapBeats[i - 1])
            val seconds = clip.mapSeconds[i - 1] * (1 - t) + clip.mapSeconds[i] * t
            val section = sectionAt(seconds, bpms[drop.song])
            assertTrue("drop of song ${drop.song} plays section $section", section in CHORUSES)
            // During the drop the song is alone, with everything turned up.
            val key = clip.keys.last { it.beat <= at }
            assertEquals(1f, key.low, 0.01f)
            assertEquals(1f, key.mid, 0.01f)
            assertEquals(1, plan.clips.count { at >= it.startBeat && at < it.endBeat && it.keys.last { k -> k.beat <= at }.mid > 0.05f })
            // The bar before it, the old beat has its bass pulled out and is set to echo away.
            val before = drop.beat - 2
            val leaving = plan.clips.first { it.song != drop.song && before >= it.startBeat && before < it.endBeat }
            assertEquals(0f, leaving.keys.last { it.beat <= before }.low, 0.01f)
            assertTrue(leaving.echoBeats > 0)
        }
    }

    @Test
    fun echoRingsOnAfterTheBeatStops() {
        val pcm = listOf(structuredSong(120.0, 0, 31), structuredSong(124.0, 5, 32))
        val plan = MixPlanner.plan(pcm.map { analyse(it) }, seed = 5, targetSeconds = 120f)
        val clip = plan.clips.first { it.echoBeats > 0 }
        val capture = Capture()
        MixRenderer(plan, pcm.map { ArrayPcmSource(it) }).render(capture, solo = clip.song)
        fun level(fromBeat: Double, toBeat: Double): Double {
            val from = plan.frameOf(fromBeat).toInt()
            val to = plan.frameOf(toBeat).toInt()
            var sum = 0.0
            for (i in from until to) {
                val v = capture.data[2 * i] / 32768.0
                sum += v * v
            }
            return kotlin.math.sqrt(sum / (to - from))
        }
        val end = clip.endBeat
        // That song is not playing after its clip ends (heard alone), yet the echo is there...
        val ringing = level(end + 0.5, end + 1.5)
        assertTrue("echo level $ringing", ringing > 0.002)
        // ...quieter with every repeat...
        assertTrue(level(end + 2.5, end + 3.5) < ringing)
        // ...and gone after its bar.
        assertTrue(level(end + 4.5, end + 6.0) < 0.0005)
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
