package com.noexcusehere.mashwave.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * Asks Android for the speakers, and hears about it when something else takes them. Android 8
 * changed how this is asked; older devices (among them the first Fire TV Stick 4K) are asked
 * the old way.
 */
class SpeakerFocus(private val audio: AudioManager, private val film: Boolean, private val onChange: (Int) -> Unit) {
    private val listener = AudioManager.OnAudioFocusChangeListener { change -> onChange(change) }
    private val modern: Any? = if (Build.VERSION.SDK_INT >= 26) Modern.request(film, listener) else null

    /** True when the speakers are ours. */
    fun take(): Boolean {
        val request = modern
        val answer = if (Build.VERSION.SDK_INT >= 26 && request != null) {
            Modern.take(audio, request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return answer == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun drop() {
        val request = modern
        if (Build.VERSION.SDK_INT >= 26 && request != null) {
            Modern.drop(audio, request)
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(listener)
        }
    }

    @RequiresApi(26)
    private object Modern {
        fun request(film: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Any =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (film) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                .build()

        fun take(audio: AudioManager, request: Any): Int = audio.requestAudioFocus(request as AudioFocusRequest)

        fun drop(audio: AudioManager, request: Any) {
            audio.abandonAudioFocusRequest(request as AudioFocusRequest)
        }
    }
}
