package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.noexcusehere.mashwave.MashwaveApp
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.iptv.TvAccount
import com.noexcusehere.mashwave.iptv.TvCatalog
import com.noexcusehere.mashwave.iptv.TvException
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvRegions
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.iptv.TvState
import com.noexcusehere.mashwave.iptv.Xtream
import com.noexcusehere.mashwave.ui.CastButton
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.MwSheet
import com.noexcusehere.mashwave.ui.Routes
import com.noexcusehere.mashwave.ui.UiState
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.ConfirmDialog
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.RemoteImages
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.components.SheetAction
import com.noexcusehere.mashwave.ui.components.WebPicture
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import com.noexcusehere.mashwave.ui.theme.Violet
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Pretend category ids for the lists that are not the service's own categories. */
const val TV_ALL = "*all"
const val TV_FAVOURITES = "*fav"
const val TV_RECENT = "*recent"

private const val REGION_OTHER = "*other"

/**
 * What tapping a channel, movie or show does: channels play at once (and remember the list they
 * came from, for channel up / down), movies and shows from a login open their page, and anything
 * from a playlist simply plays.
 */
fun openTvItem(app: MashwaveApp, ui: UiState, nav: NavHostController, list: List<TvItem>, index: Int) {
    val item = list.getOrNull(index) ?: return
    if (item.opensDetails) {
        app.tv.opened = item
        nav.navigate(Routes.TV_DETAIL)
        return
    }
    val entry = app.tv.tune(list, index) ?: return
    ui.play(app.player, listOf(entry), 0)
}

/** The TV tab: live channels, movies and shows from the user's own TV service. */
@Composable
fun TvScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val state by app.tv.state.collectAsStateWithLifecycle()
    // "add", "services" or "edit:<id>"
    var sheet by remember { mutableStateOf("") }
    val account = state.active

    LaunchedEffect(account?.id, state.section) {
        if (account != null) app.tv.ensure(state.section)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        ScreenHeader("TV") {
            if (account != null) {
                CastButton()
                HeaderIcon(Icons.Rounded.Search, "Search channels, movies and shows", { nav.navigate(Routes.TV_SEARCH) })
                HeaderIcon(Icons.Rounded.Settings, "Your TV services", { sheet = "services" })
            }
        }
        if (account == null) {
            TvWelcome(onAdd = { sheet = "add" })
        } else {
            TvHome(state, account, onServices = { sheet = "services" })
        }
    }

    when {
        sheet == "add" -> TvAccountDialog(null) { sheet = "" }
        sheet.startsWith("edit:") -> TvAccountDialog(state.accounts.firstOrNull { it.id == sheet.removePrefix("edit:") }) { sheet = "" }
        sheet == "services" -> TvServicesSheet(state, onAdd = { sheet = "add" }, onEdit = { sheet = "edit:$it" }, onDismiss = { sheet = "" })
    }
}

