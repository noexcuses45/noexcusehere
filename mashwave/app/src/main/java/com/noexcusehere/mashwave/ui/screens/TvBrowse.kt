package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvSearch
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.iptv.tvRef
import com.noexcusehere.mashwave.ui.CastButton
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.components.HeaderIcon
import com.noexcusehere.mashwave.ui.components.PlayingBars
import com.noexcusehere.mashwave.ui.components.RemoteImages
import com.noexcusehere.mashwave.ui.components.ScreenHeader
import com.noexcusehere.mashwave.ui.components.WebPicture
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val sortNames = listOf("Service order", "A to Z", "Newest first")

/** One category's channels (as a list) or movies / shows (as posters), with a filter box and sorting. */
@Composable
fun TvListScreen(sectionName: String, category: String) {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val state by app.tv.state.collectAsStateWithLifecycle()
    val player by app.player.state.collectAsStateWithLifecycle()
    val section = remember(sectionName) { TvSection.entries.firstOrNull { it.name == sectionName } ?: TvSection.LIVE }
    val catalog = state.shelf(section).catalog
    var filtering by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(section) { app.tv.ensure(section) }

    val all = remember(catalog, category, state.favourites, state.recents) {
        when (category) {
            TV_ALL -> catalog?.items.orEmpty()
            TV_FAVOURITES -> state.favourites.filter { it.section == section }
            TV_RECENT -> state.recents.filter { it.section == section }
            else -> catalog?.byCategory?.get(category).orEmpty()
        }
    }
    val title = when (category) {
        TV_ALL -> "All ${section.many}"
        TV_FAVOURITES -> "Favourites"
        TV_RECENT -> "Recently watched"
        else -> catalog?.categoryName(category).orEmpty().ifBlank { section.title }
    }
    val shown = remember(all, filter, sort) {
        val base = if (filter.isBlank()) all else TvSearch.find(all, filter, limit = 2000)
        when (sort) {
            1 -> base.sortedBy { it.name.lowercase() }
            2 -> base.sortedByDescending { it.added }
            else -> base
        }
    }
    val playingId = player.current?.tvRef?.id

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        if (filtering) {
            TvSearchBox(filter, { filter = it }, "Find in $title") {
                filtering = false
                filter = ""
            }
        } else {
            ScreenHeader(title, onBack = { nav.popBackStack() }) {
                CastButton(com.noexcusehere.mashwave.cast.CastWhat.VIDEO)
                HeaderIcon(Icons.Rounded.Search, "Find in this list", { filtering = true })
                if (category != TV_RECENT) {
                    HeaderIcon(Icons.AutoMirrored.Rounded.Sort, "Change order: ${sortNames[sort]}", {
                        sort = (sort + 1) % sortNames.size
                        app.say("Order: ${sortNames[sort]}")
                    })
                }
            }
        }
        when {
            catalog == null && category !in listOf(TV_FAVOURITES, TV_RECENT) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Pink)
            }
            shown.isEmpty() -> Text(
                if (filter.isNotBlank()) "Nothing here matches “$filter”." else "Nothing here yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
            section == TvSection.LIVE -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
                    ChannelRow(
                        item = item,
                        number = if (sort == 0 && filter.isBlank()) index + 1 else 0,
                        playing = item.id == playingId,
                        animate = player.isPlaying,
                        favourite = state.isFavourite(item),
                        onStar = { app.tv.toggleFavourite(item) },
                        onClick = { openTvItem(app, ui, nav, shown, index) },
                    )
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(shown, key = { _, it -> it.id }) { index, item ->
                    PosterCard(item, state.isFavourite(item), onLongClick = {
                        app.tv.toggleFavourite(item)
                        app.say(if (state.isFavourite(item)) "Removed from favourites" else "Added to favourites")
                    }) { openTvItem(app, ui, nav, shown, index) }
                }
            }
        }
    }
}

@Composable
fun ChannelRow(
    item: TvItem,
    number: Int,
    playing: Boolean,
    animate: Boolean,
    favourite: Boolean,
    onStar: (() -> Unit)?,
    subtitle: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (playing) Surface2 else Color.Transparent)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number > 0) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = TextLow,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(34.dp).padding(end = 8.dp),
            )
        }
        WebPicture(item.logo, Modifier.size(width = 68.dp, height = 46.dp), RoundedCornerShape(8.dp), seed = item.name, inset = 5.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, color = if (playing) Pink else TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) {
            PlayingBars(animate, Modifier.padding(horizontal = 8.dp).size(18.dp))
        }
        if (onStar != null) {
            IconButton(onClick = onStar) {
                Icon(
                    if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
                    tint = if (favourite) Amber else TextLow,
                )
            }
        }
    }
}

