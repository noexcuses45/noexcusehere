package com.noexcusehere.mashwave.automash

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The part a song plays at a given moment, as loudness of its bass / vocal range / treble:
 *  - FULL: the whole song, untouched
 *  - BEAT: its bass and drums carry the rhythm, the middle (where voices live) is pulled right down
 *  - TOP:  its voices and melodies ride on top, with the bass removed so two basslines never fight
 */
enum class Role(val low: Float, val mid: Float, val high: Float) {
    FULL(1f, 1f, 1f),
    BEAT(1f, 0.22f, 0.8f),
    TOP(0f, 1f, 0.7f),
}

/** Band loudness at one moment of the mix; values in between are blended smoothly. */
class GainKey(val beat: Double, val low: Float, val mid: Float, val high: Float)

/** One continuous stretch of one song inside the mix. */
class Clip(
    val song: Int,
    /** Where it starts and ends, in beats of the finished mix. */
    val startBeat: Double,
    val endBeat: Double,
    /** Time map: at mix beat [mapBeats] the song is at [mapSeconds]. */
    val mapBeats: DoubleArray,
    val mapSeconds: DoubleArray,
    val keys: List<GainKey>,
    /** Pitch change, in semitones, that puts the song in a key that fits the others. */
    val semitones: Int,
    /** Overall level, so quiet and loud songs end up balanced. */
    val gain: Float,
    /** Song times (seconds) of drum hits that must land exactly where the time map puts them. */
    val hits: DoubleArray = DoubleArray(0),
)

/** How one song was fitted into the mix (shown to the user, and used in tests). */
class PlannedSong(
    val multiplier: Float,
    /** Speed change applied: 1.05 means played 5% faster. */
    val stretch: Float,
    val semitones: Int,
)

class MixPlan(
    val tempo: Float,
    val totalBeats: Double,
    val clips: List<Clip>,
    val songs: List<PlannedSong>,
    /** Order in which songs enter the mix. */
    val order: List<Int>,
) {
    val seconds: Double
        get() = totalBeats * 60.0 / tempo

    fun frameOf(beat: Double): Double = beat * 60.0 / tempo * MIX_RATE
}

/** A song's beat grid seen at the mix's pulse (it may be counted in half or double time). */
class BeatView(a: SongAnalysis, val multiplier: Float) {
    val beats: DoubleArray
    val low: FloatArray
    val mid: FloatArray
    val full: FloatArray
    val downbeat: Int
    val phrase: Int

    init {
        val m = a.beats.size
        when {
            multiplier > 1.5f -> {
                // Counted twice as fast: add a beat halfway between each pair.
                beats = DoubleArray(2 * m - 1) { if (it % 2 == 0) a.beats[it / 2] else (a.beats[it / 2] + a.beats[it / 2 + 1]) / 2 }
                low = FloatArray(beats.size) { a.beatLow[it / 2] }
                mid = FloatArray(beats.size) { a.beatMid[it / 2] }
                full = FloatArray(beats.size) { a.beatFull[it / 2] }
                downbeat = (2 * a.downbeat) % 4
                phrase = (2 * a.phrase) % 32
            }
            multiplier < 0.75f -> {
                // Counted half as fast: keep every other beat, starting from a bar line.
                val first = a.downbeat % 2
                val count = (m - first + 1) / 2
                beats = DoubleArray(count) { a.beats[first + 2 * it] }
                fun pair(v: FloatArray, i: Int): Float {
                    val j = first + 2 * i
                    return if (j + 1 < m) (v[j] + v[j + 1]) / 2 else v[j]
                }
                low = FloatArray(count) { pair(a.beatLow, it) }
                mid = FloatArray(count) { pair(a.beatMid, it) }
                full = FloatArray(count) { pair(a.beatFull, it) }
                downbeat = ((a.downbeat - first) / 2) % 4
                phrase = (((a.phrase - first) / 2) % 32 + 32) % 32
            }
            else -> {
                beats = a.beats
                low = a.beatLow
                mid = a.beatMid
                full = a.beatFull
                downbeat = a.downbeat
                phrase = a.phrase
            }
        }
    }

