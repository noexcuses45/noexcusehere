package com.noexcusehere.mashwave.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.ResumePoint
import com.noexcusehere.mashwave.data.ResumeRules
import com.noexcusehere.mashwave.data.formatDuration
import com.noexcusehere.mashwave.iptv.TvException
import com.noexcusehere.mashwave.iptv.TvRepository
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.ui.CastButton
import com.noexcusehere.mashwave.ui.LocalNav
import com.noexcusehere.mashwave.ui.LocalUi
import com.noexcusehere.mashwave.ui.components.Chip
import com.noexcusehere.mashwave.ui.components.PillButton
import com.noexcusehere.mashwave.ui.components.RemoteImages
import com.noexcusehere.mashwave.ui.components.WebPicture
import com.noexcusehere.mashwave.ui.theme.Amber
import com.noexcusehere.mashwave.ui.theme.BrandGradientHorizontal
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid

/** The page behind a movie or a show: what it is about, and for shows the seasons and episodes. */
@Composable
fun TvDetailScreen() {
    val app = LocalContext.current.app
    val ui = LocalUi.current
    val nav = LocalNav.current
    val item = app.tv.opened
    if (item == null) {
        // The app was closed and reopened on this page: there is nothing to show any more.
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val state by app.tv.state.collectAsStateWithLifecycle()
    val resume by app.store.resume.collectAsStateWithLifecycle()
    var details by remember(item.id) { mutableStateOf(app.tv.cachedDetails(item)) }
    var problem by remember(item.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var season by rememberSaveable(item.id) { mutableIntStateOf(-1) }
    var wholeStory by remember { mutableStateOf(false) }
    val isShow = item.section == TvSection.SHOWS

    LaunchedEffect(item.id, attempt) {
        if (details == null) {
            problem = null
            try {
                details = app.tv.details(item)
            } catch (e: TvException) {
                problem = e.message
            }
        }
    }

    val info = details
    val seasons = info?.seasons.orEmpty()
    // Every episode of the show, to find where the viewer left off.
    val allEpisodes: List<MediaEntry> = remember(info) {
        if (info == null || !isShow) emptyList() else info.seasons.flatMap { app.tv.episodes(item, info, it) }
    }
    val latest = remember(allEpisodes, resume) {
        allEpisodes.mapNotNull { e -> resume[e.uri]?.let { e to it } }.maxByOrNull { it.second.at }
    }
    LaunchedEffect(seasons, latest?.first?.uri) {
        if (season !in seasons && seasons.isNotEmpty()) {
            season = latest?.first?.album?.removePrefix("Season ")?.toIntOrNull()?.takeIf { it in seasons } ?: seasons.first()
        }
    }
    val episodes = remember(info, season) { if (info == null || !isShow || season < 0) emptyList() else app.tv.episodes(item, info, season) }
    val episodeInfo = remember(info, season) { info?.episodes.orEmpty().filter { it.season == season } }

    fun playMovie(fromStart: Boolean) {
        val entry = app.tv.entry(item) ?: return
        if (fromStart) app.store.clearResume(entry.uri)
        app.tv.watched(item)
        ui.play(app.player, listOf(entry), 0)
    }

    fun playEpisode(list: List<MediaEntry>, index: Int) {
        if (index !in list.indices) return
        app.tv.watched(item)
        ui.play(app.player, list, index)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item(key = "top") {
                Box {
                    val backdrop = info?.backdrop.orEmpty().ifBlank { info?.cover.orEmpty().ifBlank { item.logo } }
                    WebPicture(
                        backdrop,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 10f),
                        RoundedCornerShape(0.dp),
                        size = RemoteImages.WIDE,
                        crop = true,
                        seed = item.name,
                        fallback = Icons.Rounded.Movie,
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.55f), Color.Transparent, Ink.copy(alpha = 0.7f), Ink))),
                    )
                }
            }
            item(key = "head") {
                Row(Modifier.padding(horizontal = 16.dp).padding(top = 2.dp)) {
                    WebPicture(
                        info?.cover.orEmpty().ifBlank { item.logo },
                        Modifier.size(width = 104.dp, height = 156.dp),
                        RoundedCornerShape(12.dp),
                        size = RemoteImages.POSTER,
                        crop = true,
                        seed = item.name,
                        fallback = Icons.Rounded.Movie,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.headlineSmall, color = TextHi, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(
                            info?.released?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                            info?.genre?.takeIf { it.isNotBlank() },
                            info?.durationSec?.takeIf { it > 0 }?.let { minutes(it) },
                            if (isShow && seasons.isNotEmpty()) "${seasons.size} ${if (seasons.size == 1) "season" else "seasons"}" else null,
                        )
                        if (facts.isNotEmpty()) {
                            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TextMid, modifier = Modifier.padding(top = 4.dp))
                        }
                        val rating = info?.rating?.toFloatOrNull()?.takeIf { it > 0f } ?: item.rating.takeIf { it > 0f }
                        if (rating != null) {
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Star, contentDescription = null, tint = Amber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("%.1f".format(rating), style = MaterialTheme.typography.labelLarge, color = TextHi)
                            }
                        }
                    }
                }
            }
            item(key = "actions") {
                Column(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!isShow) {
                            val entry = remember(item, info) { app.tv.entry(item) }
                            val point = entry?.let { resume[it.uri] }
                            val from = ResumeRules.startFrom(point)
                            PillButton(
                                if (from > 0) "Carry on from ${formatDuration(from)}" else "Play",
                                { playMovie(fromStart = false) },
                                icon = Icons.Rounded.PlayArrow,
                                filled = true,
                            )
                            if (from > 0) PillButton("Start over", { playMovie(fromStart = true) }, icon = Icons.Rounded.Replay)
                        } else if (latest != null) {
                            val (entry, point) = latest
                            val list = remember(info, entry.album) { info?.let { d -> app.tv.episodes(item, d, entry.album.removePrefix("Season ").toIntOrNull() ?: -1) }.orEmpty() }
                            PillButton(
                                "Carry on ${entry.album.replace("Season ", "S")} E${entry.track} from ${formatDuration(ResumeRules.startFrom(point))}",
                                { playEpisode(list, list.indexOfFirst { it.uri == entry.uri }) },
                                icon = Icons.Rounded.PlayArrow,
                                filled = true,
                            )
                        } else if (episodes.isNotEmpty()) {
                            PillButton("Play S$season E${episodes.first().track}", { playEpisode(episodes, 0) }, icon = Icons.Rounded.PlayArrow, filled = true)
                        }
                        val favourite = state.isFavourite(item)
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Surface2)
                                .clickable { app.tv.toggleFavourite(item) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = if (favourite) "Remove from favourites" else "Add to favourites",
                                tint = if (favourite) Amber else TextHi,
                            )
                        }
                    }
                    if (info == null && problem == null) {
                        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Pink, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(if (isShow) "Getting the episodes..." else "Getting the details...", style = MaterialTheme.typography.bodySmall, color = TextMid)
                        }
                    }
                    problem?.let {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = TextMid, modifier = Modifier.weight(1f))
                            TextButton(onClick = { attempt++ }) { Text("Try again", color = Pink) }
                        }
                    }
                    if (!info?.plot.isNullOrBlank()) {
                        Text(
                            info?.plot.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMid,
                            maxLines = if (wholeStory) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .clickable { wholeStory = !wholeStory },
                        )
                    }
                    if (!info?.cast.isNullOrBlank()) {
                        Text("With ${info?.cast}", style = MaterialTheme.typography.bodySmall, color = TextLow, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (!info?.director.isNullOrBlank()) {
                        Text("Directed by ${info?.director}", style = MaterialTheme.typography.bodySmall, color = TextLow, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            if (isShow && info != null) {
                if (seasons.isEmpty()) {
                    item(key = "none") {
                        Text("The service lists no episodes for this show yet.", style = MaterialTheme.typography.bodyMedium, color = TextMid, modifier = Modifier.padding(16.dp))
                    }
                } else {
                    item(key = "seasons") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                        ) {
                            items(seasons, key = { it }) { s -> Chip("Season $s", s == season, { season = s }) }
                        }
                    }
                    itemsIndexed(episodes, key = { _, it -> it.uri }) { index, entry ->
                        val plain = episodeInfo.getOrNull(index)
                        EpisodeRow(
                            number = entry.track,
                            title = plain?.let { TvRepository.episodeTitle(item.name, it) } ?: entry.title,
                            entry = entry,
                            point = resume[entry.uri],
                        ) { playEpisode(episodes, index) }
                    }
                }
            }
        }
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            CastButton(tint = Color.White)
        }
    }
}

private fun minutes(seconds: Int): String {
    val m = seconds / 60
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

@Composable
private fun EpisodeRow(number: Int, title: String, entry: MediaEntry, point: ResumePoint?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 116.dp, height = 66.dp).clip(RoundedCornerShape(8.dp))) {
            WebPicture(entry.art, Modifier.fillMaxSize(), RoundedCornerShape(0.dp), size = RemoteImages.LOGO, crop = true, seed = entry.title, fallback = Icons.Rounded.Movie)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            val fraction = ResumeRules.fraction(point)
            if (fraction > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction).height(3.dp).background(BrandGradientHorizontal))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$number. $title", style = MaterialTheme.typography.titleSmall, color = TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val notes = listOfNotNull(
                entry.durationMs.takeIf { it > 0 }?.let { minutes((it / 1000).toInt()) },
                point?.let { "stopped at ${formatDuration(it.positionMs)}" },
            )
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = TextMid, maxLines = 1)
        }
    }
}
