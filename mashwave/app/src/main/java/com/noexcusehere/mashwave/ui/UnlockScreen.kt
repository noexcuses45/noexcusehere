package com.noexcusehere.mashwave.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.licence.LicenceCodes
import com.noexcusehere.mashwave.licence.LicenceConfig
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.BrandGradientHorizontal
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import java.util.Locale
import kotlinx.coroutines.launch

/** What the app shows until it has been unlocked with a code. */
@Composable
fun UnlockScreen() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val links = LocalUriHandler.current
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // If the app locked itself again (its code was switched off), it says so.
    var problem by remember { mutableStateOf(app.licence.notice.ifEmpty { null }) }

    fun unlock() {
        if (busy) return
        busy = true
        problem = null
        keyboard?.hide()
        scope.launch {
            problem = app.licence.unlock(typed)
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
        Spacer(Modifier.height(20.dp))
        Text("Unlock $APP_NAME", style = MaterialTheme.typography.headlineMedium, color = TextHi, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Type the code that came with your purchase. It is checked over the internet this once; after that the app opens by itself.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMid,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
        Spacer(Modifier.height(22.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = {
                typed = it.take(24)
                problem = null
            },
            singleLine = true,
            enabled = !busy,
            placeholder = { Text(LicenceCodes.EXAMPLE, color = TextLow, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            // Shown in capitals whatever was typed, the way codes are written down.
            visualTransformation = Capitals,
            textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 1.5.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { unlock() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Violet,
                unfocusedBorderColor = Outline,
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                disabledContainerColor = Surface2,
                focusedTextColor = TextHi,
                unfocusedTextColor = TextHi,
                cursorColor = Color.White,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(50))
                .background(BrandGradientHorizontal)
                .clickable(enabled = !busy) { unlock() },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
            } else {
                Text("Unlock", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
        problem?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Amber, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 440.dp))
        }
        if (LicenceConfig.BUY_URL.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                "No code yet? Get one at " + LicenceConfig.BUY_URL.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                style = MaterialTheme.typography.bodyLarge,
                color = Violet,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { runCatching { links.openUri(LicenceConfig.BUY_URL) } }.padding(6.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Only the code, the app's version and an anonymous fingerprint of this device are sent - nothing about you.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp),
        )
    }
}

/** Shows what was typed in capital letters without changing it (which would upset some keyboards). */
private val Capitals = VisualTransformation { text ->
    TransformedText(AnnotatedString(text.text.uppercase(Locale.ROOT)), OffsetMapping.Identity)
}

