package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.AppFolders
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.licence.LicenceCodes
import com.noexcusehere.mashwave.licence.LicenceConfig
import kotlinx.coroutines.launch

/** What the app shows until it has been unlocked with a code. */
@Composable
fun UnlockScreen(desk: Desk) {
    val scope = rememberCoroutineScope()
    val links = LocalUriHandler.current
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // If the app locked itself again (its code was switched off), it says so.
    var problem by remember { mutableStateOf(desk.licence.notice.ifEmpty { null }) }
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { box.requestFocus() } }

    fun unlock() {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            problem = desk.licence.unlock(typed)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppLogo?.let { Image(it, contentDescription = null, modifier = Modifier.size(110.dp).clip(RoundedCornerShape(24.dp))) }
        Spacer(Modifier.height(18.dp))
        Text("Unlock ${AppFolders.NAME}", style = MaterialTheme.typography.displaySmall, color = TextHi, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Type the code that came with your purchase. It is checked over the internet this once; after that the app opens by itself.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMid,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(22.dp))
        Field(
            typed,
            {
                typed = it.take(24)
                problem = null
            },
            "Your code  (like ${LicenceCodes.EXAMPLE})",
            Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            icon = Icons.Rounded.LockOpen,
            focus = box,
            onEnter = { unlock() },
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Unlock", { unlock() }, icon = Icons.Rounded.LockOpen, enabled = !busy)
            if (busy) {
                Spacer(Modifier.width(14.dp))
                CircularProgressIndicator(color = Violet, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Checking the code...", style = MaterialTheme.typography.bodyMedium, color = TextHi)
            }
        }
        problem?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Amber, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
        }
        if (LicenceConfig.BUY_URL.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Quiet(
                "No code yet? Get one at " + LicenceConfig.BUY_URL.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                { runCatching { links.openUri(LicenceConfig.BUY_URL) } },
                icon = Icons.Rounded.OpenInNew,
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Capital or small letters, with or without the dashes - either is fine.\n" +
                "Only the code, the app's version and an anonymous fingerprint of this computer are sent - nothing about you.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
    }
}