    /** Song time (seconds) at a possibly fractional or out-of-range beat number. */
    fun time(beat: Double): Double {
        val last = beats.size - 1
        if (beat <= 0) return beats[0] + beat * (beats[1] - beats[0])
        if (beat >= last) return beats[last] + (beat - last) * (beats[last] - beats[last - 1])
        val i = floor(beat).toInt()
        val t = beat - i
        return beats[i] * (1 - t) + beats[i + 1] * t
    }
}

/**
 * Decides the whole mix: a shared tempo, a pitch shift per song so the keys fit, and a chain of
 * eight-bar sections in which each song first rides on top of the previous one, then takes
 * over the rhythm, then hands over to the next - so something always carries on while
 * something else changes.
 */
object MixPlanner {
    /** Beats per section: eight bars. */
    const val SEGMENT = 32

    fun plan(songs: List<SongAnalysis>, seed: Long = 0, shuffle: Boolean = false): MixPlan {
        require(songs.size >= 2) { "A mashup needs at least two songs" }
        val random = Random(seed)
        val n = songs.size

        val (tempo, multipliers) = chooseTempo(songs.map { it.bpm })
        val views = songs.mapIndexed { i, a -> BeatView(a, multipliers[i]) }
        val semitones = chooseShifts(songs.map { it.notes })
        val planned = songs.mapIndexed { i, a ->
            PlannedSong(multipliers[i], tempo / (a.bpm * multipliers[i]), semitones[i])
        }

        val order = (0 until n).toMutableList()
        if (shuffle) order.shuffle(random)

        // Roughly 2.5 - 4 minutes, longer with more songs; every song gets at least one turn.
        val targetSeconds = when (n) {
            2 -> 150f
            3 -> 185f
            4 -> 215f
            else -> 240f
        }
        val secondsPerTurn = 2 * SEGMENT * 60f / tempo
        val turns = (targetSeconds / secondsPerTurn).roundToInt().coerceIn(max(n, 3), 10)

        val used = views.map { IntArray(it.beats.size) }
        val chosen = ArrayList<Turn>()
        for (k in 0 until turns) {
            val song = order[k % n]
            val parts: List<Pair<Role, Int>> = when (k) {
                0 -> listOf(Role.FULL to SEGMENT, Role.BEAT to SEGMENT, Role.TOP to SEGMENT)
                turns - 1 -> listOf(Role.TOP to SEGMENT, Role.BEAT to SEGMENT, Role.FULL to SEGMENT / 2)
                else -> listOf(Role.TOP to SEGMENT, Role.BEAT to 2 * SEGMENT, Role.TOP to SEGMENT)
            }
            val outStart = if (k == 0) 0 else (2 * k - 1) * SEGMENT
            val gain = (TARGET_RMS / max(songs[song].rms, 1e-4f)).coerceIn(0.4f, 3f)
            chooseSection(
                song, views[song], used[song], parts, outStart,
                isFirst = k == 0, isLast = k == turns - 1,
                semitones = semitones[song], gain = gain, random = random,
                preferEarly = k < n,
            )?.let { chosen += it }
        }

        // Even out the layers: every bassline at a similar level, every "top" at a similar level,
        // so a bass-light song doesn't make a thin beat and a quiet vocal isn't buried.
        val bassLevels = chosen.map { it.bass }.filter { it > 0f }.sorted()
        val topLevels = chosen.map { it.top }.filter { it > 0f }.sorted()
        val bassTarget = if (bassLevels.isEmpty()) 0f else bassLevels[bassLevels.size / 2]
        val topTarget = if (topLevels.isEmpty()) 0f else topLevels[topLevels.size / 2]
        fun boost(target: Float, own: Float): Float =
            if (target <= 0f || own <= 0f) 1f else kotlin.math.sqrt(target / own).coerceIn(0.6f, 1.6f)

        val clips = chosen.flatMap {
            buildClips(it, views[it.song], boost(bassTarget, it.bass), boost(topTarget, it.top), songs[it.song].hits)
        }
        return MixPlan(tempo, (2.0 * turns * SEGMENT - SEGMENT / 2), clips, planned, order)
    }

