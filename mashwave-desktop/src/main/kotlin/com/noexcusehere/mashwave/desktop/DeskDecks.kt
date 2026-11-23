package com.noexcusehere.mashwave.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** Two songs and how each was set, kept to be opened again. */
@Serializable
data class SavedMix(
    val id: String,
    val name: String,
    val a: LocalFile,
    val b: LocalFile,
    val tempoA: Float = 1f,
    val tempoB: Float = 1f,
    val pitchA: Float = 0f,
    val pitchB: Float = 0f,
    val volumeA: Float = 1f,
    val volumeB: Float = 1f,
    val crossfade: Float = 0.5f,
    val startA: Long = 0,
    val startB: Long = 0,
    val bpmA: Float = 0f,
    val bpmB: Float = 0f,
    val createdAt: Long = 0,
)

@Serializable
private data class MixesSaved(val mixes: List<SavedMix> = emptyList())

/** The sums of the two decks, the same as the phone app's. */
object DeckMaths {
    /**
     * Crossfader curve made for layering: in the middle both decks are at full volume, and each
     * side only fades out over its own half of the slider. [x] runs from 0 (only A) to 1 (only B).
     */
    fun crossfadeGains(x: Float): Pair<Float, Float> {
        val c = x.coerceIn(0f, 1f)
        return (2f * (1f - c)).coerceIn(0f, 1f) to (2f * c).coerceIn(0f, 1f)
    }

    /**
     * The speed that brings a song of [mine] beats a minute onto a beat of [other] beats a
     * minute, preferring half or double time when that needs less stretching. Null when a beat is unknown.
     */
    fun matching(mine: Float, other: Float): Float? {
        if (mine <= 0f || other <= 0f) return null
        var target = other / mine
        while (target > 1.45f) target /= 2f
        while (target < 0.7f) target *= 2f
        return target.coerceIn(0.5f, 2f)
    }

    /** Beats a minute from the moments (in milliseconds) somebody tapped along; null with fewer than three taps. */
    fun tapped(taps: List<Long>): Float? {
        if (taps.size < 3) return null
        val gap = (taps.last() - taps.first()).toFloat() / (taps.size - 1)
        return if (gap > 0) 60_000f / gap else null
    }
}

/** One of the two decks: a song, how it is set, and the player that plays it. */
class Deck {
    var song by mutableStateOf<LocalFile?>(null)
        internal set

    /** Speed: 1 is as recorded, from half to double. The pitch stays where it was. */
    var tempo by mutableFloatStateOf(1f)
        internal set

    /** Pitch shift in semitones, -12 to +12. */
    var pitch by mutableFloatStateOf(0f)
        internal set
    var volume by mutableFloatStateOf(1f)
        internal set

    /** Beats a minute of the song as recorded; 0 when unknown. */
    var bpm by mutableFloatStateOf(0f)
        internal set

    /** True while the song's beat is being worked out. */
    var analysing by mutableStateOf(false)
        internal set

    /** Where this deck starts when the mix is started over. */
    var cueMs by mutableLongStateOf(0L)
        internal set

    var playing by mutableStateOf(false)
        internal set
    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set

    /** The beat as it is heard, once the speed is applied. */
    val heardBpm: Float get() = bpm * tempo

    internal var player: VlcPlayer? = null

    /** The pitch the player was started with; another pitch needs the player started afresh. */
    internal var playerPitch = 0f
    internal var analysis: Job? = null
    internal val taps = ArrayList<Long>()
}

/**
 * Two decks that play at the same time so two songs can be layered: each has its own speed,
 * pitch and loudness, with a crossfader between them.
 */
class DeskDecks(private val desk: Desk, private val scope: CoroutineScope, dataDir: File) {
    val decks = listOf(Deck(), Deck())

    /** 0 = only deck A, 0.5 = both at full loudness, 1 = only deck B. */
    var crossfade by mutableFloatStateOf(0.5f)
        private set

    private val file = File(dataDir, "mixes.json")
    var mixes by mutableStateOf(
        runCatching { AppJson.decodeFromString(MixesSaved.serializer(), file.readText()).mixes }.getOrDefault(emptyList()).sortedByDescending { it.createdAt },
    )
        private set

    /** The saved mix that is open, if the decks were filled from one. */
    var loaded by mutableStateOf<SavedMix?>(null)
        private set

    val ready: Boolean get() = decks.all { it.song != null }
    val anyPlaying: Boolean get() = decks.any { it.playing }

    private var watcher: Job? = null

    // ---- loading -----------------------------------------------------------------------------

