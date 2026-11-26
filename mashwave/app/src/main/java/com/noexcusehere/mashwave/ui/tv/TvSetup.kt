package com.noexcusehere.mashwave.ui.tv

import com.noexcusehere.mashwave.BuildConfig
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.cast.MediaServer
import com.noexcusehere.mashwave.iptv.LiveCushion
import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvClient
import com.noexcusehere.mashwave.iptv.TvException
import com.noexcusehere.mashwave.iptv.TvPairing
import com.noexcusehere.mashwave.iptv.TvRepository
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Signing in on a TV. The easy way is from a phone: the TV shows an address and a code, the
 * viewer opens the address on their phone and pastes their provider's message. The patient can
 * type it in with the remote instead.
 */
@Composable
fun TvSignIn(ui: TvUi) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    BackHandler { ui.setupOpen = false }

    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    val code = remember { TvPairing.newCode() }
    var address by remember { mutableStateOf<String?>(null) }
    var noWifi by remember { mutableStateOf(false) }

    /** Tries a sign-in. Returns null when it worked, or the reason it did not. */
    suspend fun attempt(account: TvAccount, spares: List<String>): String? {
        busy = true
        problem = null
        return try {
            // Signing in again to a service that is already here renews it rather than listing it twice.
            app.tv.add(account, replaces = TvRepository.twinOf(app.tv.state.value.accounts, account)?.id, spares = spares)
            ui.setupOpen = false
            ui.page = TvPage.LIVE
            null
        } catch (e: TvException) {
            (TvClient.withoutPhoneTip(e.message) ?: "That did not work.").also { problem = it }
        } finally {
            busy = false
        }
    }

    // The little web page for the phone exists only while this screen is open.
    DisposableEffect(Unit) {
        val pairing = runCatching {
            TvPairing(code) { account, spares -> runBlocking { withContext(Dispatchers.Main) { attempt(account, spares) } } }
        }.getOrNull()
        scope.launch {
            val host = withContext(Dispatchers.IO) { MediaServer.localAddress()?.hostAddress }
            if (host == null || pairing == null) noWifi = true else address = "$host:${pairing.port}"
        }
        onDispose { pairing?.close() }
    }

    val first = remember { FocusRequester() }
    LandOn(first)

    Row(Modifier.fillMaxSize().background(Ink).padding(horizontal = 40.dp, vertical = 26.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(18.dp)).padding(24.dp)) {
            Text("Easiest: from your phone", style = MaterialTheme.typography.headlineSmall, color = TextHi)
            Spacer(Modifier.height(10.dp))
            if (noWifi) {
                Text("This TV is not on a home network, so a phone cannot reach it. Type the details in on the right instead.", style = MaterialTheme.typography.bodyLarge, color = Amber)
            } else {
                Text("1.  Put your phone on the same Wi-Fi as this TV.", style = MaterialTheme.typography.bodyLarge, color = TextMid)
                Spacer(Modifier.height(8.dp))
                Text("2.  Open its web browser and go to:", style = MaterialTheme.typography.bodyLarge, color = TextMid)
                Text(address ?: "...", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp), color = TextHi, modifier = Modifier.padding(start = 26.dp, top = 4.dp, bottom = 8.dp))
                Text("3.  Type this code there:", style = MaterialTheme.typography.bodyLarge, color = TextMid)
                Text(code, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, letterSpacing = 8.sp), color = Pink, modifier = Modifier.padding(start = 26.dp, top = 2.dp, bottom = 8.dp))
                Text("4.  Paste the message your TV provider sent you, and send it.", style = MaterialTheme.typography.bodyLarge, color = TextMid)
            }
            Spacer(Modifier.weight(1f))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Violet, strokeWidth = 3.dp, modifier = Modifier.width(24.dp).height(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Checking with the service...", style = MaterialTheme.typography.titleMedium, color = TextHi)
                }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Amber, maxLines = 5, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
            Text("Or type it here with the remote", style = MaterialTheme.typography.headlineSmall, color = TextHi)
            Spacer(Modifier.height(12.dp))
            TvTextBox(server, { server = it }, "Server address  (http://example.com:8080)", Modifier.fillMaxWidth(), web = true, requester = first)
            Spacer(Modifier.height(8.dp))
            TvTextBox(username, { username = it }, "User name", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            TvTextBox(password, { password = it }, "Password", Modifier.fillMaxWidth(), secret = true)
            Spacer(Modifier.height(12.dp))
            Text("or a playlist link instead", style = MaterialTheme.typography.bodyMedium, color = TextLow)
            Spacer(Modifier.height(6.dp))
            TvTextBox(link, { link = it }, "Playlist link  (http://... .m3u)", Modifier.fillMaxWidth(), web = true)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Sign in", {
                    if (!busy) {
                        val login = server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                        when {
                            login -> scope.launch { attempt(TvAccount(id = "", name = "", type = TvAccount.XTREAM, server = server, username = username, password = password), emptyList()) }
                            link.isNotBlank() -> scope.launch { attempt(TvAccount(id = "", name = "", type = TvAccount.PLAYLIST, playlistUrl = link.trim()), emptyList()) }
                            else -> problem = "Fill in the server address, user name and password - or a playlist link."
                        }
                    }
                }, primary = true)
                TvButton("Back", { ui.setupOpen = false })
            }
        }
    }
}