    /** One song's turn in the mix, before it is cut into clips. */
    private class Turn(
        val song: Int,
        val parts: List<Pair<Role, Int>>,
        val outStart: Int,
        val start: Int,
        val span: Int,
        val isFirst: Boolean,
        val isLast: Boolean,
        val semitones: Int,
        val gain: Float,
        /** Loudness of the bass while this song carries the beat, and of the middle while it is on top. */
        val bass: Float,
        val top: Float,
    )

    /**
     * Picks one tempo for everything. Songs may be counted in half or double time when that
     * needs less stretching (a 75 BPM song sits happily under a 150 BPM one).
     */
    fun chooseTempo(bpms: List<Float>): Pair<Float, List<Float>> {
        val candidates = bpms.flatMap { listOf(it, it * 2, it / 2) }.filter { it in 70f..180f }
        fun multiplier(bpm: Float, target: Float): Float =
            listOf(1f, 2f, 0.5f).minBy { abs(ln(bpm * it / target)) }

        var best = bpms[0]
        var bestCost = Float.MAX_VALUE
        for (c in candidates) {
            val worst = bpms.maxOf { abs(ln(it * multiplier(it, c) / c)) }
            // Prefer a comfortable dance tempo when the choice is otherwise even.
            val cost = worst + 0.05f * abs(ln(c / 118f))
            if (cost < bestCost) {
                bestCost = cost
                best = c
            }
        }
        var multipliers = bpms.map { multiplier(it, best) }
        // Meet in the middle so no song carries all the stretching.
        var tempo = exp(bpms.indices.map { ln(bpms[it] * multipliers[it]) }.average()).toFloat()
        multipliers = bpms.map { multiplier(it, tempo) }
        tempo = exp(bpms.indices.map { ln(bpms[it] * multipliers[it]) }.average()).toFloat()
        return (tempo * 10).roundToInt() / 10f to multipliers
    }

    /**
     * Semitone shift (at most three either way) for each song so their notes agree as much as
     * possible. Rather than trusting a single "this song is in A minor" label, it compares
     * which notes each song actually uses and slides them against each other.
     */
    fun chooseShifts(notes: List<FloatArray>): List<Int> {
        val n = notes.size
        val centred = notes.map { v ->
            val mean = v.average().toFloat()
            FloatArray(12) { v[it] - mean }
        }
        val norms = centred.map { v -> kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat() }
        // Similarity of song a and song b when b is moved up by `shift` semitones relative to a.
        fun match(a: Int, b: Int, shift: Int): Float {
            if (norms[a] <= 0f || norms[b] <= 0f) return 0f
            var dot = 0f
            for (k in 0 until 12) dot += centred[a][k] * centred[b][((k - shift) % 12 + 12) % 12]
            return dot / (norms[a] * norms[b])
        }
        val options = (-MAX_SHIFT..MAX_SHIFT).toList()
        val current = IntArray(n)
        var best = IntArray(n)
        var bestScore = Float.NEGATIVE_INFINITY
        fun search(i: Int) {
            if (i == n) {
                var score = 0f
                for (a in 0 until n) {
                    score -= SHIFT_COST * abs(current[a])
                    for (b in a + 1 until n) score += match(a, b, current[b] - current[a]) / (n - 1)
                }
                if (score > bestScore + 1e-6f) {
                    bestScore = score
                    best = current.copyOf()
                }
                return
            }
            for (k in options) {
                current[i] = k
                search(i + 1)
            }
        }
        if (n <= 6) search(0)
        return best.toList()
    }

