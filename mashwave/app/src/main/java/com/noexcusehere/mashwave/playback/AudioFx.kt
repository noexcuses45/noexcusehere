package com.noexcusehere.mashwave.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import com.noexcusehere.mashwave.data.FxSettings
import kotlinx.coroutines.flow.MutableStateFlow

/** Equalizer, bass boost and surround attached to the main player's audio output. */
object AudioFx {
    data class Info(
        val minLevel: Int,
        val maxLevel: Int,
        val centerHz: List<Int>,
        val presets: List<String>,
        val bassSupported: Boolean,
        val virtualizerSupported: Boolean,
    )

    val info = MutableStateFlow<Info?>(null)

    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    fun attach(sessionId: Int, settings: FxSettings) {
        release()
        if (sessionId <= 0) return
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        info.value = runCatching {
            val eq = equalizer ?: return@runCatching null
            val range = eq.bandLevelRange
            Info(
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                centerHz = (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) },
                bassSupported = bass?.strengthSupported == true,
                virtualizerSupported = virtualizer?.strengthSupported == true,
            )
        }.getOrNull()
        apply(settings)
    }

    fun apply(settings: FxSettings) {
        runCatching {
            equalizer?.let { eq ->
                eq.enabled = settings.enabled
                if (settings.enabled) {
                    settings.bandLevels.forEachIndexed { band, level ->
                        if (band < eq.numberOfBands) eq.setBandLevel(band.toShort(), level.toShort())
                    }
                }
            }
        }
        runCatching {
            bass?.let { fx ->
                val on = settings.enabled && settings.bass > 0
                if (on) fx.setStrength(settings.bass.coerceIn(0, 1000).toShort())
                fx.enabled = on
            }
        }
        runCatching {
            virtualizer?.let { fx ->
                val on = settings.enabled && settings.virtualizer > 0
                if (on) fx.setStrength(settings.virtualizer.coerceIn(0, 1000).toShort())
                fx.enabled = on
            }
        }
    }

    /** The band levels a built-in preset would set, so the sliders can show its shape. */
    fun presetLevels(preset: Int): List<Int>? = runCatching {
        val eq = equalizer ?: return null
        if (preset !in 0 until eq.numberOfPresets) return null
        eq.usePreset(preset.toShort())
        (0 until eq.numberOfBands).map { eq.getBandLevel(it.toShort()).toInt() }
    }.getOrNull()

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bass?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bass = null
        virtualizer = null
    }
}
