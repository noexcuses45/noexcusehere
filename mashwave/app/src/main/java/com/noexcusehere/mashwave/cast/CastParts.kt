package com.noexcusehere.mashwave.cast

import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.iptv.isLiveTv
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/*
 * The parts of casting that have nothing to do with which kind of device is doing the sending:
 * what a TV is, what it can be asked, why something might not start, and how to say so.
 * Plain Kotlin, shared by the phone app and the computer app.
 */

enum class CastKind { DLNA, CHROMECAST }

/**
 * The two things that can be sent to a TV, switched on separately: a show can run on the TV
 * while music keeps playing on the phone or a Bluetooth speaker.
 */
enum class CastWhat { MUSIC, VIDEO }

/** A TV or speaker the user can send music and videos to. */
data class CastDevice(val id: String, val name: String, val detail: String, val kind: CastKind)

data class CastState(
    val scanning: Boolean = false,
    val devices: List<CastDevice> = emptyList(),
    /** Id of the device we are in the middle of connecting to. */
    val connecting: String? = null,
    /** The device we are playing on, once connected. */
    val device: CastDevice? = null,
    val queue: List<MediaEntry> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** TV volume from 0 to 1, when the TV tells us. */
    val volume: Float? = null,
    val problem: String? = null,
    /** False when the phone is not on Wi-Fi, so no TV can be reached. */
    val onNetwork: Boolean = true,
    /** Videos and TV channels the user starts go to the TV. */
    val sendVideo: Boolean = false,
    /** Songs the user starts go to the TV. */
    val sendMusic: Boolean = false,
) {
    fun sends(what: CastWhat): Boolean = if (what == CastWhat.VIDEO) sendVideo else sendMusic

    val entry: MediaEntry?
        get() = queue.getOrNull(index)
}

enum class RemoteState { PLAYING, PAUSED, LOADING, STOPPED, FINISHED }

/** Why something would not start on the TV. */
enum class CastWhy {
    /** The phone has no Wi-Fi, so the TV cannot reach it. */
    NO_WIFI,

    /** The TV service refused or failed to send the stream. */
    SERVICE,

    /** The TV never came to the phone for the stream. */
    NOT_COLLECTED,

    /** The TV answered "no" when told to play. */
    TV_SAID_NO,

    /** The TV stopped answering. */
    TV_SILENT,

    /** The TV took the stream and could not play it. */
    UNSUPPORTED,
}

/** The phone is not on a home network, so nothing on it can be fetched by a TV. */
class NoWifiException : IllegalStateException("The phone is not on Wi-Fi")

data class RemoteStatus(val state: RemoteState, val positionMs: Long, val durationMs: Long, val volume: Float?)

/** What every kind of connected device must be able to do. All calls may block or fail. */
interface CastLink {
    suspend fun load(url: String, entry: MediaEntry, mime: String, artUrl: String?, startMs: Long)
    suspend fun play()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(level: Float)
    suspend fun stop()

    /** Null when the device did not answer. */
    suspend fun status(): RemoteStatus?
    fun close()

    /** The kinds of file the device says it plays (MIME types); empty when it does not say. */
    val accepts: Set<String>
        get() = emptySet()
}