    private fun chooseSection(
        song: Int,
        view: BeatView,
        used: IntArray,
        parts: List<Pair<Role, Int>>,
        outStart: Int,
        isFirst: Boolean,
        isLast: Boolean,
        semitones: Int,
        gain: Float,
        random: Random,
        preferEarly: Boolean,
    ): Turn? {
        val length = parts.sumOf { it.second }
        val size = view.beats.size

        // Role of each beat of the turn, for scoring candidate sections.
        val roleAt = ArrayList<Role>()
        for ((role, beats) in parts) repeat(beats) { roleAt += role }

        val meanLow = view.low.average().toFloat().coerceAtLeast(1e-12f)
        val meanMid = view.mid.average().toFloat().coerceAtLeast(1e-12f)
        val meanFull = view.full.average().toFloat().coerceAtLeast(1e-12f)

        fun score(start: Int, span: Int): Float {
            var total = 0f
            for (b in 0 until span) {
                val i = start + b
                val value = when (roleAt[b % roleAt.size]) {
                    Role.TOP -> min(view.mid[i] / meanMid, 2f)
                    Role.BEAT -> min(view.low[i] / meanLow, 2f)
                    Role.FULL -> min(view.full[i] / meanFull, 2f)
                }
                // Near-silence (intros, breaks, fade-outs) and already used bars are poor picks.
                val quiet = if (view.full[i] < 0.08f * meanFull) 2f else 0f
                total += value - quiet - 1.5f * used[i]
            }
            return total / span
        }

        fun candidates(span: Int, step: Int, anchor: Int): List<Int> {
            val out = ArrayList<Int>()
            var s = anchor % step
            while (s + span + 1 < size) {
                if (s >= 1) out += s
                s += step
            }
            return out
        }

        var span = length
        var starts = candidates(span, 32, view.phrase)
        if (starts.isEmpty()) starts = candidates(span, 4, view.downbeat)
        if (starts.isEmpty()) {
            // The song is shorter than its turn: loop the longest phrase-sized piece that fits.
            span = listOf(64, 32, 16, 8).firstOrNull { it + 2 < size } ?: return null
            starts = candidates(span, 32, view.phrase).ifEmpty { candidates(span, 4, view.downbeat) }
            if (starts.isEmpty()) return null
        }
        val ranked = starts.map { s ->
            val early = if (preferEarly) -0.15f * s / size else 0f
            s to score(s, span) + early + random.nextFloat() * 0.1f
        }.sortedByDescending { it.second }
        val start = ranked.first().first
        for (b in 0 until span) used[start + b]++

        var bass = 0f
        var bassBeats = 0
        var top = 0f
        var topBeats = 0
        for (b in 0 until length) {
            val i = start + b % span
            when (roleAt[b]) {
                Role.BEAT -> {
                    bass += view.low[i]; bassBeats++
                }
                Role.TOP -> {
                    top += view.mid[i]; topBeats++
                }
                Role.FULL -> Unit
            }
        }
        return Turn(
            song, parts, outStart, start, span, isFirst, isLast, semitones, gain,
            bass = if (bassBeats == 0) 0f else gain * gain * bass / bassBeats,
            top = if (topBeats == 0) 0f else gain * gain * top / topBeats,
        )
    }

