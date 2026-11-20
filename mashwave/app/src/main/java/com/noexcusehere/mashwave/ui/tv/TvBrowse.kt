package com.noexcusehere.mashwave.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.APP_NAME
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.ResumeRules
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.iptv.TvException
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvRegions
import com.noexcusehere.mashwave.iptv.TvRepository
import com.noexcusehere.mashwave.iptv.TvSearch
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.ui.components.RemoteImages
import com.noexcusehere.mashwave.ui.components.WebPicture
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface1
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** First visit: nothing is signed in yet. */
@Composable
fun TvWelcome(ui: TvUi) {
    val start = remember { FocusRequester() }
    LandOn(start)
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(110.dp).clip(RoundedCornerShape(24.dp)))
        Spacer(Modifier.height(18.dp))
        Text("Welcome to $APP_NAME", style = MaterialTheme.typography.headlineLarge, color = TextHi, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with the details from your TV service and your channels, movies and shows appear here. The app has no channels of its own.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMid,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(24.dp))
        TvButton("Add your TV service", { ui.setupOpen = true }, icon = Icons.Rounded.Add, primary = true, requester = start)
    }
}

/** Plays the channel at [index] of [list] over the whole screen. */
private fun playChannel(ui: TvUi, app: com.noexcusehere.mashwave.MashwaveApp, list: List<TvItem>, index: Int) {
    val entry = app.tv.tune(list, index) ?: return
    app.player.play(listOf(entry), 0)
    ui.playerOpen = true
}

