package com.noexcusehere.mashwave.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.licence.LicenceCodes
import com.noexcusehere.mashwave.licence.LicenceConfig
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlinx.coroutines.launch

/** What the TV layout shows until the app has been unlocked with a code. */
@Composable
fun TvUnlock() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // If the app locked itself again (its code was switched off), it says so.
    var problem by remember { mutableStateOf(app.licence.notice.ifEmpty { null }) }
    val box = remember { FocusRequester() }
    LandOn(box)

    fun unlock() {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            problem = app.licence.unlock(typed)
            busy = false
        }
    }

    Row(Modifier.fillMaxSize().background(Ink).padding(horizontal = 56.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
            Spacer(Modifier.height(18.dp))
            Text("Unlock $APP_NAME", style = MaterialTheme.typography.headlineLarge, color = TextHi)
            Spacer(Modifier.height(8.dp))
            Text(
                "Type the code that came with your purchase. It is checked over the internet this once; after that the app opens by itself.",
                style = MaterialTheme.typography.bodyLarge,
                color = TextMid,
            )
            if (LicenceConfig.BUY_URL.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("No code yet? Get one at", style = MaterialTheme.typography.bodyLarge, color = TextMid)
                Text(LicenceConfig.BUY_URL.removePrefix("https://").removePrefix("http://").trimEnd('/'), style = MaterialTheme.typography.titleLarge, color = Violet)
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Text("Your code", style = MaterialTheme.typography.titleLarge, color = TextHi)
            Spacer(Modifier.height(10.dp))
            TvTextBox(
                typed,
                {
                    typed = it.take(24)
                    problem = null
                },
                "Press OK to type your code",
                Modifier.fillMaxWidth(),
                requester = box,
                onDone = { unlock() },
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvButton("Unlock", { unlock() }, primary = true)
                if (busy) {
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(color = Violet, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Checking the code...", style = MaterialTheme.typography.titleMedium, color = TextHi)
                }
            }
            problem?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Amber)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "A code looks like ${LicenceCodes.EXAMPLE}. Capital or small letters, with or without the dashes - either is fine.\nOnly the code, the app's version and an anonymous fingerprint of this device are sent - nothing about you.",
                style = MaterialTheme.typography.bodySmall,
                color = TextLow,
            )
        }
    }
}