    private fun buildClips(turn: Turn, view: BeatView, bassBoost: Float, topBoost: Float, hits: DoubleArray): List<Clip> {
        val parts = turn.parts
        val length = parts.sumOf { it.second }
        val span = turn.span
        val start = turn.start
        val outStart = turn.outStart
        val isFirst = turn.isFirst
        val isLast = turn.isLast
        val song = turn.song

        // Cut the turn into pieces of continuous song (one piece unless we are looping).
        val clips = ArrayList<Clip>()
        var done = 0
        while (done < length) {
            val pieceLength = min(span, length - done)
            val pieceOut = outStart + done
            val first = isFirst && done == 0
            val pre = if (first) 0.0 else PRE_ROLL
            val clipStart = pieceOut - pre
            val clipEnd = (pieceOut + pieceLength).toDouble()

            val mapFrom = floor(clipStart).toInt() - 1
            val mapTo = clipEnd.toInt() + 2
            val mapBeats = DoubleArray(mapTo - mapFrom + 1) { (mapFrom + it).toDouble() }
            val mapSeconds = DoubleArray(mapBeats.size) { view.time(start + (mapBeats[it] - pieceOut)) }

            clips += Clip(
                song = song,
                startBeat = clipStart,
                endBeat = clipEnd,
                mapBeats = mapBeats,
                mapSeconds = mapSeconds,
                keys = gainKeys(parts, outStart, pieceOut, pieceLength, first, isLast && done + pieceLength == length, bassBoost, topBoost),
                semitones = turn.semitones,
                gain = turn.gain,
                hits = hits,
            )
            done += pieceLength
        }
        return clips
    }

    /** Builds the loudness automation for one piece: fade in, role changes on the bar line, fade out. */
    private fun gainKeys(
        parts: List<Pair<Role, Int>>,
        turnStart: Int,
        pieceStart: Int,
        pieceLength: Int,
        hardStart: Boolean,
        ending: Boolean,
        bassBoost: Float,
        topBoost: Float,
    ): List<GainKey> {
        fun roleAt(beat: Int): Role {
            var at = turnStart
            for ((role, beats) in parts) {
                if (beat < at + beats) return role
                at += beats
            }
            return parts.last().first
        }

        val keys = ArrayList<GainKey>()
        fun change(at: Double, from: FloatArray, to: FloatArray) {
            // Voices and treble blend over half a beat; the bass swaps in the last quarter beat
            // so the incoming kick drum lands cleanly on the bar line.
            keys += GainKey(at - PRE_ROLL, from[0], from[1], from[2])
            keys += GainKey(at - PRE_ROLL / 2, from[0], (from[1] + to[1]) / 2, (from[2] + to[2]) / 2)
            keys += GainKey(at, to[0], to[1], to[2])
        }
        fun levels(role: Role) = when (role) {
            Role.BEAT -> floatArrayOf(role.low * bassBoost, role.mid, role.high)
            Role.TOP -> floatArrayOf(role.low, role.mid * topBoost, role.high * topBoost)
            Role.FULL -> floatArrayOf(role.low, role.mid, role.high)
        }
        val silent = floatArrayOf(0f, 0f, 0f)

        val firstRole = roleAt(pieceStart)
        if (hardStart) {
            val l = levels(firstRole)
            keys += GainKey(pieceStart.toDouble(), l[0], l[1], l[2])
        } else {
            change(pieceStart.toDouble(), silent, levels(firstRole))
        }
        var role = firstRole
        for (beat in pieceStart + 1 until pieceStart + pieceLength) {
            val next = roleAt(beat)
            if (next != role) {
                change(beat.toDouble(), levels(role), levels(next))
                role = next
            }
        }
        val end = (pieceStart + pieceLength).toDouble()
        if (ending) {
            // Final fade-out over the last two bars.
            val l = levels(role)
            keys += GainKey(end - 8, l[0], l[1], l[2])
            keys += GainKey(end, 0f, 0f, 0f)
        } else {
            change(end, levels(role), silent)
        }
        return keys
    }

    /** How long before its bar line an incoming part starts to fade in, in beats. */
    const val PRE_ROLL = 0.5
    private const val TARGET_RMS = 0.16f
    private const val MAX_SHIFT = 3

    /** How much better the notes must match to justify one more semitone of pitch change. */
    private const val SHIFT_COST = 0.04f
}