/** Live TV: groups on the left (moving over one shows its channels), channels on the right. */
@Composable
fun TvLive(ui: TvUi) {
    val app = LocalContext.current.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    val account = state.active?.id
    LaunchedEffect(account) { app.tv.setSection(TvSection.LIVE) }
    val shelf = state.shelf(TvSection.LIVE)
    val catalog = shelf.catalog
    if (catalog == null) {
        if (shelf.error != null) {
            TvNote(Icons.Rounded.WifiOff, "The channels could not be loaded", shelf.error.orEmpty(), action = "Try again", onAction = { app.tv.ensure(TvSection.LIVE, force = true) })
        } else {
            TvBusy("Loading channels...")
        }
        return
    }

    val counts = remember(catalog) { catalog.items.groupingBy { it.category }.eachCount() }
    val regionOf = remember(catalog) { catalog.categories.associate { it.id to TvRegions.of(it.name)?.key } }
    val regions = remember(catalog) { TvRegions.buttons(catalog.categories, counts, Locale.getDefault().country) }
    val hasOther = remember(catalog) { catalog.categories.any { (counts[it.id] ?: 0) > 0 && regionOf[it.id] == null } }
    val region = ui.liveRegion
    val groups = remember(catalog, region) {
        catalog.categories.filter { category ->
            (counts[category.id] ?: 0) > 0 && when (region) {
                null -> true
                TV_REGION_OTHER -> regionOf[category.id] == null
                else -> regionOf[category.id] == region
            }
        }
    }
    val favourites = remember(state.favourites) { state.favourites.filter { it.section == TvSection.LIVE } }
    val recents = remember(state.recents) { state.recents.filter { it.section == TvSection.LIVE } }
    val group = ui.liveGroup
    val channels = remember(catalog, group, groups, region, favourites, recents) {
        when (group) {
            TV_GROUP_ALL -> if (region == null) catalog.items else groups.flatMap { catalog.byCategory[it.id].orEmpty() }
            TV_GROUP_FAVOURITES -> favourites
            TV_GROUP_RECENT -> recents
            else -> catalog.byCategory[group].orEmpty()
        }
    }

    var choosingCountry by remember { mutableStateOf(false) }
    if (choosingCountry) {
        val names = listOf<Pair<String?, String>>(null to "All countries") + regions.map { it.key to it.key } + (if (hasOther) listOf(TV_REGION_OTHER to "Other") else emptyList())
        TvPicker("Choose a country", names.map { it.second }, names.indexOfFirst { it.first == region }.coerceAtLeast(0), onClose = { choosingCountry = false }) { chosen ->
            ui.liveRegion = names[chosen].first
            ui.liveGroup = TV_GROUP_ALL
            choosingCountry = false
        }
        return
    }

    // Coming back from a channel: the remote goes back on the channel that was on.
    val back = remember { FocusRequester() }
    val returnTo = ui.liveReturn
    if (returnTo in channels.indices) {
        LaunchedEffect(returnTo) {
            runCatching { ui.liveChannels.scrollToItem((returnTo - 3).coerceAtLeast(0)) }
            repeat(8) {
                if (runCatching { back.requestFocus() }.isSuccess) {
                    ui.liveReturn = -1
                    return@LaunchedEffect
                }
                delay(60)
            }
            ui.liveReturn = -1
        }
    }

    val showing = remember { FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(262.dp).fillMaxHeight().background(Surface1)) {
            Text("Live TV", style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.padding(start = 20.dp, top = 18.dp))
            Text("${catalog.items.size} channels", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            // Coming in from the side menu, the remote lands on the group that is showing (not on
            // whichever happens to be level with it, which would switch group by accident).
            LazyColumn(state = ui.liveGroups, modifier = Modifier.focusRestorer(showing), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (regions.size >= 2) {
                    item {
                        val label = when (region) {
                            null -> "All countries"
                            TV_REGION_OTHER -> "Other"
                            else -> region
                        }
                        TvSideRow("Country: $label", false, { choosingCountry = true }, icon = Icons.Rounded.Public)
                    }
                }
                item {
                    TvSideRow(if (region == null) "All channels" else "All in this country", group == TV_GROUP_ALL, { ui.liveGroup = TV_GROUP_ALL }, onLanded = { ui.liveGroup = TV_GROUP_ALL }, icon = Icons.Rounded.Tv, requester = showing.takeIf { group == TV_GROUP_ALL })
                }
                item {
                    TvSideRow("Favourites", group == TV_GROUP_FAVOURITES, { ui.liveGroup = TV_GROUP_FAVOURITES }, onLanded = { ui.liveGroup = TV_GROUP_FAVOURITES }, count = favourites.size, icon = Icons.Rounded.Favorite, requester = showing.takeIf { group == TV_GROUP_FAVOURITES })
                }
                if (recents.isNotEmpty()) {
                    item {
                        TvSideRow("Recently watched", group == TV_GROUP_RECENT, { ui.liveGroup = TV_GROUP_RECENT }, onLanded = { ui.liveGroup = TV_GROUP_RECENT }, icon = Icons.Rounded.History, requester = showing.takeIf { group == TV_GROUP_RECENT })
                    }
                }
                items(groups) { category ->
                    TvSideRow(category.name, group == category.id, { ui.liveGroup = category.id }, onLanded = { ui.liveGroup = category.id }, count = counts[category.id], requester = showing.takeIf { group == category.id })
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (shelf.error != null) {
                Text("Showing the list from last time: ${shelf.error}", style = MaterialTheme.typography.bodySmall, color = Amber, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            if (channels.isEmpty()) {
                TvNote(
                    Icons.Rounded.LiveTv,
                    if (group == TV_GROUP_FAVOURITES) "No favourites yet" else "No channels here",
                    if (group == TV_GROUP_FAVOURITES) "Hold OK (or press the menu button) on a channel to keep it here." else "This group is empty.",
                )
            } else {
                // A different group starts at its top.
                LaunchedEffect(group, region) { if (ui.liveReturn < 0) runCatching { ui.liveChannels.scrollToItem(0) } }
                // Coming back into the list with the remote lands on the channel it was last on.
                LazyColumn(
                    state = ui.liveChannels,
                    modifier = Modifier.focusRestorer(),
                    contentPadding = PaddingValues(start = 10.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                ) {
                    itemsIndexed(channels) { index, item ->
                        val starred = state.isFavourite(item)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .remote(
                                    onClick = {
                                        ui.liveReturn = index
                                        playChannel(ui, app, channels, index)
                                    },
                                    requester = if (index == returnTo) back else null,
                                    onMenu = {
                                        app.tv.toggleFavourite(item)
                                        ui.message = if (starred) "Removed from favourites" else "Added to favourites"
                                    },
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text((index + 1).toString(), style = MaterialTheme.typography.labelLarge, color = TextMid, textAlign = TextAlign.End, modifier = Modifier.width(44.dp), maxLines = 1)
                            Spacer(Modifier.width(12.dp))
                            WebPicture(item.logo, Modifier.size(width = 58.dp, height = 40.dp), RoundedCornerShape(6.dp), size = RemoteImages.LOGO, seed = item.name, inset = 3.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(item.name, style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (starred) Icon(Icons.Rounded.Favorite, contentDescription = "Favourite", tint = Pink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A full-screen list to pick one thing from. */
@Composable
fun TvPicker(title: String, choices: List<String>, chosen: Int, onClose: () -> Unit, onChoose: (Int) -> Unit) {
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    LandOn(first)
    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 80.dp, vertical = 24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TextHi)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.widthIn(max = 520.dp)) {
            itemsIndexed(choices) { index, name ->
                TvSideRow(name, index == chosen, { onChoose(index) }, requester = if (index == chosen) first else null)
            }
        }
    }
}

/** Movies or Shows: kinds on the left, posters on the right. */
@Composable
fun TvVod(ui: TvUi, section: TvSection) {
    val app = LocalContext.current.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    val account = state.active?.id
    LaunchedEffect(account, section) { app.tv.setSection(section) }
    val shelf = state.shelf(section)
    val catalog = shelf.catalog
    if (catalog == null) {
        if (shelf.error != null) {
            TvNote(Icons.Rounded.WifiOff, "The list could not be loaded", shelf.error.orEmpty(), action = "Try again", onAction = { app.tv.ensure(section, force = true) })
        } else {
            TvBusy("Loading ${section.many}...")
        }
        return
    }
    val blank = if (section == TvSection.MOVIES) Icons.Rounded.Movie else Icons.Rounded.VideoLibrary
    if (catalog.items.isEmpty()) {
        TvNote(blank, "No ${section.many} here", "This TV service does not list any ${section.many}.")
        return
    }
    val counts = remember(catalog) { catalog.items.groupingBy { it.category }.eachCount() }
    val groups = remember(catalog) { catalog.categories.filter { (counts[it.id] ?: 0) > 0 } }
    val favourites = remember(state.favourites, section) { state.favourites.filter { it.section == section } }
    val recents = remember(state.recents, section) { state.recents.filter { it.section == section } }
    val group = ui.vodGroup[section] ?: TV_GROUP_ALL
    val shown = remember(catalog, group, favourites, recents) {
        when (group) {
            TV_GROUP_ALL -> catalog.items
            TV_GROUP_FAVOURITES -> favourites
            TV_GROUP_RECENT -> recents
            else -> catalog.byCategory[group].orEmpty()
        }
    }
    val grid = ui.vodGrids.getValue(section)
    val back = remember { FocusRequester() }
    val returnTo = ui.vodReturn[section] ?: -1
    if (returnTo in shown.indices) {
        LaunchedEffect(returnTo) {
            repeat(8) {
                if (runCatching { back.requestFocus() }.isSuccess) {
                    ui.vodReturn[section] = -1
                    return@LaunchedEffect
                }
                delay(60)
            }
            ui.vodReturn[section] = -1
        }
    }
    fun choose(id: String) {
        ui.vodGroup[section] = id
    }
    val showing = remember { FocusRequester() }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(262.dp).fillMaxHeight().background(Surface1)) {
            Text(section.title, style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.padding(start = 20.dp, top = 18.dp))
            Text("${catalog.items.size} ${section.many}", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp))
            LazyColumn(state = ui.vodGroups.getValue(section), modifier = Modifier.focusRestorer(showing), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { TvSideRow("All ${section.many}", group == TV_GROUP_ALL, { choose(TV_GROUP_ALL) }, onLanded = { choose(TV_GROUP_ALL) }, icon = blank, requester = showing.takeIf { group == TV_GROUP_ALL }) }
                item { TvSideRow("Favourites", group == TV_GROUP_FAVOURITES, { choose(TV_GROUP_FAVOURITES) }, onLanded = { choose(TV_GROUP_FAVOURITES) }, count = favourites.size, icon = Icons.Rounded.Favorite, requester = showing.takeIf { group == TV_GROUP_FAVOURITES }) }
                if (recents.isNotEmpty()) {
                    item { TvSideRow("Recently watched", group == TV_GROUP_RECENT, { choose(TV_GROUP_RECENT) }, onLanded = { choose(TV_GROUP_RECENT) }, icon = Icons.Rounded.History, requester = showing.takeIf { group == TV_GROUP_RECENT }) }
                }
                items(groups) { category ->
                    TvSideRow(category.name, group == category.id, { choose(category.id) }, onLanded = { choose(category.id) }, count = counts[category.id], requester = showing.takeIf { group == category.id })
                }
            }
        }
        if (shown.isEmpty()) {
            TvNote(blank, if (group == TV_GROUP_FAVOURITES) "No favourites yet" else "Nothing here", if (group == TV_GROUP_FAVOURITES) "Open a page and choose \"Add to favourites\" to keep it here." else "This group is empty.", Modifier.weight(1f))
        } else {
            LaunchedEffect(group) { if ((ui.vodReturn[section] ?: -1) < 0) runCatching { grid.scrollToItem(0) } }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                state = grid,
                modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
                contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(shown) { index, item ->
                    TvPoster(item, requester = if (index == returnTo) back else null) {
                        ui.vodReturn[section] = index
                        openItem(ui, app, item)
                    }
                }
            }
        }
    }
}

/** Opens whatever [item] is: a channel plays, a movie or show shows its page. */
private fun openItem(ui: TvUi, app: com.noexcusehere.mashwave.MashwaveApp, item: TvItem, list: List<TvItem> = listOf(item), index: Int = 0) {
    when {
        item.section == TvSection.LIVE -> playChannel(ui, app, list, index)
        item.opensDetails || item.section == TvSection.MOVIES -> ui.details = item
        else -> {
            val entry = app.tv.entry(item) ?: return
            app.tv.watched(item)
            app.player.play(listOf(entry), 0)
            ui.playerOpen = true
        }
    }
}

@Composable
fun TvPoster(item: TvItem, modifier: Modifier = Modifier, requester: FocusRequester? = null, onClick: () -> Unit) {
    Column(modifier.remote(onClick, RoundedCornerShape(12.dp), requester = requester).padding(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(9.dp)).background(Surface2)) {
            WebPicture(
                item.logo,
                Modifier.fillMaxSize(),
                RoundedCornerShape(9.dp),
                size = RemoteImages.POSTER,
                crop = true,
                seed = item.name,
                fallback = if (item.section == TvSection.SHOWS) Icons.Rounded.VideoLibrary else Icons.Rounded.Movie,
            )
        }
        Text(item.name, style = MaterialTheme.typography.labelLarge, color = TextHi, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
    }
}

/** The page of one movie or show: what it is about, and the button (or the episodes) to play it. */
@Composable
fun TvDetails(ui: TvUi, item: TvItem) {
    val app = LocalContext.current.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    val resume by app.store.resume.collectAsStateWithLifecycle()
    BackHandler { ui.details = null }
    var details by remember(item) { mutableStateOf(app.tv.cachedDetails(item)) }
    var problem by remember(item) { mutableStateOf<String?>(null) }
    var attempt by remember(item) { mutableIntStateOf(0) }
    LaunchedEffect(item, attempt) {
        if (details != null) return@LaunchedEffect
        problem = null
        try {
            details = app.tv.details(item)
        } catch (e: TvException) {
            problem = e.message
        }
    }
    val loaded = details
    val show = item.section == TvSection.SHOWS
    val starred = state.isFavourite(item)
    val first = remember { FocusRequester() }
    val rows = rememberLazyListState()
    // In a show the remote starts on the episode last watched, so carrying on is one press of OK.
    val lastWatched = remember(item, loaded) {
        if (!show || loaded == null) {
            null
        } else {
            loaded.seasons
                .flatMap { number -> app.tv.episodes(item, loaded, number).mapIndexed { index, episode -> Triple(number, index, resume[episode.uri]?.at ?: 0L) } }
                .filter { it.third > 0 }
                .maxByOrNull { it.third }
        }
    }
    LaunchedEffect(loaded != null) {
        // Further down than the first screenful: bring it into view before the remote can land on it.
        val down = lastWatched?.second ?: 0
        if (down > 0) runCatching { rows.scrollToItem(down) }
        repeat(8) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(60)
        }
    }

    fun playMovie(fromStart: Boolean) {
        val entry = app.tv.entry(item) ?: return
        app.tv.watched(item)
        if (fromStart) app.store.clearResume(entry.uri)
        app.player.play(listOf(entry), 0)
        ui.playerOpen = true
    }

    Row(Modifier.fillMaxSize().background(Ink).padding(start = 36.dp, top = 26.dp, end = 28.dp)) {
        Box(Modifier.width(210.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Surface2)) {
            WebPicture(
                loaded?.cover?.ifBlank { item.logo } ?: item.logo,
                Modifier.fillMaxSize(),
                RoundedCornerShape(14.dp),
                size = RemoteImages.POSTER,
                crop = true,
                seed = item.name,
                fallback = if (show) Icons.Rounded.VideoLibrary else Icons.Rounded.Movie,
            )
        }
        Spacer(Modifier.width(28.dp))
        var season by remember(item, loaded) { mutableIntStateOf(lastWatched?.first ?: loaded?.seasons?.firstOrNull() ?: 1) }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = rows, contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Text(loaded?.name?.ifBlank { item.name } ?: item.name, style = MaterialTheme.typography.headlineLarge, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    loaded?.released?.takeIf { it.isNotBlank() },
                    loaded?.genre?.takeIf { it.isNotBlank() },
                    loaded?.durationSec?.takeIf { it > 0 }?.let { formatDuration(it * 1000L) },
                    (loaded?.rating?.takeIf { it.isNotBlank() && it != "0" } ?: item.rating.takeIf { it > 0f }?.let { String.format(Locale.US, "%.1f", it) })?.let { "★ $it" },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString("   ·   "), style = MaterialTheme.typography.bodyLarge, color = TextMid, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!show) {
                        val entry = remember(item, loaded) { app.tv.entry(item) }
                        val place = entry?.let { resume[it.uri] }
                        if (place != null) {
                            TvButton("Carry on from ${formatDuration(ResumeRules.startFrom(place))}", { playMovie(false) }, icon = Icons.Rounded.PlayArrow, primary = true, requester = first)
                            TvButton("Start again", { playMovie(true) }, icon = Icons.Rounded.Replay)
                        } else {
                            TvButton("Play", { playMovie(false) }, icon = Icons.Rounded.PlayArrow, primary = true, requester = first)
                        }
                    }
                    TvButton(
                        if (starred) "In favourites" else "Favourite",
                        { app.tv.toggleFavourite(item) },
                        icon = if (starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        requester = if (show && (loaded == null || loaded.episodes.isEmpty())) first else null,
                    )
                }
                Spacer(Modifier.height(14.dp))
                when {
                    problem != null -> {
                        Text(problem.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = Amber)
                        Spacer(Modifier.height(8.dp))
                        TvButton("Try again", { attempt++ })
                    }
                    loaded == null -> Text("Loading...", style = MaterialTheme.typography.bodyLarge, color = TextLow)
                    else -> {
                        if (loaded.plot.isNotBlank()) Text(loaded.plot, style = MaterialTheme.typography.bodyLarge, color = TextHi.copy(alpha = 0.9f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (loaded.cast.isNotBlank()) Text("With ${loaded.cast}", style = MaterialTheme.typography.bodyMedium, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (show && loaded != null) {
                if (loaded.episodes.isEmpty()) {
                    item { Text("The service lists no episodes for this show.", style = MaterialTheme.typography.bodyLarge, color = TextLow, modifier = Modifier.padding(top = 16.dp)) }
                } else {
                    val all: List<MediaEntry> = loaded.seasons.flatMap { app.tv.episodes(item, loaded, it) }
                    if (loaded.seasons.size > 1) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) {
                                items(loaded.seasons) { number ->
                                    Text(
                                        "Season $number",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextHi,
                                        modifier = Modifier.remote({ season = number }, RoundedCornerShape(50), chosen = season == number, onLanded = { season = number }).padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        item { Spacer(Modifier.height(14.dp)) }
                    }
                    val episodes = loaded.episodes.filter { it.season == season }
                    val entries = app.tv.episodes(item, loaded, season)
                    val landOn = lastWatched?.takeIf { it.first == season }?.second ?: 0
                    itemsIndexed(episodes) { index, episode ->
                        val entry = entries.getOrNull(index)
                        val place = entry?.let { resume[it.uri] }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .remote(
                                    onClick = {
                                        if (entry != null) {
                                            app.tv.watched(item)
                                            val at = all.indexOfFirst { it.uri == entry.uri }
                                            // The whole show is lined up, so "next" carries on into the following season.
                                            if (at >= 0) app.player.play(all, at) else app.player.play(entries, index)
                                            ui.playerOpen = true
                                        }
                                    },
                                    requester = if (index == landOn) first else null,
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${episode.number}", style = MaterialTheme.typography.titleLarge, color = TextMid, modifier = Modifier.width(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(TvRepository.episodeTitle(item.name, episode), style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val under = listOfNotNull(
                                    episode.durationSec.takeIf { it > 0 }?.let { formatDuration(it * 1000L) },
                                    place?.let { "stopped at ${formatDuration(it.positionMs)}" },
                                ).joinToString("  ·  ")
                                if (under.isNotBlank()) Text(under, style = MaterialTheme.typography.bodyMedium, color = if (place != null) Pink else TextMid)
                            }
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** One box that looks through channels, movies and shows at once. */
@Composable
fun TvSearch(ui: TvUi) {
    val app = LocalContext.current.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    var found by remember { mutableStateOf<Map<TvSection, List<TvItem>>>(emptyMap()) }
    val catalogs = TvSection.entries.associateWith { state.shelf(it).catalog }
    LaunchedEffect(state.active?.id) { TvSection.entries.forEach { app.tv.ensure(it) } }
    LaunchedEffect(ui.query, catalogs.values.map { it?.loadedAt }) {
        if (ui.query.isBlank()) {
            found = emptyMap()
            return@LaunchedEffect
        }
        delay(350)
        found = withContext(Dispatchers.Default) {
            TvSection.entries.associateWith { section -> catalogs[section]?.let { TvSearch.find(it.items, ui.query, limit = 40) }.orEmpty() }
        }
    }
    // Coming in from the side menu the remote lands on the box to type in (or where it was last time).
    val box = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().focusRestorer(box).focusGroup().padding(start = 24.dp, end = 24.dp, top = 18.dp)) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, color = TextHi)
        Spacer(Modifier.height(10.dp))
        TvTextBox(ui.query, { ui.query = it }, "Press OK and type a name", Modifier.widthIn(max = 560.dp).fillMaxWidth(), icon = Icons.Rounded.Search, requester = box)
        Spacer(Modifier.height(8.dp))
        when {
            ui.query.isBlank() -> TvNote(Icons.Rounded.Search, "Find anything", "Type part of a name. Every word has to be in it, in any order.")
            found.values.all { it.isEmpty() } -> TvNote(Icons.Rounded.Search, "Nothing found", "Nothing has \"${ui.query}\" in its name.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                val channels = found[TvSection.LIVE].orEmpty()
                if (channels.isNotEmpty()) {
                    item { Text("Channels", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                    itemsIndexed(channels.take(10)) { index, item -> TvChannelLine(item, state.isFavourite(item)) { playChannel(ui, app, channels, index) } }
                }
                for (section in listOf(TvSection.MOVIES, TvSection.SHOWS)) {
                    val items = found[section].orEmpty()
                    if (items.isEmpty()) continue
                    item { Text(section.title, style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items) { item -> TvPoster(item, Modifier.width(140.dp)) { openItem(ui, app, item) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvChannelLine(item: TvItem, starred: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 2.dp).remote(onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WebPicture(item.logo, Modifier.size(width = 58.dp, height = 40.dp), RoundedCornerShape(6.dp), size = RemoteImages.LOGO, seed = item.name, inset = 3.dp)
        Spacer(Modifier.width(14.dp))
        Text(item.name, style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (starred) Icon(Icons.Rounded.Favorite, contentDescription = "Favourite", tint = Pink, modifier = Modifier.size(20.dp))
    }
}

/** Everything with a heart, and what was watched lately. */
@Composable
fun TvSaved(ui: TvUi) {
    val app = LocalContext.current.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    if (state.favourites.isEmpty() && state.recents.isEmpty()) {
        TvNote(Icons.Rounded.Favorite, "Nothing here yet", "Channels, movies and shows you add to favourites are kept here, along with what you watched lately.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp)) {
        item { Text("Favourites", style = MaterialTheme.typography.headlineSmall, color = TextHi) }
        val channels = state.favourites.filter { it.section == TvSection.LIVE }
        if (channels.isNotEmpty()) {
            item { Text("Channels", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
            itemsIndexed(channels) { index, item -> TvChannelLine(item, true) { playChannel(ui, app, channels, index) } }
        }
        for (section in listOf(TvSection.MOVIES, TvSection.SHOWS)) {
            val items = state.favourites.filter { it.section == section }
            if (items.isEmpty()) continue
            item { Text(section.title, style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(items) { item -> TvPoster(item, Modifier.width(140.dp)) { openItem(ui, app, item) } } } }
        }
        if (state.favourites.isEmpty()) item { Text("Nothing is in favourites yet.", style = MaterialTheme.typography.bodyLarge, color = TextLow, modifier = Modifier.padding(top = 8.dp)) }
        if (state.recents.isNotEmpty()) {
            item { Text("Recently watched", style = MaterialTheme.typography.titleLarge, color = TextHi, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
            val recentChannels = state.recents.filter { it.section == TvSection.LIVE }
            itemsIndexed(recentChannels.take(6)) { index, item -> TvChannelLine(item, state.isFavourite(item)) { playChannel(ui, app, recentChannels, index) } }
            val others = state.recents.filter { it.section != TvSection.LIVE }
            if (others.isNotEmpty()) {
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) { items(others) { item -> TvPoster(item, Modifier.width(140.dp)) { openItem(ui, app, item) } } } }
            }
        }
    }
}