    /** Puts [song] on deck [i] (0 is A, 1 is B), ready to start from [startMs], and works out its beat when [bpm] is not known. */
    fun load(i: Int, song: LocalFile, bpm: Float = 0f, startMs: Long = 0) {
        val deck = decks[i]
        drop(deck)
        deck.song = song
        deck.bpm = bpm
        deck.cueMs = startMs.coerceAtLeast(0)
        deck.positionMs = deck.cueMs
        deck.durationMs = song.durationMs
        deck.taps.clear()
        deck.analysis?.cancel()
        deck.analysing = false
        if (bpm <= 0f) {
            deck.analysing = true
            deck.analysis = scope.launch {
                val found = runCatching { desk.mashup.tempoOf(song.path) }.getOrNull()
                if (deck.song?.path == song.path) {
                    if (found != null && deck.bpm <= 0f) deck.bpm = found
                    deck.analysing = false
                }
            }
        }
    }

    fun clear(i: Int) {
        val deck = decks[i]
        drop(deck)
        deck.analysis?.cancel()
        deck.song = null
        deck.bpm = 0f
        deck.analysing = false
        deck.cueMs = 0
        deck.positionMs = 0
        deck.durationMs = 0
        loaded = null
    }

    private fun drop(deck: Deck) {
        val old = deck.player
        deck.player = null
        deck.playing = false
        if (old != null) Thread { runCatching { old.release() } }.apply { isDaemon = true }.start()
    }

    // ---- playing -----------------------------------------------------------------------------

    private fun start(i: Int, from: Long) {
        val deck = decks[i]
        val song = deck.song ?: return
        var player = deck.player
        if (player == null || deck.playerPitch != deck.pitch) {
            drop(deck)
            // Pitch is set when the player starts; the engine cannot change it on the fly.
            val extra = ArrayList<String>()
            extra += "--no-video"
            if (abs(deck.pitch) > 0.01f) {
                extra += "--audio-filter=scaletempo_pitch"
                extra += "--pitch-shift=" + String.format(java.util.Locale.ROOT, "%.2f", deck.pitch)
            }
            player = desk.spareNewPlayer(extra)
            if (player == null) {
                desk.say("The decks need the player, which could not be started.")
                return
            }
            deck.player = player
            deck.playerPitch = deck.pitch
        }
        // Only the decks make a sound while they play.
        desk.player?.pause()
        player.play(listOf(song.entry()), 0, startMs = from.coerceAtLeast(0))
        player.changeRate(deck.tempo)
        deck.playing = true
        deck.positionMs = from.coerceAtLeast(0)
        applyVolumes()
        watch()
    }

