package com.noexcusehere.mashwave.desktop.vlc

import java.io.File

/**
 * VLC's engine used as a converter rather than a player: a sound file goes in, another kind
 * comes out, as fast as the computer can manage (nothing is played, and nothing is heard).
 * Mashups use it to read any song in and to pack the finished mix into a small file.
 */
class VlcConvert(private val found: VlcFinder.Found) {
    private val lib = found.lib

    /** The kinds of file a finished mix can be saved as, best first. */
    enum class Kind(val ending: String, val chain: String, val box: String) {
        M4A("m4a", "acodec=mp4a,ab=192", "mp4"),
        MP3("mp3", "acodec=mp3,ab=192", "raw"),
        FLAC("flac", "acodec=flac", "raw"),
    }

    /**
     * Reads [source] from its start into [wav] as plain sound (44.1 kHz, stereo, 16-bit), stopping
     * after [maxSeconds]. True when it ran to the end.
     */
    fun toWav(source: File, wav: File, maxSeconds: Int, keepGoing: () -> Boolean, onProgress: (Float) -> Unit): Boolean =
        run(source, "#transcode{acodec=s16l,channels=2,samplerate=44100}:standard{access=file,mux=wav,dst=${quote(wav)}}", listOf(":stop-time=$maxSeconds"), keepGoing, onProgress) &&
            wav.length() > 4096

    /** Packs [wav] into [target] as [kind]. True when it ran to the end (the caller checks the file is sound). */
    fun pack(wav: File, target: File, kind: Kind, keepGoing: () -> Boolean, onProgress: (Float) -> Unit): Boolean =
        run(wav, "#transcode{${kind.chain},channels=2,samplerate=44100}:standard{access=file,mux=${kind.box},dst=${quote(target)}}", emptyList(), keepGoing, onProgress) &&
            target.length() > 4096

    /** A file's place, written the way VLC wants it inside its instructions. */
    private fun quote(file: File): String = "\"" + file.absolutePath.replace('\\', '/').replace("\"", "\\\"") + "\""

    private fun run(source: File, chain: String, extra: List<String>, keepGoing: () -> Boolean, onProgress: (Float) -> Unit): Boolean {
        val args = arrayOf("--ignore-config", "--quiet", "--no-metadata-network-access", "--no-sub-autodetect-file")
        val instance = lib.libvlc_new(args.size, args) ?: return false
        var player: com.sun.jna.Pointer? = null
        try {
            val media = lib.libvlc_media_new_path(instance, source.absolutePath) ?: return false
            lib.libvlc_media_add_option(media, ":sout=$chain")
            // Only the sound is wanted: a cover picture inside a song must not be treated as a video.
            lib.libvlc_media_add_option(media, ":no-video")
            lib.libvlc_media_add_option(media, ":no-sout-video")
            lib.libvlc_media_add_option(media, ":no-spu")
            for (option in extra) lib.libvlc_media_add_option(media, option)
            player = lib.libvlc_media_player_new(instance)
            if (player == null) {
                lib.libvlc_media_release(media)
                return false
            }
            lib.libvlc_media_player_set_media(player, media)
            lib.libvlc_media_release(media)
            if (lib.libvlc_media_player_play(player) != 0) return false
            val began = System.currentTimeMillis()
            var started = false
            var lastMoved = began
            var lastTime = -1L
            while (true) {
                if (!keepGoing()) return false
                when (lib.libvlc_media_player_get_state(player)) {
                    LibVlc.ENDED -> return true
                    LibVlc.ERROR -> return false
                    // "Stopped" after having been under way is how some files finish.
                    LibVlc.STOPPED -> if (started) return true
                    LibVlc.PLAYING -> started = true
                }
                val now = System.currentTimeMillis()
                val time = lib.libvlc_media_player_get_time(player)
                val length = lib.libvlc_media_player_get_length(player)
                if (time != lastTime) {
                    lastTime = time
                    lastMoved = now
                }
                if (length > 0 && time >= 0) onProgress((time.toFloat() / length).coerceIn(0f, 1f))
                // Never started, or stuck: give up rather than wait for ever.
                if (!started && now - began > 30_000) return false
                if (now - lastMoved > 60_000) return false
                Thread.sleep(40)
            }
        } finally {
            player?.let {
                runCatching { lib.libvlc_media_player_stop(it) }
                runCatching { lib.libvlc_media_player_release(it) }
            }
            runCatching { lib.libvlc_release(instance) }
        }
    }
}
