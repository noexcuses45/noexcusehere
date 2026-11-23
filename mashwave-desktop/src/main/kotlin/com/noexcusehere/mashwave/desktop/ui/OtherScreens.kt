package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.desktop.AppFolders
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.desktop.SignIn
import com.noexcusehere.mashwave.iptv.LiveCushion
import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvClient
import com.noexcusehere.mashwave.iptv.TvException
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvSearch
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.iptv.Xtream
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First visit: nothing is signed in yet. */
@Composable
fun Welcome(desk: Desk) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AppLogo?.let { Image(it, contentDescription = null, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(26.dp))) }
        Spacer(Modifier.height(18.dp))
        Text("Welcome to ${AppFolders.NAME}", style = MaterialTheme.typography.displaySmall, color = TextHi, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with the details from your TV service and your channels, movies and shows appear here. " +
                "The app has no channels of its own.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMid,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(22.dp))
        Pill("Add your TV service", { desk.signIn = SignIn(null) }, icon = Icons.Rounded.Add)
    }
}

/** One box that looks through channels, movies and shows at once. */
@Composable
fun SearchScreen(desk: Desk) {
    val state by desk.tv.state.collectAsState()
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Map<TvSection, List<TvItem>>>(emptyMap()) }
    val catalogs = TvSection.entries.associateWith { state.shelf(it).catalog }

    LaunchedEffect(state.active?.id) {
        // Looking through everything needs every list on hand.
        TvSection.entries.forEach { desk.tv.ensure(it) }
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(query, catalogs.values.map { it?.loadedAt }) {
        if (query.isBlank()) {
            found = emptyMap()
            return@LaunchedEffect
        }
        delay(200)
        found = withContext(Dispatchers.Default) {
            TvSection.entries.associateWith { section -> catalogs[section]?.let { TvSearch.find(it.items, query, limit = 60) }.orEmpty() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.padding(start = 24.dp, top = 18.dp))
        Field(
            query,
            { query = it },
            "Channel, movie or show",
            Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
            icon = Icons.Rounded.Search,
            focus = focus,
        )
        val loading = TvSection.entries.any { state.shelf(it).loading && state.shelf(it).catalog == null }
        when {
            query.isBlank() -> Note(Icons.Rounded.Search, "Find anything", "Type part of a name. Every word you type has to be in it, in any order.")
            found.values.all { it.isEmpty() } && loading -> Busy("Loading the lists...")
            found.values.all { it.isEmpty() } -> Note(Icons.Rounded.Search, "Nothing found", "Nothing has \"$query\" in its name.")
            else -> {
                val list = rememberLazyListState()
                Box(Modifier.weight(1f)) {
                    LazyColumn(state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
                        val channels = found[TvSection.LIVE].orEmpty()
                        if (channels.isNotEmpty()) {
                            item { Heading("Channels", channels.size) }
                            itemsIndexed(channels.take(12)) { index, item ->
                                SmallChannel(item, state.isFavourite(item), { desk.playChannel(channels, index, fill = true) }, { desk.tv.toggleFavourite(item) })
                            }
                        }
                        for (section in listOf(TvSection.MOVIES, TvSection.SHOWS)) {
                            val items = found[section].orEmpty()
                            if (items.isEmpty()) continue
                            item { Heading(section.title, items.size) }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    itemsIndexed(items) { _, item -> Poster(item, Modifier.width(150.dp)) { desk.open(item) } }
                                }
                            }
                        }
                    }
                    ListBar(list)
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String, count: Int? = null) {
    Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = TextHi)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = TextLow, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun SmallChannel(item: TvItem, favourite: Boolean, onClick: () -> Unit, onFavourite: (() -> Unit)?) {
    Row(
        Modifier
            .widthIn(max = 620.dp)
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Picture(item.logo, Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(Surface2), side = 120, scale = ContentScale.Fit) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = TextLow, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(item.name, style = MaterialTheme.typography.bodyLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onFavourite != null) {
            RoundIcon(
                if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (favourite) "Remove from favourites" else "Add to favourites",
                onFavourite,
                size = 32.dp,
                tint = if (favourite) Pink else TextLow,
            )
        }
    }
}

/** Everything with a heart, and what was watched lately. */
@Composable
fun SavedScreen(desk: Desk) {
    val state by desk.tv.state.collectAsState()
    if (state.favourites.isEmpty() && state.recents.isEmpty()) {
        Note(Icons.Rounded.Favorite, "Nothing here yet", "Channels, movies and shows you give a heart to are kept here, along with what you watched lately.")
        return
    }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 28.dp)) {
            item { Text("Favourites", style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.padding(top = 12.dp)) }
            val channels = state.favourites.filter { it.section == TvSection.LIVE }
            if (channels.isNotEmpty()) {
                item { Heading("Channels", channels.size) }
                itemsIndexed(channels) { index, item -> SmallChannel(item, true, { desk.playChannel(channels, index, fill = true) }, { desk.tv.toggleFavourite(item) }) }
            }
            for (section in listOf(TvSection.MOVIES, TvSection.SHOWS)) {
                val items = state.favourites.filter { it.section == section }
                if (items.isEmpty()) continue
                item { Heading(section.title, items.size) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(items) { _, item -> Poster(item, Modifier.width(150.dp)) { desk.open(item) } }
                    }
                }
            }
            if (state.favourites.isEmpty()) {
                item { Text("Nothing has a heart yet.", style = MaterialTheme.typography.bodyMedium, color = TextLow, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (state.recents.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Heading("Recently watched") }
                        Quiet("Clear", { desk.tv.clearRecents() }, Modifier.padding(top = 10.dp))
                    }
                }
                val recentChannels = state.recents.filter { it.section == TvSection.LIVE }
                itemsIndexed(recentChannels.take(8)) { index, item -> SmallChannel(item, state.isFavourite(item), { desk.playChannel(recentChannels, index, fill = true) }, { desk.tv.toggleFavourite(item) }) }
                val others = state.recents.filter { it.section != TvSection.LIVE }
                if (others.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(others) { _, item -> Poster(item, Modifier.width(150.dp)) { desk.open(item) } }
                        }
                    }
                }
            }
        }
        ListBar(list)
    }
}