private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

@Composable
fun TvSettings(ui: TvUi) {
    val context = LocalContext.current
    val app = context.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<String?>(null) }
    // Coming in from the side menu the remote lands on "Add a TV service", not on whichever
    // button happens to be level with the menu (which could be a "Remove").
    val add = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize().focusRestorer(add), contentPadding = PaddingValues(start = 24.dp, end = 28.dp, top = 18.dp, bottom = 28.dp)) {
        // Each part has a name, so that when a service is removed the parts below it stay what
        // they were - and the remote stays on the button it was moved to.
        item(key = "title") { Text("Settings", style = MaterialTheme.typography.headlineSmall, color = TextHi) }

        item(key = "services") { Text("TV services", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }
        if (state.accounts.isEmpty()) item(key = "none") { Text("No service yet.", style = MaterialTheme.typography.bodyLarge, color = TextLow) }
        items(state.accounts, key = { "service-" + it.id }) { account ->
            val active = account.id == state.active?.id
            Row(
                Modifier
                    .padding(vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface1)
                    .border(1.dp, if (active) Violet else Outline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(account.name + if (active) "   (in use)" else "", style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(
                        if (account.isXtream) "Login: ${account.username}" else "Playlist link",
                        account.status.takeIf { it.isNotBlank() },
                        account.expiresAt.takeIf { it > 0 }?.let { "until " + dayFormat.format(Date(it)) },
                        account.maxConnections.takeIf { it > 0 }?.let { if (it == 1) "1 screen at a time" else "$it screens at a time" },
                    )
                    Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = TextMid)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // "Use" and a removed service's buttons go away once pressed, so the remote
                    // is moved to a button that stays before they do.
                    if (!active) {
                        TvButton("Use", {
                            runCatching { add.requestFocus() }
                            app.tv.select(account.id)
                        })
                    }
                    TvButton(if (removing == account.id) "Press again to remove" else "Remove", {
                        if (removing == account.id) {
                            runCatching { add.requestFocus() }
                            app.tv.remove(account.id)
                            removing = null
                        } else {
                            removing = account.id
                        }
                    })
                }
            }
        }
        item(key = "add") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                TvButton("Add a TV service", { ui.setupOpen = true }, icon = Icons.Rounded.Add, primary = true, requester = add)
                if (state.active != null) {
                    TvButton("Fetch the lists again", {
                        app.tv.refresh()
                        ui.message = "Fetching the newest lists..."
                    }, icon = Icons.Rounded.Refresh)
                }
            }
        }

        item(key = "live") {
            Text("Live TV", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 18.dp))
            Text("How much of a channel is collected before it shows. More means fewer stops on a shaky connection.", style = MaterialTheme.typography.bodyMedium, color = TextMid, modifier = Modifier.padding(bottom = 6.dp))
        }
        items(LiveCushion.entries.toList(), key = { "cushion-" + it.name }) { cushion ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .remote({ app.tv.setCushion(cushion) }, chosen = state.cushion == cushion)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(cushion.label + if (state.cushion == cushion) "   ✓" else "", style = MaterialTheme.typography.titleMedium, color = TextHi)
                Text(cushion.note, style = MaterialTheme.typography.bodyMedium, color = TextMid)
            }
        }

        // Only in the phone app, when it finds itself on a TV device: the TV app has no other layout.
        if (!BuildConfig.TV_APP) item(key = "screen") {
            Text("This screen", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 18.dp))
            Text("This is the layout for a TV remote. On a phone or tablet the touch layout is the one to use.", style = MaterialTheme.typography.bodyMedium, color = TextMid, modifier = Modifier.padding(bottom = 8.dp))
            TvButton("Switch to the phone layout", {
                TvMode.choose(context, TvMode.PHONE)
                (context as? Activity)?.recreate()
            })
        }
        item(key = "about") {
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
            Text("$APP_NAME $version", style = MaterialTheme.typography.bodyMedium, color = TextLow, modifier = Modifier.padding(top = 18.dp))
            Text("Sign-ins, favourites and lists are kept on this device only.", style = MaterialTheme.typography.bodySmall, color = TextLow)
            val code = app.licence.hint
            if (code.isNotEmpty()) Text("Unlocked with the code ending in $code.", style = MaterialTheme.typography.bodySmall, color = TextLow)
        }
    }
}
