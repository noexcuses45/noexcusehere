package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.FxSettings
import com.noexcusehere.mashwave.playback.AudioFx
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.EmptyState
import com.noexcusehere.mashwave.ui.components.MwSlider
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.components.VerticalSlider
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlin.math.roundToInt

@Composable
fun EqualizerScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val info by AudioFx.info.collectAsStateWithLifecycle()
    val data by app.store.data.collectAsStateWithLifecycle()
    val fx = data.fx

    fun update(new: FxSettings) {
        app.store.setFx(new)
        AudioFx.apply(new)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader("Sound", onBack = { nav.popBackStack() }) {
            Switch(
                checked = fx.enabled,
                onCheckedChange = { update(fx.copy(enabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Pink,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Surface3,
                    uncheckedBorderColor = Surface3,
                    uncheckedThumbColor = TextMid,
                ),
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        val eq = info
        if (eq == null) {
            EmptyState(
                Icons.Rounded.Equalizer,
                "Equalizer not available",
                "This phone doesn't offer a built-in equalizer to apps, or the player hasn't started yet. Play a song and come back.",
            )
            return@Column
        }

        val levels = List(eq.centerHz.size) { fx.bandLevels.getOrElse(it) { 0 } }
        val span = (eq.maxLevel - eq.minLevel).coerceAtLeast(1)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                if (fx.enabled) "Shape the sound of everything you play." else "Switch on to shape the sound.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (eq.presets.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    item { Chip("Custom", selected = fx.preset < 0, onClick = { update(fx.copy(preset = -1)) }) }
                    itemsIndexed(eq.presets) { index, name ->
                        Chip(name, selected = fx.preset == index, onClick = {
                            val presetLevels = AudioFx.presetLevels(index) ?: levels
                            update(fx.copy(enabled = true, preset = index, bandLevels = presetLevels))
                        })
                    }
                }
            }

            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface1)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                eq.centerHz.forEachIndexed { band, hz ->
                    val level = levels[band]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            (if (level > 0) "+" else "") + "${(level / 100f).roundToInt()} dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (fx.enabled) TextHi else TextMid,
                        )
                        VerticalSlider(
                            value = (level - eq.minLevel).toFloat() / span,
                            onValueChange = { f ->
                                val newLevel = (eq.minLevel + f * span).roundToInt()
                                val snapped = if (kotlin.math.abs(newLevel) < span / 40) 0 else newLevel
                                update(
                                    fx.copy(
                                        enabled = true,
                                        preset = -1,
                                        bandLevels = levels.toMutableList().also { it[band] = snapped },
                                    ),
                                )
                            },
                            modifier = Modifier
                                .width(44.dp)
                                .height(210.dp),
                        )
                        Text(
                            if (hz >= 1000) "${(hz / 100) / 10f}k".replace(".0k", "k") else "$hz",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMid,
                        )
                    }
                }
            }

            if (eq.bassSupported) {
                EffectRow("Bass boost", fx.bass) { update(fx.copy(enabled = true, bass = it)) }
            }
            if (eq.virtualizerSupported) {
                EffectRow("Surround (headphones)", fx.virtualizer) { update(fx.copy(enabled = true, virtualizer = it)) }
            }

            Row(Modifier.padding(16.dp)) {
                PillButton("Reset everything", {
                    update(FxSettings(enabled = fx.enabled, bandLevels = List(eq.centerHz.size) { 0 }))
                })
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EffectRow(label: String, strength: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleMedium, color = TextHi, modifier = Modifier.weight(1f))
            Text("${strength / 10}%", style = MaterialTheme.typography.labelLarge, color = if (strength > 0) Violet else TextMid)
        }
        MwSlider(value = strength / 1000f, onValueChange = { onChange((it * 1000).roundToInt()) })
    }
}