private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

@Composable
fun SettingsScreen(desk: Desk) {
    val state by desk.tv.state.collectAsState()
    var removing by remember { mutableStateOf<TvAccount?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 28.dp).widthIn(max = 760.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = TextHi)

            Heading("TV services")
            if (state.accounts.isEmpty()) Text("No service yet.", style = MaterialTheme.typography.bodyMedium, color = TextLow)
            for (account in state.accounts) {
                val active = account.id == state.active?.id
                Column(
                    Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface1)
                        .border(1.dp, if (active) Violet else Outline, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(account.name, style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (active) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = "In use", tint = Violet, modifier = Modifier.size(18.dp))
                                }
                            }
                            val facts = listOfNotNull(
                                if (account.isXtream) "Login: ${account.username}" else "Playlist link",
                                account.status.takeIf { it.isNotBlank() },
                                account.expiresAt.takeIf { it > 0 }?.let { "until " + dayFormat.format(Date(it)) },
                                account.maxConnections.takeIf { it > 0 }?.let { if (it == 1) "1 screen at a time" else "$it screens at a time" },
                            )
                            Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = TextMid)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!active) Quiet("Use", { desk.tv.select(account.id) })
                            Quiet("Edit", { desk.signIn = SignIn(account) })
                            Quiet("Remove", { removing = account }, tint = Color(0xFFFF8A8A))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Add a TV service", { desk.signIn = SignIn(null) }, icon = Icons.Rounded.Add)
                if (state.active != null) {
                    Quiet("Fetch the lists again", {
                        desk.tv.refresh()
                        desk.say("Fetching the newest lists...")
                    }, icon = Icons.Rounded.Refresh)
                }
            }

            Heading("Music and videos on this computer")
            Text("These folders, and the folders inside them, are looked through for music and videos.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
            Spacer(Modifier.height(6.dp))
            if (desk.folders.isEmpty()) Text("No folder chosen yet.", style = MaterialTheme.typography.bodyMedium, color = TextLow)
            for (folder in desk.folders) {
                Row(
                    Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface1).border(1.dp, Outline, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = TextMid, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(folder, style = MaterialTheme.typography.bodyMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Quiet("Remove", { desk.removeFolder(folder) }, icon = Icons.Rounded.Close, tint = TextMid)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Quiet("Add a folder", { Choosers.folder(desk.window)?.let { desk.addFolder(it) } }, icon = Icons.Rounded.Add)
                Spacer(Modifier.width(6.dp))
                Quiet("Look again", { desk.lookForMediaAgain() }, icon = Icons.Rounded.Refresh, enabled = !desk.library.looking)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (desk.library.looking) "Looking... ${desk.library.foundSoFar} files so far"
                    else "${desk.library.songs.size} songs and ${desk.library.videos.size} videos found",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLow,
                )
            }

            Heading("Live TV")
            Text("How much of a channel is collected before it shows. More means fewer stops on a shaky connection.", style = MaterialTheme.typography.bodySmall, color = TextMid)
            Spacer(Modifier.height(8.dp))
            for (cushion in LiveCushion.entries) {
                val chosen = state.cushion == cushion
                Row(
                    Modifier
                        .padding(vertical = 3.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (chosen) Violet.copy(alpha = 0.2f) else Surface1)
                        .clickable { desk.tv.setCushion(cushion) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(cushion.label, style = MaterialTheme.typography.titleMedium, color = TextHi)
                        Text(cushion.note, style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                    if (chosen) Icon(Icons.Rounded.CheckCircle, contentDescription = "Chosen", tint = Violet, modifier = Modifier.size(20.dp))
                }
            }

            Heading("Player")
            val player = desk.player
            when {
                player != null && player.builtIn -> Text("Built in (VLC engine ${player.version}).", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                player != null -> Text("Using the VLC ${player.version} installed in ${desk.vlcFolder}", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                desk.lookingForVlc -> Text("Starting the player...", style = MaterialTheme.typography.bodyMedium, color = TextMid)
                else -> VlcNeeded(desk)
            }

            Heading("About")
            Text("${AppFolders.NAME} for computers, version ${System.getProperty("jpackage.app-version") ?: "1.0"}", style = MaterialTheme.typography.bodyMedium, color = TextMid)
            Text("Sign-ins, favourites and lists are kept on this computer only.", style = MaterialTheme.typography.bodySmall, color = TextLow)
            if (desk.licence.hint.isNotEmpty()) {
                Text("Unlocked with the code ending in ${desk.licence.hint}.", style = MaterialTheme.typography.bodySmall, color = TextLow)
            }
            Spacer(Modifier.height(8.dp))
            Quiet("Open the folder they are kept in", { runCatching { Desktop.getDesktop().open(desk.dataDir) } }, icon = Icons.Rounded.FolderOpen)
            val notices = remember(player) { if (player?.builtIn == true) File(desk.vlcFolder, "NOTICE.txt").takeIf { it.isFile } else null }
            if (notices != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "Video and sound are played by the VLC engine (libVLC) from VideoLAN, which is free software used under the LGPL licence. " +
                        "It sits in its own folder inside the app and can be replaced with another copy of the same engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLow,
                )
                Spacer(Modifier.height(8.dp))
                Quiet("Read its licence and notices", { runCatching { Desktop.getDesktop().open(notices.parentFile) } }, icon = Icons.Rounded.FolderOpen)
            }
        }
    }
    removing?.let { account ->
        Sheet("Remove \"${account.name}\"?", { removing = null }, width = 440.dp) {
            Text("Its sign-in, lists and favourites are removed from this computer. Your subscription itself is not affected.", style = MaterialTheme.typography.bodyMedium, color = TextMid)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.End)) {
                Quiet("Keep it", { removing = null })
                Pill("Remove", {
                    desk.tv.remove(account.id)
                    removing = null
                })
            }
        }
    }
}