/** The rules of thumb about what a TV did, kept apart from any one app. */
object CastRules {
    /**
     * True when something stopped well before its end - a lost stream or a press of "stop",
     * not a film running out. Without a believable position (some TVs never report one) it
     * counts as finished, as before.
     */
    fun stoppedEarly(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs < 60_000 || positionMs < 5_000) return false
        return positionMs < durationMs - maxOf(20_000L, durationMs / 20)
    }

    /**
     * Works out why something would not start, from what is known: the [error] if there was
     * one, how often the TV [asked] the phone for the stream (null when the phone was not
     * feeding it), how much was [handedOver], and what the service said was wrong ([trouble]).
     */
    fun whyNot(error: Throwable?, asked: Int?, handedOver: Long, trouble: String?): CastWhy = when {
        error is NoWifiException -> CastWhy.NO_WIFI
        trouble != null -> CastWhy.SERVICE
        // No answer at all from the TV (as opposed to an answer of "no").
        error != null && error !is DlnaException -> CastWhy.TV_SILENT
        asked == 0 -> CastWhy.NOT_COLLECTED
        error is DlnaException && handedOver == 0L -> CastWhy.TV_SAID_NO
        else -> CastWhy.UNSUPPORTED
    }

    /** Answers from a service that mean "there is no such thing here". */
    val NOT_THERE = setOf(404, 410)

    /**
     * The sentence shown to the viewer. [detail] is the service's or the TV's own explanation,
     * when there is one; [busyElsewhere] adds the hint about plans that allow one screen at a time.
     */
    fun wordsFor(why: CastWhy, tv: String, title: String, detail: String?, busyElsewhere: Boolean = true): String = when (why) {
        CastWhy.NO_WIFI -> "The phone isn't on Wi-Fi, so $tv can't collect the video from it. Join the same Wi-Fi as the TV, then tap play."
        CastWhy.SERVICE ->
            "Your TV service didn't send “$title”" + (if (detail.isNullOrBlank()) "" else " (${detail.trimEnd('.')})") +
                (if (busyElsewhere) ". If your plan allows one screen at a time, make sure nothing else is using it, then tap play." else ". Tap play to try again.")
        CastWhy.NOT_COLLECTED -> "$tv didn't collect “$title” from the phone. Check the phone and the TV are on the same Wi-Fi, then tap play."
        CastWhy.TV_SAID_NO -> "$tv wouldn't start “$title”" + (if (detail.isNullOrBlank()) "" else " (it said: ${detail.trimEnd('.')})") + ". Tap play to try again."
        CastWhy.TV_SILENT -> "$tv isn't answering. Check it is still on, then tap play."
        CastWhy.UNSUPPORTED -> "$tv couldn't play “$title”. It may not support this kind of file."
    }

    /**
     * Corrects [declared] when the first [count] bytes of the file ([head]) show it is really
     * a different kind of file. Unknown beginnings leave the declared type alone.
     */
    fun refineMime(declared: String, head: ByteArray, count: Int, isVideo: Boolean): String {
        if (count < 12) return declared
        fun at(offset: Int, text: String) = text.indices.all { head[offset + it] == text[it].code.toByte() }
        val lower = declared.lowercase()
        return when {
            at(4, "ftyp") ->
                if (listOf("mp4", "m4", "quicktime", "3gp").any { it in lower }) declared else if (isVideo) "video/mp4" else "audio/mp4"
            at(0, "fLaC") -> if ("flac" in lower) declared else "audio/flac"
            at(0, "OggS") -> if (listOf("ogg", "opus", "vorbis").any { it in lower }) declared else if (isVideo) "video/ogg" else "audio/ogg"
            at(0, "RIFF") && at(8, "WAVE") -> if ("wav" in lower) declared else "audio/wav"
            at(0, "RIFF") && at(8, "AVI ") -> if ("avi" in lower || "msvideo" in lower) declared else "video/x-msvideo"
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() ->
                if (listOf("matroska", "webm").any { it in lower }) declared else if (isVideo) "video/x-matroska" else "audio/x-matroska"
            at(0, "ID3") -> if ("mpeg" in lower || "mp3" in lower) declared else "audio/mpeg"
            else -> declared
        }
    }
}