@Composable
fun PosterCard(item: TvItem, favourite: Boolean, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            WebPicture(
                item.logo,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                size = RemoteImages.POSTER,
                crop = true,
                seed = item.name,
                fallback = Icons.Rounded.Movie,
            )
            if (item.rating > 0f) {
                Text(
                    "★ " + "%.1f".format(item.rating),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            if (favourite) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = "Favourite",
                    tint = Amber,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(20.dp),
                )
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextHi,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 2.dp),
        )
    }
}

/** A search field that takes the place of the page title. */
@Composable
fun TvSearchBox(value: String, onChange: (String) -> Unit, hint: String, autoFocus: Boolean = true, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // Where the list underneath matters more than typing, the keyboard waits to be asked for.
        if (autoFocus) {
            delay(120)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            keyboard?.hide()
            onClose()
        }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = TextHi) }
        TextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(hint, color = TextLow, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMid) },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMid) }
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Pink,
                focusedTextColor = TextHi,
                unfocusedTextColor = TextHi,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
        )
    }
}

private class TvHits(val query: String, val bySection: Map<TvSection, List<TvItem>>)

/** Search across every channel, movie and show of the service at once. */
@Composable
fun TvSearchScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val state by app.tv.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf(TvHits("", emptyMap())) }
    val catalogs = TvSection.entries.associateWith { state.shelf(it).catalog }

    // Movies and shows are only fetched once someone wants them; searching counts.
    LaunchedEffect(state.active?.id) { TvSection.entries.forEach { app.tv.ensure(it) } }

    LaunchedEffect(query, catalogs.values.map { it?.loadedAt }) {
        val text = query.trim()
        if (text.length < 2) {
            hits = TvHits(text, emptyMap())
            return@LaunchedEffect
        }
        delay(250)
        hits = withContext(Dispatchers.Default) {
            TvHits(text, TvSection.entries.associateWith { TvSearch.find(catalogs[it]?.items.orEmpty(), text, limit = 60) })
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        TvSearchBox(query, { query = it }, "Channels, movies, shows") { nav.popBackStack() }
        val loading = TvSection.entries.filter { state.shelf(it).catalog == null && state.shelf(it).loading }
        if (loading.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Pink, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Still getting the list of " + loading.joinToString(" and ") { it.many } + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
        }
        val total = hits.bySection.values.sumOf { it.size }
        when {
            query.trim().length < 2 -> Text(
                "Type a channel, a movie or a show. Every word you type has to be in the name, in any order.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
            total == 0 && hits.query == query.trim() -> Text(
                "Nothing found for “${query.trim()}”.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                TvSection.entries.forEach { section ->
                    val found = hits.bySection[section].orEmpty()
                    if (found.isEmpty()) return@forEach
                    item(key = "h" + section.name) {
                        Text(
                            section.title + if (found.size >= 60) "  (first 60 - type more to narrow it down)" else "  (${found.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextHi,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
                        )
                    }
                    itemsIndexed(found, key = { _, it -> section.name + it.id }) { index, item ->
                        if (section == TvSection.LIVE) {
                            ChannelRow(
                                item = item,
                                number = 0,
                                playing = false,
                                animate = false,
                                favourite = state.isFavourite(item),
                                onStar = { app.tv.toggleFavourite(item) },
                                subtitle = catalogs[section].categoryOf(item),
                                onClick = { openTvItem(app, ui, nav, found, index) },
                            )
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { openTvItem(app, ui, nav, found, index) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                WebPicture(
                                    item.logo,
                                    Modifier.size(width = 46.dp, height = 68.dp),
                                    RoundedCornerShape(6.dp),
                                    size = RemoteImages.LOGO,
                                    crop = true,
                                    seed = item.name,
                                    fallback = Icons.Rounded.Movie,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val group = catalogs[section].categoryOf(item)
                                    if (group.isNotBlank()) Text(group, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
