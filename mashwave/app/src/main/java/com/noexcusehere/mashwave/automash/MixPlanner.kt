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
    /**
     * When above zero, the last so-many beats of the clip are sent into an echo that keeps
     * ringing after the clip has stopped - a DJ's "echo out".
     */
    val echoBeats: Double = 0.0,
)

/** What happens at one point of the mix, for telling the listener how it was put together. */
enum class SceneKind { INTRO, BLEND, DROP, OUTRO }

/** [song] is the one the scene is about; [other] the song underneath it, when there is one. */
class SceneNote(val beat: Double, val kind: SceneKind, val song: Int, val other: Int = -1)

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
    /** The story of the mix, in order. */
    val scenes: List<SceneNote> = emptyList(),
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
    val sustain: FloatArray
    val hook: FloatArray
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
                sustain = FloatArray(beats.size) { a.beatSustain[it / 2] }
                hook = FloatArray(beats.size) { a.beatHook[it / 2] }
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
                sustain = FloatArray(count) { pair(a.beatSustain, it) }
                hook = FloatArray(count) { pair(a.beatHook, it) }
                downbeat = ((a.downbeat - first) / 2) % 4
                phrase = (((a.phrase - first) / 2) % 32 + 32) % 32
            }
            else -> {
                beats = a.beats
                low = a.beatLow
                mid = a.beatMid
                full = a.beatFull
                sustain = a.beatSustain
                hook = a.beatHook
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
 * Decides the whole mix the way a DJ plans a set:
 *
 *  - one shared tempo and a pitch shift per song so the keys fit;
 *  - an energy curve for the whole thing - ease in, build to a first high point, take a breath,
 *    build to the biggest moment about four fifths of the way through, then land;
 *  - for every song, the part to use: the hook (the phrase that keeps coming back) for when its
 *    voice is on top or it plays on its own, and a stretch with strong drums and little singing
 *    for when it is the beat underneath;
 *  - a chain in which each new song first sings over the previous song's beat and then takes
 *    over the rhythm itself, so something always carries on while something else changes;
 *  - "drops": at the high points the old beat has its bass pulled out for a bar and echoes away,
 *    and the new song lands with everything at once on the first beat of the next phrase.
 *
 * The length is chosen in whole phrases to come as close as possible to what was asked for.
 */
object MixPlanner {
    /** Beats in an eight-bar phrase. */
    const val SEGMENT = 32

    /** What a song is doing during one stretch of its time in the mix. */
    private enum class Part(val role: Role) {
        /** Alone at the very start. */
        INTRO(Role.FULL),

        /** Voice and melody over another song's beat. */
        TOP(Role.TOP),

        /** Everything at once, on its own: the big moment. */
        DROP(Role.FULL),

        /** Bass and drums under another song's voice. */
        BED(Role.BEAT),

        /** Alone at the very end, fading. */
        OUTRO(Role.FULL),
    }

    /** Length for "you decide": longer with more songs. */
    fun autoSeconds(songs: Int): Float = when (songs) {
        2 -> 150f
        3 -> 185f
        4 -> 215f
        else -> 240f
    }

    /** The energy a DJ aims for at each point of a set, from 0 (start) to 1 (end). Returns 0.45 to 1. */
    fun energyArc(t: Float): Float {
        fun mix(a: Float, b: Float, x: Float) = a + (b - a) * x.coerceIn(0f, 1f)
        return when {
            t < 0.30f -> mix(0.45f, 0.85f, t / 0.30f)
            t < 0.50f -> mix(0.85f, 0.55f, (t - 0.30f) / 0.20f)
            t < 0.82f -> mix(0.55f, 1.00f, (t - 0.50f) / 0.32f)
            else -> mix(1.00f, 0.60f, (t - 0.82f) / 0.18f)
        }
    }

    /**
     * [targetSeconds] is how long the mashup should be; 0 lets the planner choose. The result is
     * the nearest whole number of phrases, and never shorter than it takes to hear every song.
     */
    fun plan(songs: List<SongAnalysis>, seed: Long = 0, shuffle: Boolean = false, targetSeconds: Float = 0f): MixPlan {
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

        // ---- how long, and in what size of steps ----
        val wanted = if (targetSeconds > 0f) targetSeconds else autoSeconds(n)
        val barsWanted = wanted * tempo / 60f / 4f
        // Eight-bar phrases as a rule; four-bar ones (quick cuts) when there is little time per song.
        val phraseBars = if (barsWanted / n >= 14f) 8 else 4
        val unit = phraseBars * 4
        val phrases = max((barsWanted / phraseBars).roundToInt(), n + 1)

        // ---- the running order: an intro, one "turn" per song that comes in, an outro ----
        // In its turn a song sings over the previous song's beat (the blend) and may then play
        // alone at full power (the drop) before it becomes the beat for the next one.
        val body = phrases - 2
        val entries = (body / (if (phraseBars == 8) 2.5f else 2.2f)).roundToInt().coerceIn(n - 1, max(n - 1, body))
        val blend = IntArray(entries + 1) { if (it == 0) 0 else 1 }
        val drop = IntArray(entries + 1)
        var spare = body - entries

        fun startOf(k: Int): Int {
            var at = 1
            for (j in 1 until k) at += blend[j] + drop[j]
            return at
        }
        // Drops go where the energy should be highest - and the last song always gets one.
        val dropsWanted = min(spare, (entries + 1) / 2)
        fun placeDrops() {
            java.util.Arrays.fill(drop, 0)
            // Judge each turn by the moment its drop would start.
            val byEnergy = (1..entries).sortedByDescending { k ->
                energyArc((startOf(k) + blend[k] + 0.5f) / phrases) + random.nextFloat() * 0.03f + if (k == entries) 1f else 0f
            }
            for (k in byEnergy.take(dropsWanted)) drop[k] = 1
        }
        placeDrops()
        spare -= dropsWanted
        // What is left lengthens the blends, quietest parts of the set first (they can take it).
        while (spare > 0) {
            val k = (1..entries).minBy { blend[it] * 10f + energyArc((startOf(it) + 1f) / phrases) }
            blend[k]++
            spare--
        }
        // Longer blends moved everything along, so look once more at where the drops belong.
        placeDrops()

        // ---- choose the part of each song to use ----
        val used = views.map { IntArray(it.beats.size) }
        val runs = ArrayList<Run>()
        val scenes = ArrayList<SceneNote>()
        fun songOf(k: Int) = order[k % n]
        fun gainOf(song: Int) = (TARGET_RMS / max(songs[song].rms, 1e-4f)).coerceIn(0.4f, 3f)

        for (k in 0..entries) {
            val song = songOf(k)
            val isLast = k == entries
            val parts = ArrayList<Pair<Part, Int>>()
            if (k == 0) {
                parts += Part.INTRO to unit
            } else {
                parts += Part.TOP to blend[k] * unit
                if (drop[k] > 0) parts += Part.DROP to drop[k] * unit
            }
            if (isLast) parts += Part.OUTRO to unit else parts += Part.BED to blend[k + 1] * unit
            val outStart = if (k == 0) 0 else startOf(k) * unit
            // The beat makes way for the next song's drop: bass out for the last bar, then an echo.
            val nextDrops = !isLast && drop[k + 1] > 0
            runs += chooseSections(
                song, views[song], used[song], parts, outStart, unit,
                totalBeats = phrases * unit,
                isFirst = k == 0, isLast = isLast,
                bassCut = if (nextDrops) (if (unit >= 32) 4 else 2) else 0,
                semitones = semitones[song], gain = gainOf(song), random = random,
            )

            if (k == 0) {
                scenes += SceneNote(0.0, SceneKind.INTRO, song)
            } else {
                scenes += SceneNote(outStart.toDouble(), SceneKind.BLEND, song, songOf(k - 1))
                if (drop[k] > 0) scenes += SceneNote((outStart + blend[k] * unit).toDouble(), SceneKind.DROP, song)
            }
            if (isLast) scenes += SceneNote(((phrases - 1) * unit).toDouble(), SceneKind.OUTRO, song)
        }

        // Even out the layers: every bassline at a similar level, every "top" at a similar level,
        // so a bass-light song doesn't make a thin beat and a quiet vocal isn't buried.
        val bassLevels = runs.map { it.bass }.filter { it > 0f }.sorted()
        val topLevels = runs.map { it.top }.filter { it > 0f }.sorted()
        val bassTarget = if (bassLevels.isEmpty()) 0f else bassLevels[bassLevels.size / 2]
        val topTarget = if (topLevels.isEmpty()) 0f else topLevels[topLevels.size / 2]
        fun boost(target: Float, own: Float): Float =
            if (target <= 0f || own <= 0f) 1f else kotlin.math.sqrt(target / own).coerceIn(0.6f, 1.6f)

        val clips = runs.flatMap {
            buildClips(it, views[it.song], boost(bassTarget, it.bass), boost(topTarget, it.top), songs[it.song].hits, unit)
        }
        return MixPlan(tempo, (phrases * unit).toDouble(), clips, planned, order, scenes)
    }

    /**
     * A stretch of one song that plays straight through in the mix: one or more parts in a
     * row that are also in a row in the song itself.
     */
    private class Run(
        val song: Int,
        val parts: List<Pair<Part, Int>>,
        val outStart: Int,
        val start: Int,
        /** Shorter than the run's length when a short song has to be looped. */
        val span: Int,
        val isFirst: Boolean,
        val isLast: Boolean,
        /** Beats at the very end during which the bass is pulled out. */
        val bassCut: Int,
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

    /**
     * Finds, for each thing a song has to do during its stay, the stretch of the song that suits
     * it: the hook where it plays alone, singing where it is on top, strong drums and little
     * singing where it is the beat - each with the energy that fits that point of the set.
     *
     * The big moment is chosen first and the rest around it. Parts prefer to follow on from each
     * other the way the song itself runs, but the planner will jump to a better spot when that
     * is worth more - the way a DJ jumps to a cue point to land the chorus on the drop.
     */
    private fun chooseSections(
        song: Int,
        view: BeatView,
        used: IntArray,
        parts: List<Pair<Part, Int>>,
        outStart: Int,
        unit: Int,
        totalBeats: Int,
        isFirst: Boolean,
        isLast: Boolean,
        bassCut: Int,
        semitones: Int,
        gain: Float,
        random: Random,
    ): List<Run> {
        val size = view.beats.size
        val meanLow = view.low.average().toFloat().coerceAtLeast(1e-12f)
        val meanMid = view.mid.average().toFloat().coerceAtLeast(1e-12f)
        val meanFull = view.full.average().toFloat().coerceAtLeast(1e-12f)
        val meanSustain = view.sustain.average().toFloat().coerceAtLeast(1e-12f)

        // Where each part sits in the mix.
        val offsets = IntArray(parts.size)
        for (p in 1 until parts.size) offsets[p] = offsets[p - 1] + parts[p - 1].second

        fun score(part: Part, at: Int, start: Int, span: Int, length: Int): Float {
            var total = 0f
            for (b in 0 until length) {
                val i = start + b % span
                val low = min(view.low[i] / meanLow, 2f)
                val mid = min(view.mid[i] / meanMid, 2f)
                val full = min(view.full[i] / meanFull, 2f)
                val voice = min(view.sustain[i] / meanSustain, 2f)
                val hook = view.hook[i]
                val value = when (part) {
                    // On top: singing or melody that people will recognise.
                    Part.TOP -> 0.45f * mid + 0.45f * voice + 0.7f * hook
                    // Underneath: bass and drums, and as little singing as possible to clash.
                    Part.BED -> low + 0.3f * full - 0.45f * voice
                    // Alone at full power: the chorus.
                    Part.DROP -> 0.7f * full + 1.1f * hook + 0.3f * low
                    // Alone at the start: a clear groove without much singing, to build from.
                    Part.INTRO -> 0.35f * full + 0.6f * low - 0.25f * voice
                    // Alone at the end: the song as people know it.
                    Part.OUTRO -> 0.5f * full + 0.5f * low + 0.3f * hook
                }
                // Loud parts at the high points of the set, calmer ones where it breathes.
                // A drop is a high point wherever it falls.
                var wanted = energyArc((outStart + at + b + 0.5f) / totalBeats)
                if (part == Part.DROP) wanted = max(wanted, 0.95f)
                val fit = (wanted - 0.7f) * (full - 1f) * 0.9f
                // Near-silence (intros, breaks, fade-outs) is a poor pick, and so are bars heard
                // before - except that a chorus is welcome back for a drop: it is the hook, after all.
                val quiet = if (view.full[i] < 0.08f * meanFull) 2f else 0f
                val again = when (part) {
                    Part.DROP -> 0.4f
                    Part.TOP -> 1.0f
                    else -> 1.5f
                }
                total += value + fit - quiet - again * used[i]
            }
            return total / length
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

        // The start chosen for each part, and how much of the song it covers before looping.
        val startOf = IntArray(parts.size) { -1 }
        val spanOf = IntArray(parts.size)

        /** Picks the best stretch for part [p]; [after] / [before] are neighbours it would like to join up with. */
        fun choose(p: Int, after: Int, before: Int) {
            val (part, length) = parts[p]
            var span = length
            var starts = candidates(span, unit, view.phrase)
            if (starts.isEmpty()) starts = candidates(span, 4, view.downbeat)
            if (starts.isEmpty()) {
                // The song is shorter than this part: loop the longest phrase-sized piece that fits.
                span = listOf(64, 32, 16, 8).firstOrNull { it < length && it + 2 < size } ?: return
                starts = candidates(span, min(unit, span), view.phrase).ifEmpty { candidates(span, 4, view.downbeat) }
                if (starts.isEmpty()) return
            }
            val best = starts.maxBy { s ->
                var value = score(part, offsets[p], s, span, length) + random.nextFloat() * 0.1f
                // Carrying straight on from the previous part, or running straight into the next, is worth a little.
                if (after >= 0 && startOf[after] >= 0 && spanOf[after] == parts[after].second && s == startOf[after] + parts[after].second) value += FLOW_BONUS
                if (before >= 0 && startOf[before] >= 0 && span == length && s + length == startOf[before]) value += FLOW_BONUS
                // A set usually opens with the opening of a song: it was written as a way in.
                if (part == Part.INTRO) value -= 0.8f * s / size
                value
            }
            startOf[p] = best
            spanOf[p] = span
            for (b in 0 until min(span, length)) used[best + b]++
        }

        // The moment that matters most is settled first: the drop, else the singing, else the opening.
        val anchor = parts.indexOfFirst { it.first == Part.DROP }.takeIf { it >= 0 }
            ?: parts.indexOfFirst { it.first == Part.TOP }.takeIf { it >= 0 }
            ?: 0
        choose(anchor, -1, -1)
        for (p in anchor + 1 until parts.size) choose(p, p - 1, -1)
        for (p in anchor - 1 downTo 0) choose(p, -1, p + 1)

        // Parts that follow on from each other in the song become one uninterrupted run.
        val runs = ArrayList<Run>()
        var p = 0
        while (p < parts.size) {
            if (startOf[p] < 0) {
                p++
                continue
            }
            var q = p
            while (q + 1 < parts.size && startOf[q + 1] >= 0 &&
                spanOf[q] == parts[q].second && spanOf[q + 1] == parts[q + 1].second &&
                startOf[q + 1] == startOf[q] + parts[q].second
            ) {
                q++
            }
            val group = parts.subList(p, q + 1).toList()
            val length = group.sumOf { it.second }
            val span = if (p == q) spanOf[p] else length
            var bass = 0f
            var bassBeats = 0
            var top = 0f
            var topBeats = 0
            var b = 0
            for ((part, beats) in group) {
                repeat(beats) {
                    val i = startOf[p] + b % span
                    if (part == Part.BED) {
                        bass += view.low[i]; bassBeats++
                    } else if (part == Part.TOP) {
                        top += view.mid[i]; topBeats++
                    }
                    b++
                }
            }
            runs += Run(
                song = song,
                parts = group,
                outStart = outStart + offsets[p],
                start = startOf[p],
                span = span,
                isFirst = isFirst && p == 0,
                isLast = isLast && q == parts.size - 1,
                bassCut = if (q == parts.size - 1) bassCut else 0,
                semitones = semitones,
                gain = gain,
                bass = if (bassBeats == 0) 0f else gain * gain * bass / bassBeats,
                top = if (topBeats == 0) 0f else gain * gain * top / topBeats,
            )
            p = q + 1
        }
        return runs
    }

    private fun buildClips(turn: Run, view: BeatView, bassBoost: Float, topBoost: Float, hits: DoubleArray, unit: Int): List<Clip> {
        val parts = turn.parts.map { it.first.role to it.second }
        val length = parts.sumOf { it.second }
        val span = turn.span
        val start = turn.start
        val outStart = turn.outStart
        val isFirst = turn.isFirst
        val isLast = turn.isLast
        val song = turn.song

        // Cut the stay into pieces of continuous song (one piece unless we are looping).
        val clips = ArrayList<Clip>()
        var done = 0
        while (done < length) {
            val pieceLength = min(span, length - done)
            val pieceOut = outStart + done
            val first = isFirst && done == 0
            val final = done + pieceLength == length
            val pre = if (first) 0.0 else PRE_ROLL
            val clipStart = pieceOut - pre
            val clipEnd = (pieceOut + pieceLength).toDouble()

            val mapFrom = floor(clipStart).toInt() - 1
            val mapTo = clipEnd.toInt() + 2
            val mapBeats = DoubleArray(mapTo - mapFrom + 1) { (mapFrom + it).toDouble() }
            val mapSeconds = DoubleArray(mapBeats.size) { view.time(start + (mapBeats[it] - pieceOut)) }
            val bassCut = if (final) turn.bassCut else 0

            clips += Clip(
                song = song,
                startBeat = clipStart,
                endBeat = clipEnd,
                mapBeats = mapBeats,
                mapSeconds = mapSeconds,
                keys = gainKeys(parts, outStart, pieceOut, pieceLength, first, isLast && final, bassCut, bassBoost, topBoost, unit),
                semitones = turn.semitones,
                gain = turn.gain,
                hits = hits,
                // With its bass already out, the last beat of the old groove echoes into the drop.
                echoBeats = if (bassCut > 0) 1.0 else 0.0,
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
        bassCut: Int,
        bassBoost: Float,
        topBoost: Float,
        unit: Int,
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
            } else if (role == Role.BEAT && unit >= 32 && (beat - turnStart) % unit == 0 && beat - pieceStart >= unit) {
                // A long stretch under one beat gets a breath at each phrase line: the bass ducks
                // out for the last two beats and comes back on the "one", as a DJ would flick it.
                val l = levels(role)
                keys += GainKey(beat - 2.25, l[0], l[1], l[2])
                keys += GainKey(beat - 2.0, 0f, l[1], l[2])
                keys += GainKey(beat - 0.25, 0f, l[1], l[2])
                keys += GainKey(beat.toDouble(), l[0], l[1], l[2])
            }
        }
        val end = (pieceStart + pieceLength).toDouble()
        if (ending) {
            // Final fade-out: four bars when there is room, so the last song settles rather than stops.
            val l = levels(role)
            val fade = if (pieceLength >= 32) 16 else min(8, pieceLength)
            keys += GainKey(end - fade, l[0], l[1], l[2])
            keys += GainKey(end, 0f, 0f, 0f)
        } else if (bassCut > 0 && pieceLength > bassCut + 1) {
            // "Kill the bass": the low end drops out for the last bar, leaving a gap the
            // next song's first beat fills. The rest plays on and then stops dead on the line.
            val l = levels(role)
            val thin = floatArrayOf(0f, l[1], l[2])
            keys += GainKey(end - bassCut - 0.25, l[0], l[1], l[2])
            keys += GainKey(end - bassCut, thin[0], thin[1], thin[2])
            change(end, thin, silent)
        } else {
            change(end, levels(role), silent)
        }
        return keys
    }

    /** How much a part gains by following straight on from its neighbour in the song. */
    private const val FLOW_BONUS = 0.22f

    /** How long before its bar line an incoming part starts to fade in, in beats. */
    const val PRE_ROLL = 0.5
    private const val TARGET_RMS = 0.16f
    private const val MAX_SHIFT = 3

    /** How much better the notes must match to justify one more semitone of pitch change. */
    private const val SHIFT_COST = 0.04f
}
