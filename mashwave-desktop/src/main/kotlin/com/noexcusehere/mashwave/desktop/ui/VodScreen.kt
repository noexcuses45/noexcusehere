package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noexcusehere.mashwave.data.ResumeRules
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.iptv.TvException
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvRepository
import com.noexcusehere.mashwave.iptv.TvSearch
import com.noexcusehere.mashwave.iptv.TvSection

/** Movies or Shows: kinds down the side, posters in the middle. */
@Composable
fun VodScreen(desk: Desk, section: TvSection) {
    val state by desk.tv.state.collectAsState()
    val account = state.active?.id
    LaunchedEffect(account, section) { desk.tv.setSection(section) }
    val shelf = state.shelf(section)
    val catalog = shelf.catalog

    var group by rememberSaveable(account, section) { mutableStateOf(GROUP_ALL) }
    var filter by rememberSaveable(account, section) { mutableStateOf("") }

    if (catalog == null) {
        when {
            shelf.error != null -> Note(Icons.Rounded.WifiOff, "The list could not be loaded", shelf.error.orEmpty(), action = "Try again", onAction = { desk.tv.ensure(section, force = true) })
            else -> Busy("Loading ${section.many}...")
        }
        return
    }
    if (catalog.items.isEmpty()) {
        Note(
            if (section == TvSection.MOVIES) Icons.Rounded.Movie else Icons.Rounded.VideoLibrary,
            "No ${section.many} here",
            "This TV service does not list any ${section.many}.",
        )
        return
    }

    val counts = remember(catalog) { catalog.items.groupingBy { it.category }.eachCount() }
    val groups = remember(catalog) { catalog.categories.filter { (counts[it.id] ?: 0) > 0 } }
    val favourites = remember(state.favourites, section) { state.favourites.filter { it.section == section } }
    val recents = remember(state.recents, section) { state.recents.filter { it.section == section } }
    val base = remember(catalog, group, favourites, recents) {
        when (group) {
            GROUP_ALL -> catalog.items
            GROUP_FAVOURITES -> favourites
            GROUP_RECENT -> recents
            else -> catalog.byCategory[group].orEmpty()
        }
    }
    val shown = remember(base, filter) { if (filter.isBlank()) base else TvSearch.find(base, filter, limit = 600) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(248.dp).fillMaxHeight().background(Surface1)) {
            Text(section.title, style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 12.dp))
            Text("${catalog.items.size} ${section.many}", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = Modifier.padding(start = 18.dp, bottom = 10.dp))
            val groupList = rememberLazyListState()
            Box(Modifier.weight(1f)) {
                LazyColumn(state = groupList, contentPadding = PaddingValues(bottom = 12.dp)) {
                    item { SideRow("All ${section.many}", group == GROUP_ALL, { group = GROUP_ALL }, icon = if (section == TvSection.MOVIES) Icons.Rounded.Movie else Icons.Rounded.VideoLibrary) }
                    item { SideRow("Favourites", group == GROUP_FAVOURITES, { group = GROUP_FAVOURITES }, count = favourites.size, icon = Icons.Rounded.Favorite) }
                    if (recents.isNotEmpty()) item { SideRow("Recently watched", group == GROUP_RECENT, { group = GROUP_RECENT }, icon = Icons.Rounded.History) }
                    item { Spacer(Modifier.height(8.dp)) }
                    items(groups) { category -> SideRow(category.name, group == category.id, { group = category.id }, count = counts[category.id]) }
                }
                ListBar(groupList)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Field(filter, { filter = it }, "Find in ${section.many}", Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 8.dp), icon = Icons.Rounded.Search)
            if (shown.isEmpty()) {
                Note(
                    if (section == TvSection.MOVIES) Icons.Rounded.Movie else Icons.Rounded.VideoLibrary,
                    if (filter.isNotBlank()) "Nothing found" else if (group == GROUP_FAVOURITES) "No favourites yet" else "Nothing here",
                    if (filter.isNotBlank()) "Nothing in this list has \"$filter\" in its name." else if (group == GROUP_FAVOURITES) "Click the heart on a page to keep it here." else "This group is empty.",
                )
            } else {
                PosterGrid(shown, Modifier.weight(1f), resetOn = "$group|$filter") { item -> desk.open(item) }
            }
        }
    }
}

