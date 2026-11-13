package com.noexcusehere.mashwave.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.ui.components.PlayingBars
import com.noexcusehere.mashwave.ui.theme.BrandGradient
import com.noexcusehere.mashwave.ui.theme.Cyan
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet

/** First-run page that explains why the app needs to see the phone's music and videos. */
@Composable
fun WelcomeScreen(onAllow: () -> Unit, onSkip: () -> Unit, askedBefore: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Violet.copy(alpha = 0.45f), Ink, Ink)))
            .systemBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(108.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(BrandGradient),
            contentAlignment = Alignment.Center,
        ) {
            PlayingBars(playing = true, modifier = Modifier.size(52.dp), color = Color.White)
        }
        Spacer(Modifier.height(26.dp))
        Text("Mashwave", style = MaterialTheme.typography.displaySmall, color = TextHi)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your music, your videos, your mixes - all in one player.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMid,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(30.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature(Icons.Rounded.LibraryMusic, Pink, "Plays what's on your phone", "MP3, FLAC, AAC, OGG, WAV and more, with playlists and likes.")
            Feature(Icons.Rounded.VideoLibrary, Cyan, "Video too", "MP4, MKV, WebM, streams, subtitles and audio tracks.")
            Feature(Icons.Rounded.GraphicEq, Violet, "Mashup Studio", "Layer two songs, match their tempo and save the mix.")
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(50))
                .background(BrandGradient)
                .clickable(onClick = onAllow),
            contentAlignment = Alignment.Center,
        ) {
            Text("Allow access to music & videos", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        if (askedBefore) {
            // After a refusal Android may stop showing its prompt, so offer the Settings page too.
            Text(
                "Nothing happened? Open app settings",
                style = MaterialTheme.typography.labelLarge,
                color = Pink,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Not now - I'll just open files myself",
            style = MaterialTheme.typography.labelLarge,
            color = TextMid,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onSkip)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            "Mashwave only reads your media. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMid,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Feature(icon: ImageVector, tint: Color, title: String, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface2.copy(alpha = 0.7f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextHi)
            Text(text, style = MaterialTheme.typography.bodySmall, color = TextMid)
        }
    }
}