@Composable
private fun TvWelcome(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.4f), Pink.copy(alpha = 0.3f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = TextHi, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Watch your TV service here", style = MaterialTheme.typography.headlineSmall, color = TextHi, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with the details your TV provider gave you - the DNS (URL), user name and password, or a playlist link - " +
                "and its live channels, movies and shows appear here, sorted by country, ready to watch on the phone or send to a TV.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        PillButton("Add your TV service", onAdd, icon = Icons.Rounded.Add, filled = true)
        Spacer(Modifier.height(22.dp))
        Text(
            "The app has no channels of its own: it plays what your service provides, the same way TiviMate or IPTV Smarters do. " +
                "Your login stays on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLow,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TvHome(state: TvState, account: TvAccount, onServices: () -> Unit) {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val section = state.section
    val shelf = state.shelf(section)
    val catalog = shelf.catalog
    var region by rememberSaveable(account.id, section) { mutableStateOf<String?>(null) }

    val counts = remember(catalog) { catalog?.byCategory?.mapValues { it.value.size }.orEmpty() }
    val regionOf = remember(catalog) { catalog?.categories?.associate { it.id to TvRegions.of(it.name)?.key }.orEmpty() }
    val regions = remember(catalog) { catalog?.let { TvRegions.buttons(it.categories, counts, Locale.getDefault().country) }.orEmpty() }
    val hasOther = remember(catalog) { catalog?.categories?.any { (counts[it.id] ?: 0) > 0 && regionOf[it.id] == null } == true }
    val categories = remember(catalog, region) {
        catalog?.categories.orEmpty().filter { category ->
            (counts[category.id] ?: 0) > 0 && when (region) {
                null -> true
                REGION_OTHER -> regionOf[category.id] == null
                else -> regionOf[category.id] == region
            }
        }
    }
    val favourites = remember(state.favourites, section) { state.favourites.filter { it.section == section } }
    val recents = remember(state.recents, section) { state.recents.filter { it.section == section } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "service") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onServices)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val note = subscriptionNote(account)
                    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1)
                }
                if (shelf.loading) {
                    CircularProgressIndicator(color = Pink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (catalog == null) "Getting the list" else "Updating", style = MaterialTheme.typography.bodySmall, color = TextMid)
                }
            }
        }
        item(key = "sections") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvSection.entries.forEach { s -> Chip(s.title, s == section, { app.tv.setSection(s) }) }
            }
        }
        if (shelf.error != null) {
            item(key = "error") {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface2)
                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (catalog == null) "" else "Couldn't update: ") + shelf.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMid,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { app.tv.ensure(section, force = true) }) { Text("Try again", color = Pink) }
                }
            }
        }
        if (catalog == null) {
            if (shelf.loading) {
                item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Pink)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Getting the list of ${section.many} from your service.\nBig lists can take a minute the first time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMid,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            return@LazyColumn
        }
        if (catalog.items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "This service doesn't list any ${section.many}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            return@LazyColumn
        }

        if (favourites.isNotEmpty()) {
            item(key = "fav") {
                TvStrip("Favourites", Icons.Rounded.Star, favourites, onMore = { nav.navigate(Routes.tvList(section, TV_FAVOURITES)) }) { index ->
                    openTvItem(app, ui, nav, favourites, index)
                }
            }
        }
        if (recents.isNotEmpty()) {
            item(key = "recent") {
                TvStrip("Recently watched", Icons.Rounded.History, recents, onMore = { nav.navigate(Routes.tvList(section, TV_RECENT)) }) { index ->
                    openTvItem(app, ui, nav, recents, index)
                }
            }
        }

        if (regions.size >= 2) {
            item(key = "regions") {
                Column {
                    Text("Countries", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Chip("All", region == null, { region = null }) }
                        items(regions, key = { it.key }) { r -> Chip(r.label, region == r.key, { region = if (region == r.key) null else r.key }) }
                        if (hasOther) item { Chip("Other", region == REGION_OTHER, { region = if (region == REGION_OTHER) null else REGION_OTHER }) }
                    }
                }
            }
        }

        item(key = "title") {
            Text(
                if (region == null) "Categories" else "${categories.size} ${if (categories.size == 1) "category" else "categories"}",
                style = MaterialTheme.typography.titleLarge,
                color = TextHi,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp),
            )
        }
        if (region == null) {
            item(key = TV_ALL) {
                CategoryRow("All ${section.many}", catalog.items.size, highlighted = true) { nav.navigate(Routes.tvList(section, TV_ALL)) }
            }
        }
        items(categories, key = { "c" + it.id }) { category ->
            CategoryRow(category.name, counts[category.id] ?: 0, highlighted = false) { nav.navigate(Routes.tvList(section, category.id)) }
        }
    }
}

private fun subscriptionNote(account: TvAccount): String {
    if (!account.isXtream) return "Playlist"
    val parts = ArrayList<String>()
    if (account.expiresAt > 0) {
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(account.expiresAt))
        parts += if (account.expiresAt < System.currentTimeMillis()) "Ran out $date" else "Runs until $date"
    } else if (account.status.isNotBlank()) {
        parts += account.status
    }
    if (account.maxConnections > 0) parts += if (account.maxConnections == 1) "1 screen at a time" else "${account.maxConnections} screens at a time"
    return parts.joinToString(" · ")
}

@Composable
private fun CategoryRow(name: String, count: Int, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) Surface2 else Surface1)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = TextLow)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = TextLow, modifier = Modifier.size(20.dp))
    }
}