/** Shown wherever playing is not possible because there is no video player to play with. */
@Composable
fun VlcNeeded(desk: Desk, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Amber.copy(alpha = 0.12f)).border(1.dp, Amber.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text("The video player is missing", style = MaterialTheme.typography.titleLarge, color = TextHi)
        Spacer(Modifier.height(4.dp))
        Text(
            desk.vlcProblem.ifBlank { "The video player was not found on this computer." } +
                " Installing the app again puts it back. Or install VLC (a free player) and click \"Look again\": the app can use that instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Get VLC (free)", { runCatching { Desktop.getDesktop().browse(URI("https://www.videolan.org/vlc/")) }.onFailure { desk.say("Open videolan.org in your browser to get VLC.") } })
            Quiet("Look again", { desk.findVlc() }, icon = Icons.Rounded.Refresh)
        }
    }
}

private fun clipboardText(): String = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull().orEmpty()

/** Adding a TV service, or changing one: paste the provider's message, type a login, or give a playlist link. */
@Composable
fun SignInSheet(desk: Desk, signIn: SignIn) {
    val existing = signIn.editing
    val scope = rememberCoroutineScope()
    var playlistMode by remember { mutableStateOf(existing != null && !existing.isXtream) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var server by remember { mutableStateOf(existing?.server.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var link by remember { mutableStateOf(existing?.playlistUrl.orEmpty()) }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Other addresses found in a pasted message; tried if the first one cannot be reached.
    var spares by remember { mutableStateOf(emptyList<String>()) }
    val ready = if (playlistMode) link.isNotBlank() else server.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    fun paste() {
        val text = clipboardText()
        val login = Xtream.loginFromText(text)
        when {
            login != null -> {
                playlistMode = false
                server = login.server
                username = login.username
                password = login.password
                spares = Xtream.serversInText(text).filter { it != login.server }
                problem = null
            }
            text.trim().startsWith("http", ignoreCase = true) && !text.trim().contains(' ') -> {
                if (playlistMode) link = text.trim() else server = text.trim()
                problem = null
            }
            else -> problem = "Nothing copied looks like TV sign-in details. Copy the message from your provider first, then click Paste."
        }
    }

    fun submit() {
        if (!ready || busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                val draft = if (playlistMode) {
                    TvAccount(id = "", name = name.trim(), type = TvAccount.PLAYLIST, playlistUrl = link.trim(), userAgent = existing?.userAgent.orEmpty())
                } else {
                    TvAccount(id = "", name = name.trim(), type = TvAccount.XTREAM, server = server, username = username, password = password, userAgent = existing?.userAgent.orEmpty())
                }
                desk.tv.add(draft, replaces = existing?.id, spares = if (playlistMode) emptyList() else spares)
                desk.signIn = null
                desk.tab = com.noexcusehere.mashwave.desktop.Tab.LIVE
            } catch (e: TvException) {
                problem = TvClient.withoutPhoneTip(e.message)
            } finally {
                busy = false
            }
        }
    }

    Sheet(if (existing == null) "Add your TV service" else "Edit TV service", { if (!busy) desk.signIn = null }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Login", !playlistMode, { playlistMode = false })
                Chip("Playlist link", playlistMode, { playlistMode = true })
                Spacer(Modifier.weight(1f))
                Quiet("Paste", { paste() }, icon = Icons.Rounded.ContentPaste)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Copy the message your provider sent (with the address, user name and password), then click Paste - the boxes fill themselves in.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
            )
            Spacer(Modifier.height(12.dp))
            if (playlistMode) {
                Field(link, { link = it }, "Playlist link (http://... .m3u)", Modifier.fillMaxWidth(), onEnter = { submit() })
            } else {
                Field(server, { server = it }, "Server address (http://example.com:8080)", Modifier.fillMaxWidth(), onEnter = { submit() })
                Spacer(Modifier.height(8.dp))
                Field(username, { username = it }, "User name", Modifier.fillMaxWidth(), onEnter = { submit() })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(password, { password = it }, "Password", Modifier.weight(1f), secret = !showPassword, onEnter = { submit() })
                    Spacer(Modifier.width(8.dp))
                    Quiet(if (showPassword) "Hide" else "Show", { showPassword = !showPassword })
                }
            }
            Spacer(Modifier.height(8.dp))
            Field(name, { name = it }, "A name for it (optional)", Modifier.fillMaxWidth(), onEnter = { submit() })
            if (!playlistMode && spares.isNotEmpty() && problem == null && !busy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The message has " + (if (spares.size == 1) "a second address" else "${spares.size} more addresses") + ". It is tried automatically if the first can't be reached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLow,
                )
            }
            problem?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Amber)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) {
                    CircularProgressIndicator(color = Violet, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Text("Checking with the service...", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
                Spacer(Modifier.weight(1f))
                Quiet("Cancel", { desk.signIn = null }, enabled = !busy)
                Pill(if (existing == null) "Sign in" else "Save", { submit() }, enabled = ready && !busy)
            }
        }
    }
}