/** A smart TV or other device controlled the standard home-network way. */
class DlnaLink(private val device: DlnaRenderer, override val accepts: Set<String> = emptySet()) : CastLink {
    override suspend fun load(url: String, entry: MediaEntry, mime: String, artUrl: String?, startMs: Long) {
        runCatching { Dlna.stop(device) }
        val features = if (entry.isLiveTv) MediaServer.DLNA_LIVE_FEATURES else MediaServer.DLNA_FEATURES
        val metadata = Dlna.metadata(url, entry.title, entry.artist, mime, entry.isVideo, entry.durationMs, artUrl, features)
        try {
            Dlna.setUri(device, url, metadata)
        } catch (e: DlnaException) {
            // Some TVs choke on the description; the bare address is enough for them.
            Dlna.setUri(device, url, "")
        }
        // The TV may still be collecting the start of the file when it is first told to play,
        // and says "not now". Keep asking for a while rather than giving up after two seconds.
        val giveUpAt = System.currentTimeMillis() + START_PATIENCE_MS
        var last: Exception? = null
        var attempt = 0
        while (true) {
            try {
                Dlna.play(device)
                last = null
                break
            } catch (e: Exception) {
                last = e
                // It may have started by itself while the answer was on its way.
                val now = runCatching { Dlna.transportState(device) }.getOrNull()
                if (now == "PLAYING" || now == "TRANSITIONING") {
                    last = null
                    break
                }
                // "No such file" and "wrong kind of file" will not change by asking again.
                val final = e is DlnaException && e.code in FINAL_REFUSALS && attempt >= 1
                if (final || System.currentTimeMillis() >= giveUpAt) break
                delay((600L + 300L * attempt).coerceAtMost(2000L))
                attempt++
            }
        }
        last?.let { throw it }
        if (startMs > 3000) {
            // Wait until it is really playing before jumping to where it was left off. A TV that
            // just sits at "stopped" is not going to start; there is nothing to jump in.
            var idle = 0
            var started = false
            for (i in 0 until 40) {
                delay(500)
                val now = runCatching { Dlna.transportState(device) }.getOrNull()
                if (now == "PLAYING") {
                    started = true
                    break
                }
                idle = if (now == "STOPPED" || now == "NO_MEDIA_PRESENT") idle + 1 else 0
                if (idle >= 12) break
            }
            // Still busy loading after all that waiting: ask for the jump anyway, as it may yet start.
            if (started || idle < 12) runCatching { Dlna.seek(device, startMs) }
        }
    }

    override suspend fun play() = Dlna.play(device)

    override suspend fun pause() = Dlna.pause(device)

    override suspend fun seek(positionMs: Long) = Dlna.seek(device, positionMs)

    override suspend fun setVolume(level: Float) = Dlna.setVolume(device, (level * 100).toInt())

    override suspend fun stop() = Dlna.stop(device)

    private var polls = 0
    private var volume: Float? = null

    override suspend fun status(): RemoteStatus? {
        val state = runCatching { Dlna.transportState(device) }.getOrNull() ?: return null
        val position = runCatching { Dlna.position(device) }.getOrNull()
        // Volume changes rarely, so only ask now and then.
        if (polls++ % 5 == 0) volume = runCatching { Dlna.volume(device) }.getOrNull()?.let { it / 100f } ?: volume
        return RemoteStatus(
            state = when (state) {
                "PLAYING" -> RemoteState.PLAYING
                "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> RemoteState.PAUSED
                "TRANSITIONING" -> RemoteState.LOADING
                else -> RemoteState.STOPPED
            },
            positionMs = position?.positionMs ?: 0,
            durationMs = position?.durationMs ?: 0,
            volume = volume,
        )
    }

    override fun close() = Unit

    private companion object {
        /** How long to keep asking a TV to start something it has accepted. */
        const val START_PATIENCE_MS = 20_000L

        /** Answers that mean "never": bad request, wrong kind of file, no such file. */
        val FINAL_REFUSALS = setOf(402, 714, 716)
    }
}

class FileSource(private val file: File, override val mime: String) : ServedSource {
    override val length: Long
        get() = file.length()

    override fun open(offset: Long): InputStream = FileInputStream(file).also { skipFully(it, offset) }
}

internal fun skipFully(stream: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val skipped = stream.skip(left)
        if (skipped <= 0) {
            if (stream.read() < 0) return
            left--
        } else {
            left -= skipped
        }
    }
}