/** A sideways row of favourite or recent items. */
@Composable
private fun TvStrip(title: String, icon: ImageVector, items: List<TvItem>, onMore: () -> Unit, onOpen: (Int) -> Unit) {
    Column {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Amber, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.weight(1f))
            TextButton(onClick = onMore) { Text("See all", style = MaterialTheme.typography.labelLarge, color = TextMid) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(items.take(20), key = { _, it -> it.section.name + it.id }) { index, item ->
                val live = item.section == TvSection.LIVE
                Column(
                    Modifier
                        .width(if (live) 112.dp else 104.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(index) },
                ) {
                    WebPicture(
                        item.logo,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (live) 16f / 10f else 2f / 3f),
                        size = if (live) RemoteImages.LOGO else RemoteImages.POSTER,
                        crop = !live,
                        seed = item.name,
                        fallback = if (live) Icons.Rounded.LiveTv else Icons.Rounded.Movie,
                    )
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextHi,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}

// ---- signing in --------------------------------------------------------------------------------

@Composable
private fun tvFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Violet,
    unfocusedBorderColor = Outline,
    focusedLabelColor = Violet,
    cursorColor = Pink,
    focusedContainerColor = Surface3,
    unfocusedContainerColor = Surface3,
)

/** The sign-in form: a login (DNS / URL, user name, password) or a playlist link. */
@Composable
fun TvAccountDialog(existing: TvAccount?, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val clipboard = LocalClipboardManager.current
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
    val ready = if (playlistMode) link.isNotBlank() else server.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    fun paste() {
        val text = clipboard.getText()?.text.orEmpty()
        val login = Xtream.loginFromText(text)
        when {
            login != null -> {
                playlistMode = false
                server = login.server
                username = login.username
                password = login.password
                problem = null
            }
            text.trim().startsWith("http", ignoreCase = true) && !text.trim().contains(' ') -> {
                if (playlistMode) link = text.trim() else server = text.trim()
                problem = null
            }
            else -> problem = "Nothing copied looks like TV sign-in details. Copy the message from your provider first, then tap Paste."
        }
    }

    fun submit() {
        if (!ready || busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                val draft = if (playlistMode) {
                    TvAccount(id = "", name = name.trim(), type = TvAccount.PLAYLIST, playlistUrl = link.trim())
                } else {
                    TvAccount(id = "", name = name.trim(), type = TvAccount.XTREAM, server = server, username = username, password = password)
                }
                app.tv.add(draft, replaces = existing?.id)
                onDismiss()
            } catch (e: TvException) {
                problem = e.message
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Surface2,
        titleContentColor = TextHi,
        title = { Text(if (existing == null) "Add your TV service" else "Edit TV service", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Login", !playlistMode, { playlistMode = false })
                    Chip("Playlist link", playlistMode, { playlistMode = true })
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Outline, RoundedCornerShape(12.dp))
                        .clickable(enabled = !busy) { paste() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = Pink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Paste from your provider's message", style = MaterialTheme.typography.labelLarge, color = TextHi)
                        Text("Copy the whole message or link, then tap here", style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (playlistMode) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it },
                        label = { Text("Playlist link (M3U)") },
                        placeholder = { Text("http://...") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        colors = tvFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("DNS (URL)") },
                        placeholder = { Text("http://example.com:8080") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        colors = tvFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                        colors = tvFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = TextMid,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        colors = tvFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    placeholder = { Text("My TV") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    colors = tvFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8A8A))
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Pink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Checking with the service...", style = MaterialTheme.typography.bodySmall, color = TextMid)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = ready && !busy) {
                Text(if (existing == null) "Sign in" else "Save", color = if (ready && !busy) Pink else TextLow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel", color = TextMid) }
        },
    )
}

/** Switch between services, add or remove one, refresh the lists. */
@Composable
private fun TvServicesSheet(state: TvState, onAdd: () -> Unit, onEdit: (String) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    var removing by remember { mutableStateOf<TvAccount?>(null) }
    MwSheet(onDismiss) {
        Text("Your TV services", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
        state.accounts.forEach { account ->
            val selected = account.id == state.active?.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        app.tv.select(account.id)
                        onDismiss()
                    }
                    .padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "In use" else null,
                    tint = if (selected) Pink else TextLow,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val note = subscriptionNote(account)
                    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    onDismiss()
                    onEdit(account.id)
                }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit ${account.name}", tint = TextMid) }
                IconButton(onClick = { removing = account }) { Icon(Icons.Rounded.Delete, contentDescription = "Remove ${account.name}", tint = TextMid) }
            }
        }
        Spacer(Modifier.height(6.dp))
        SheetAction(Icons.Rounded.Refresh, "Refresh the lists now", {
            app.tv.refresh()
            app.say("Getting the latest lists from your service")
            onDismiss()
        })
        SheetAction(Icons.Rounded.Add, "Add another service", {
            onDismiss()
            onAdd()
        })
        if (state.recents.isNotEmpty()) {
            SheetAction(Icons.Rounded.History, "Clear recently watched", {
                app.tv.clearRecents()
                onDismiss()
            })
        }
    }
    removing?.let { account ->
        ConfirmDialog(
            title = "Remove ${account.name}?",
            message = "Its login, favourites and channel lists are removed from this phone. Your subscription itself is not affected.",
            confirm = "Remove",
            onDismiss = { removing = null },
            onConfirm = {
                app.tv.remove(account.id)
                removing = null
                onDismiss()
            },
        )
    }
}

/** Shared by the list and search screens: the category a catalogue item sits in. */
fun TvCatalog?.categoryOf(item: TvItem): String = this?.categoryName(item.category).orEmpty()