    /** Keeps each deck's position and "is it playing" up to date while anything plays. */
    private fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            var quiet = 0
            while (quiet < 20) {
                delay(100)
                var busy = false
                for (deck in decks) {
                    val player = deck.player ?: continue
                    when (player.status) {
                        PlayStatus.PLAYING -> {
                            busy = true
                            deck.playing = true
                            deck.positionMs = player.positionMs
                            if (player.durationMs > 0) deck.durationMs = player.durationMs
                        }
                        PlayStatus.LOADING -> busy = true
                        PlayStatus.PAUSED -> deck.playing = false
                        PlayStatus.ENDED, PlayStatus.FAILED, PlayStatus.IDLE -> {
                            if (deck.playing && player.status != PlayStatus.IDLE) deck.positionMs = deck.cueMs
                            if (player.status != PlayStatus.IDLE || !deck.playing) deck.playing = false
                        }
                    }
                }
                quiet = if (busy) 0 else quiet + 1
            }
        }
    }

    fun toggle(i: Int) {
        val deck = decks[i]
        val player = deck.player
        when {
            deck.song == null -> Unit
            deck.playing -> {
                player?.pause()
                deck.playing = false
                if (player != null) deck.positionMs = player.positionMs
            }
            player != null && player.status == PlayStatus.PAUSED && deck.playerPitch == deck.pitch -> {
                desk.player?.pause()
                player.toggle()
                player.changeRate(deck.tempo)
                deck.playing = true
                watch()
            }
            else -> start(i, deck.positionMs)
        }
    }

    fun playBoth() {
        for (i in decks.indices) if (decks[i].song != null && !decks[i].playing) toggle(i)
    }

    fun pauseBoth() {
        for (i in decks.indices) if (decks[i].playing) toggle(i)
    }

    /** Both decks from where each is set to start. */
    fun restartBoth() {
        for (i in decks.indices) if (decks[i].song != null) start(i, decks[i].cueMs)
    }

    /** Makes where deck [i] is now the place it starts from. */
    fun setCue(i: Int) {
        decks[i].cueMs = decks[i].positionMs
    }

    fun seekTo(i: Int, positionMs: Long) {
        val deck = decks[i]
        val to = positionMs.coerceIn(0, deck.durationMs.coerceAtLeast(0))
        deck.positionMs = to
        val player = deck.player
        if (player != null && (deck.playing || player.status == PlayStatus.PAUSED)) player.seekTo(to)
    }

    /** Slides deck [i] a little earlier or later, to line its beat up with the other deck's. */
    fun nudge(i: Int, deltaMs: Long) = seekTo(i, decks[i].positionMs + deltaMs)

    // ---- settings ----------------------------------------------------------------------------

    fun setTempo(i: Int, tempo: Float) {
        val deck = decks[i]
        deck.tempo = tempo.coerceIn(0.5f, 2f)
        deck.player?.changeRate(deck.tempo)
    }

    /** Sets the pitch of deck [i]. A playing deck picks it up with a short break, so call this when a slider is let go, not while it moves. */
    fun setPitch(i: Int, semitones: Float) {
        val deck = decks[i]
        val wanted = semitones.coerceIn(-12f, 12f)
        if (wanted == deck.pitch) return
        deck.pitch = wanted
        if (deck.playing) start(i, deck.player?.positionMs ?: deck.positionMs)
    }

    fun setVolume(i: Int, volume: Float) {
        decks[i].volume = volume.coerceIn(0f, 1f)
        applyVolumes()
    }

    fun changeCrossfade(value: Float) {
        crossfade = value.coerceIn(0f, 1f)
        applyVolumes()
    }

    private fun applyVolumes() {
        val gains = DeckMaths.crossfadeGains(crossfade)
        decks[0].player?.changeVolume((decks[0].volume * gains.first * 100).roundToInt())
        decks[1].player?.changeVolume((decks[1].volume * gains.second * 100).roundToInt())
    }

    fun setBpm(i: Int, bpm: Float) {
        decks[i].bpm = bpm.coerceIn(0f, 300f)
    }

    /** Tap along with the beat a few times to set the beats a minute by hand. */
    fun tap(i: Int) {
        val deck = decks[i]
        val now = System.nanoTime() / 1_000_000
        if (deck.taps.isNotEmpty() && now - deck.taps.last() > 2500) deck.taps.clear()
        deck.taps += now
        if (deck.taps.size > 8) deck.taps.removeAt(0)
        // What is heard is already sped up or slowed down, so that is undone.
        DeckMaths.tapped(deck.taps)?.let { setBpm(i, it / deck.tempo) }
    }

    /** Changes deck [i]'s speed so its beat matches the other deck's. False when a beat is not known. */
    fun matchTempo(i: Int): Boolean {
        val target = DeckMaths.matching(decks[i].bpm, decks[1 - i].heardBpm) ?: return false
        setTempo(i, target)
        return true
    }

    fun resetDeck(i: Int) {
        setTempo(i, 1f)
        setPitch(i, 0f)
        setVolume(i, 1f)
    }

    // ---- saved mixes ---------------------------------------------------------------------------

    private fun write() {
        val now = mixes
        scope.launch(Dispatchers.IO) {
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(AppJson.encodeToString(MixesSaved.serializer(), MixesSaved(now)))
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Keeps the two songs and how they are set under [name]. Null when a deck is empty. */
    fun save(name: String): SavedMix? {
        val a = decks[0].song ?: return null
        val b = decks[1].song ?: return null
        val mix = SavedMix(
            id = loaded?.id ?: UUID.randomUUID().toString(),
            name = name.trim().take(80).ifEmpty { "${a.title} + ${b.title}" },
            a = a,
            b = b,
            tempoA = decks[0].tempo,
            tempoB = decks[1].tempo,
            pitchA = decks[0].pitch,
            pitchB = decks[1].pitch,
            volumeA = decks[0].volume,
            volumeB = decks[1].volume,
            crossfade = crossfade,
            startA = decks[0].cueMs,
            startB = decks[1].cueMs,
            bpmA = decks[0].bpm,
            bpmB = decks[1].bpm,
            createdAt = System.currentTimeMillis(),
        )
        mixes = listOf(mix) + mixes.filterNot { it.id == mix.id }
        loaded = mix
        write()
        return mix
    }

    /** Fills the decks from [mix] and, with [autoPlay], starts both. */
    fun open(mix: SavedMix, autoPlay: Boolean) {
        load(0, mix.a, mix.bpmA, mix.startA)
        load(1, mix.b, mix.bpmB, mix.startB)
        decks[0].tempo = mix.tempoA.coerceIn(0.5f, 2f)
        decks[1].tempo = mix.tempoB.coerceIn(0.5f, 2f)
        decks[0].pitch = mix.pitchA.coerceIn(-12f, 12f)
        decks[1].pitch = mix.pitchB.coerceIn(-12f, 12f)
        decks[0].volume = mix.volumeA.coerceIn(0f, 1f)
        decks[1].volume = mix.volumeB.coerceIn(0f, 1f)
        crossfade = mix.crossfade.coerceIn(0f, 1f)
        loaded = mix
        if (autoPlay) restartBoth()
    }

    fun delete(mix: SavedMix) {
        mixes = mixes.filterNot { it.id == mix.id }
        if (loaded?.id == mix.id) loaded = null
        write()
    }

    /** The app is closing. */
    fun close() {
        decks.forEach { deck ->
            deck.analysis?.cancel()
            deck.player?.let { runCatching { it.release() } }
            deck.player = null
        }
    }
}
