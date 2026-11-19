package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.noexcusehere.mashwave.desktop.Desk
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvProgramme
import com.noexcusehere.mashwave.iptv.TvRegions
import com.noexcusehere.mashwave.iptv.TvSearch
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.iptv.isLiveTv
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val GROUP_ALL = "\u0001all"
const val GROUP_FAVOURITES = "\u0001fav"
const val GROUP_RECENT = "\u0001recent"
private const val REGION_OTHER = "\u0001other"

/**
 * Live TV: groups of channels on the left, the channels of the chosen group in the middle, and
 * the channel that is on - with what it is showing now and next - on the right.
 */
@Composable
fun LiveScreen(desk: Desk) {
    val state by desk.tv.state.collectAsState()
    val account = state.active?.id
    LaunchedEffect(account) { desk.tv.setSection(TvSection.LIVE) }
    val shelf = state.shelf(TvSection.LIVE)
    val catalog = shelf.catalog

    var group by rememberSaveable(account) { mutableStateOf(GROUP_ALL) }
    var region by rememberSaveable(account) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(account) { mutableStateOf("") }

    if (catalog == null) {
        when {
            shelf.error != null -> Note(Icons.Rounded.WifiOff, "The channels could not be loaded", shelf.error.orEmpty(), action = "Try again", onAction = { desk.tv.ensure(TvSection.LIVE, force = true) })
            else -> Busy("Loading channels...")
        }
        return
    }

    val counts = remember(catalog) { catalog.items.groupingBy { it.category }.eachCount() }
    val regionOf = remember(catalog) { catalog.categories.associate { it.id to TvRegions.of(it.name)?.key } }
    val regions = remember(catalog) { TvRegions.buttons(catalog.categories, counts, Locale.getDefault().country) }
    val hasOther = remember(catalog) { catalog.categories.any { (counts[it.id] ?: 0) > 0 && regionOf[it.id] == null } }
    val groups = remember(catalog, region) {
        catalog.categories.filter { category ->
            (counts[category.id] ?: 0) > 0 && when (region) {
                null -> true
                REGION_OTHER -> regionOf[category.id] == null
                else -> regionOf[category.id] == region
            }
        }
    }
    val favourites = remember(state.favourites) { state.favourites.filter { it.section == TvSection.LIVE } }
    val recents = remember(state.recents) { state.recents.filter { it.section == TvSection.LIVE } }
    val base = remember(catalog, group, groups, region, favourites, recents) {
        when (group) {
            GROUP_ALL -> if (region == null) catalog.items else groups.flatMap { catalog.byCategory[it.id].orEmpty() }
            GROUP_FAVOURITES -> favourites
            GROUP_RECENT -> recents
            else -> catalog.byCategory[group].orEmpty()
        }
    }
    val channels = remember(base, filter) { if (filter.isBlank()) base else TvSearch.find(base, filter, limit = 600) }

    val player = desk.player
    val onNow = player?.current?.takeIf { it.isLiveTv }
    val onNowItem = remember(onNow) { desk.tv.itemOf(onNow) }

    Row(Modifier.fillMaxSize()) {
        // ---- groups ----------------------------------------------------------------------------
        Column(Modifier.width(248.dp).fillMaxHeight().background(Surface1)) {
            Text("Live TV", style = MaterialTheme.typography.headlineSmall, color = TextHi, modifier = Modifier.padding(start = 18.dp, top = 18.dp, end = 12.dp))
            Text(
                "${catalog.items.size} channels",
                style = MaterialTheme.typography.bodySmall,
                color = TextLow,
                modifier = Modifier.padding(start = 18.dp, bottom = 10.dp),
            )
            if (regions.size >= 2) {
                var choosing by remember { mutableStateOf(false) }
                fun choose(key: String?) {
                    region = key
                    group = GROUP_ALL
                    choosing = false
                }
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .border(1.dp, Outline, RoundedCornerShape(10.dp))
                            .clickable { choosing = true }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Public, contentDescription = null, tint = TextMid, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (region) {
                                null -> "All countries"
                                REGION_OTHER -> "Other"
                                else -> region.orEmpty()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextHi,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose a country", tint = TextMid, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(choosing, { choosing = false }, Modifier.width(224.dp).heightIn(max = 440.dp), containerColor = Surface2) {
                        CountryChoice("All countries", region == null) { choose(null) }
                        for (r in regions) CountryChoice(r.key, region == r.key) { choose(r.key) }
                        if (hasOther) CountryChoice("Other", region == REGION_OTHER) { choose(REGION_OTHER) }
                    }
                }
            }
            val groupList = rememberLazyListState()
            Box(Modifier.weight(1f)) {
                LazyColumn(state = groupList, contentPadding = PaddingValues(bottom = 12.dp)) {
                    item(key = GROUP_ALL) {
                        SideRow(if (region == null) "All channels" else "All in this country", group == GROUP_ALL, { group = GROUP_ALL }, icon = Icons.Rounded.Tv)
                    }
                    item(key = GROUP_FAVOURITES) {
                        SideRow("Favourites", group == GROUP_FAVOURITES, { group = GROUP_FAVOURITES }, count = favourites.size, icon = Icons.Rounded.Favorite)
                    }
                    if (recents.isNotEmpty()) {
                        item(key = GROUP_RECENT) {
                            SideRow("Recently watched", group == GROUP_RECENT, { group = GROUP_RECENT }, icon = Icons.Rounded.History)
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    items(groups) { category ->
                        SideRow(category.name, group == category.id, { group = category.id }, count = counts[category.id])
                    }
                }
                ListBar(groupList)
            }
        }

        // ---- channels --------------------------------------------------------------------------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Field(filter, { filter = it }, "Find a channel in this list", Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 8.dp), icon = Icons.Rounded.Search)
            if (shelf.error != null) {
                Text(
                    "Showing the list from last time: ${shelf.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (channels.isEmpty()) {
                Note(
                    Icons.Rounded.LiveTv,
                    when {
                        filter.isNotBlank() -> "Nothing found"
                        group == GROUP_FAVOURITES -> "No favourites yet"
                        else -> "No channels here"
                    },
                    when {
                        filter.isNotBlank() -> "No channel in this list has \"$filter\" in its name."
                        group == GROUP_FAVOURITES -> "Click the heart next to a channel to keep it here."
                        else -> "This group is empty."
                    },
                )
            } else {
                val list = rememberLazyListState()
                // A different list starts at its top; coming back to the same one keeps its place.
                val showing = "$group|$region|$filter"
                var shownFor by rememberSaveable { mutableStateOf(showing) }
                LaunchedEffect(showing) {
                    if (shownFor != showing) {
                        shownFor = showing
                        list.scrollToItem(0)
                    }
                }
                Box(Modifier.weight(1f)) {
                    LazyColumn(state = list, contentPadding = PaddingValues(start = 8.dp, end = 12.dp, bottom = 12.dp)) {
                        itemsIndexed(channels) { index, item ->
                            ChannelRow(
                                item = item,
                                number = index + 1,
                                playing = onNowItem?.id == item.id,
                                favourite = state.isFavourite(item),
                                onClick = { desk.playChannel(channels, index) },
                                onDouble = { desk.playChannel(channels, index, fill = true) },
                                onFavourite = { desk.tv.toggleFavourite(item) },
                            )
                        }
                    }
                    ListBar(list)
                }
            }
        }

        // ---- what is on ------------------------------------------------------------------------
        Column(Modifier.weight(1.15f).fillMaxHeight().background(Surface1).padding(16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (player != null && onNow != null) {
                    Preview(desk, onExpand = { desk.watching = true })
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = TextLow, modifier = Modifier.size(40.dp))
                        val noPlayer = player == null && !desk.lookingForVlc
                        Text(
                            if (noPlayer) "VLC is needed to play" else "Click a channel to watch it here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextLow,
                        )
                        if (!noPlayer) Text("Double-click to fill the window", style = MaterialTheme.typography.bodySmall, color = TextLow)
                    }
                }
            }
            if (onNow != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(onNow.title, style = MaterialTheme.typography.titleLarge, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(onNow.artist, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    onNowItem?.let { item ->
                        val starred = state.isFavourite(item)
                        RoundIcon(
                            if (starred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (starred) "Remove from favourites" else "Add to favourites",
                            { desk.tv.toggleFavourite(item) },
                            tint = if (starred) Pink else TextMid,
                        )
                    }
                    RoundIcon(Icons.Rounded.OpenInFull, "Fill the window", { desk.watching = true }, tint = TextMid)
                }
                Spacer(Modifier.height(10.dp))
                Guide(desk, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CountryChoice(name: String, chosen: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(name, style = MaterialTheme.typography.bodyMedium, color = if (chosen) Pink else TextHi) },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    item: TvItem,
    number: Int,
    playing: Boolean,
    favourite: Boolean,
    onClick: () -> Unit,
    onDouble: () -> Unit,
    onFavourite: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) Violet.copy(alpha = 0.22f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onDoubleClick = onDouble)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = TextLow, textAlign = TextAlign.End, modifier = Modifier.width(38.dp), maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Picture(
            item.logo,
            Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)).background(Surface2),
            side = 120,
            scale = ContentScale.Fit,
        ) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = TextLow, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(item.name, style = MaterialTheme.typography.bodyLarge, color = if (playing) TextHi else TextHi.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        RoundIcon(
            if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favourite) "Remove from favourites" else "Add to favourites",
            onFavourite,
            size = 32.dp,
            tint = if (favourite) Pink else TextLow,
        )
    }
}

/** The small picture of the channel that is on, on the Live TV screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Preview(desk: Desk, onExpand: () -> Unit) {
    val player = desk.player ?: return
    Box(Modifier.fillMaxSize().combinedClickable(onClick = { player.toggle() }, onDoubleClick = onExpand)) {
        VideoPicture(player, desk.look, Modifier.fillMaxSize())
        when (player.status) {
            PlayStatus.LOADING -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(34.dp))
            PlayStatus.PAUSED -> Icon(Icons.Rounded.PlayArrow, contentDescription = "Paused", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(56.dp))
            PlayStatus.FAILED -> Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(player.problem, style = MaterialTheme.typography.bodyMedium, color = TextHi, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Quiet("Try again", { player.retry() })
            }
            else -> {}
        }
    }
}

private val hourMinute = SimpleDateFormat("h:mm a", Locale.getDefault())

/** What the channel is showing now and next, when its service has a guide. */
@Composable
private fun Guide(desk: Desk, modifier: Modifier = Modifier) {
    val entry = desk.player?.current?.takeIf { it.isLiveTv } ?: return
    var programmes by remember(entry.uri) { mutableStateOf<List<TvProgramme>?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entry.uri) {
        while (true) {
            programmes = desk.tv.guide(entry)
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val list = programmes
    when {
        list == null -> Text("Looking up the guide...", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = modifier)
        list.isEmpty() -> Text("No guide for this channel.", style = MaterialTheme.typography.bodySmall, color = TextLow, modifier = modifier)
        else -> LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(list.take(6)) { index, programme ->
                val current = index == 0 && programme.startMs <= now
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (current) Surface3 else Surface2)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (current) "NOW" else hourMinute.format(Date(programme.startMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (current) Pink else TextLow,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(programme.title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (current) {
                            Text("until " + hourMinute.format(Date(programme.endMs)), style = MaterialTheme.typography.labelSmall, color = TextLow)
                        }
                    }
                    if (current) {
                        Spacer(Modifier.height(8.dp))
                        Bar(programme.progress(now), Modifier.fillMaxWidth())
                    }
                    if (programme.description.isNotBlank() && index < 2) {
                        Spacer(Modifier.height(6.dp))
                        Text(programme.description, style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = if (current) 4 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