@Composable
fun PosterGrid(items: List<TvItem>, modifier: Modifier = Modifier, resetOn: String = "", onOpen: (TvItem) -> Unit) {
    val grid = rememberLazyGridState()
    // A different list starts at its top; coming back to the same one keeps its place.
    var shownFor by rememberSaveable { mutableStateOf(resetOn) }
    LaunchedEffect(resetOn) {
        if (shownFor != resetOn) {
            shownFor = resetOn
            grid.scrollToItem(0)
        }
    }
    Box(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = grid,
            contentPadding = PaddingValues(start = 18.dp, end = 22.dp, top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items) { _, item -> Poster(item, onClick = { onOpen(item) }) }
        }
        GridBar(grid)
    }
}

@Composable
fun Poster(item: TvItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Surface2)) {
            Picture(item.logo, Modifier.fillMaxSize(), side = 420) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (item.section == TvSection.SHOWS) Icons.Rounded.VideoLibrary else Icons.Rounded.Movie, contentDescription = null, tint = TextLow, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(item.name, style = MaterialTheme.typography.labelMedium, color = TextMid, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.rating > 0f) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.66f)).padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = Amber, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("%.1f".format(item.rating), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.labelLarge,
            color = TextHi,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 4.dp),
        )
    }
}

/** The page of one movie or show: what it is about, and the button (or the episodes) to play it. */
@Composable
fun DetailsPage(desk: Desk, item: TvItem) {
    val state by desk.tv.state.collectAsState()
    var details by remember(item) { mutableStateOf(desk.tv.cachedDetails(item)) }
    var problem by remember(item) { mutableStateOf<String?>(null) }
    var attempt by remember(item) { mutableIntStateOf(0) }
    LaunchedEffect(item, attempt) {
        if (details != null) return@LaunchedEffect
        problem = null
        try {
            details = desk.tv.details(item)
        } catch (e: TvException) {
            problem = e.message
        }
    }
    val loaded = details
    val show = item.section == TvSection.SHOWS
    val starred = state.isFavourite(item)

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { desk.page = null })
            Spacer(Modifier.width(6.dp))
            Text(if (show) "Shows" else "Movies", style = MaterialTheme.typography.titleMedium, color = TextMid)
        }
        Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 12.dp, top = 12.dp)) {
            Column(Modifier.width(250.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Surface2)) {
                    Picture(loaded?.cover?.ifBlank { item.logo } ?: item.logo, Modifier.fillMaxSize(), side = 700) {
                        Icon(if (show) Icons.Rounded.VideoLibrary else Icons.Rounded.Movie, contentDescription = null, tint = TextLow, modifier = Modifier.size(40.dp))
                    }
                }
            }
            Spacer(Modifier.width(28.dp))
            val scroll = rememberLazyListState()
            var season by remember(item, loaded) { mutableIntStateOf(loaded?.seasons?.firstOrNull() ?: 1) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                LazyColumn(state = scroll, contentPadding = PaddingValues(end = 20.dp, bottom = 28.dp)) {
                    item {
                        Text(loaded?.name?.ifBlank { item.name } ?: item.name, style = MaterialTheme.typography.displaySmall, color = TextHi)
                        val facts = listOfNotNull(
                            loaded?.released?.takeIf { it.isNotBlank() },
                            loaded?.genre?.takeIf { it.isNotBlank() },
                            loaded?.durationSec?.let { length(it) }?.takeIf { it.isNotBlank() },
                            (loaded?.rating?.takeIf { it.isNotBlank() && it != "0" } ?: item.rating.takeIf { it > 0f }?.let { "%.1f".format(it) })?.let { "★ $it" },
                        )
                        if (facts.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(facts.joinToString("   ·   "), style = MaterialTheme.typography.bodyMedium, color = TextMid)
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!show) {
                                val entry = remember(item, loaded) { desk.tv.entry(item) }
                                val place = desk.placeOf(entry)
                                if (place != null) {
                                    Pill("Carry on from ${clock(ResumeRules.startFrom(place))}", { desk.playMovie(item) }, icon = Icons.Rounded.PlayArrow)
                                    Quiet("Start again", { desk.playMovie(item, fromStart = true) }, icon = Icons.Rounded.Replay)
                                } else {
                                    Pill("Play", { desk.playMovie(item) }, icon = Icons.Rounded.PlayArrow)
                                }
                            }
                            Quiet(
                                if (starred) "In favourites" else "Add to favourites",
                                { desk.tv.toggleFavourite(item) },
                                icon = if (starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                tint = if (starred) Pink else TextHi,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        when {
                            problem != null -> {
                                Text(problem.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Amber)
                                Spacer(Modifier.height(8.dp))
                                Quiet("Try again", { attempt++ })
                            }
                            loaded == null -> Text("Loading...", style = MaterialTheme.typography.bodyMedium, color = TextLow)
                            else -> {
                                if (loaded.plot.isNotBlank()) Text(loaded.plot, style = MaterialTheme.typography.bodyLarge, color = TextHi.copy(alpha = 0.9f), modifier = Modifier.widthIn(max = 760.dp))
                                if (loaded.cast.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text("Cast: ${loaded.cast}", style = MaterialTheme.typography.bodyMedium, color = TextMid, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
                                }
                                if (loaded.director.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Director: ${loaded.director}", style = MaterialTheme.typography.bodyMedium, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (show && loaded != null) {
                        if (loaded.episodes.isEmpty()) {
                            item {
                                Spacer(Modifier.height(18.dp))
                                Text("The service lists no episodes for this show.", style = MaterialTheme.typography.bodyMedium, color = TextLow)
                            }
                        } else {
                            item {
                                Spacer(Modifier.height(22.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(loaded.seasons) { number -> Chip("Season $number", season == number, { season = number }) }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            val episodes = loaded.episodes.filter { it.season == season }
                            val entries = desk.tv.episodes(item, loaded, season)
                            items(episodes.size) { index ->
                                val episode = episodes[index]
                                val place = desk.placeOf(entries.getOrNull(index))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Surface1)
                                        .clickable { desk.playEpisode(item, loaded, episode) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(width = 132.dp, height = 74.dp).clip(RoundedCornerShape(8.dp)).background(Surface2), contentAlignment = Alignment.Center) {
                                        Picture(episode.image, Modifier.fillMaxSize(), side = 300) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TextLow, modifier = Modifier.size(26.dp))
                                        }
                                        if (place != null) Bar(ResumeRules.fraction(place), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${episode.number}. ${TvRepository.episodeTitle(item.name, episode)}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = TextHi,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val under = listOfNotNull(
                                            length(episode.durationSec).takeIf { it.isNotBlank() },
                                            place?.let { "stopped at ${clock(it.positionMs)}" },
                                        ).joinToString("  ·  ")
                                        if (under.isNotBlank()) Text(under, style = MaterialTheme.typography.labelMedium, color = if (place != null) Pink else TextLow)
                                        if (episode.plot.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(episode.plot, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (place != null) {
                                        RoundIcon(Icons.Rounded.Replay, "Start this episode again", { desk.playEpisode(item, loaded, episode, fromStart = true) }, tint = TextMid)
                                    }
                                }
                            }
                        }
                    }
                }
                ListBar(scroll)
            }
        }
    }
}
