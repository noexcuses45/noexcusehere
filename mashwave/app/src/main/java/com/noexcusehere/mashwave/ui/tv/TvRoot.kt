package com.noexcusehere.mashwave.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noexcusehere.mashwave.R
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.iptv.TvItem
import com.noexcusehere.mashwave.iptv.TvSection
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Outline
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TvPage(val title: String, val icon: ImageVector) {
    LIVE("Live TV", Icons.Rounded.LiveTv),
    MOVIES("Movies", Icons.Rounded.Movie),
    SHOWS("Shows", Icons.Rounded.VideoLibrary),
    SEARCH("Search", Icons.Rounded.Search),
    SAVED("Favourites", Icons.Rounded.Favorite),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

const val TV_GROUP_ALL = "\u0001all"
const val TV_GROUP_FAVOURITES = "\u0001fav"
const val TV_GROUP_RECENT = "\u0001recent"
const val TV_REGION_OTHER = "\u0001other"

/**
 * What the TV layout remembers while the app is open: which screen is showing, which group was
 * chosen and how far each list was scrolled - so that coming back from a film or a channel
 * lands the remote where it was.
 */
class TvUi {
    var page by mutableStateOf(TvPage.LIVE)

    /** The movie or show whose page is open. */
    var details by mutableStateOf<TvItem?>(null)

    /** True while the player fills the screen. */
    var playerOpen by mutableStateOf(false)

    /** True while the sign-in screen is open. */
    var setupOpen by mutableStateOf(false)
    var message by mutableStateOf("")

    var liveGroup by mutableStateOf(TV_GROUP_ALL)
    var liveRegion by mutableStateOf<String?>(null)
    val liveGroups = LazyListState()
    val liveChannels = LazyListState()

    /** The channel row to put the remote back on (-1: none). */
    var liveReturn by mutableStateOf(-1)

    val vodGroup = mutableStateMapOf<TvSection, String>()
    val vodGroups = mapOf(TvSection.MOVIES to LazyListState(), TvSection.SHOWS to LazyListState())
    val vodGrids = mapOf(TvSection.MOVIES to LazyGridState(), TvSection.SHOWS to LazyGridState())
    val vodReturn = mutableStateMapOf<TvSection, Int>()

    var query by mutableStateOf("")

    /** Bumped whenever the remote should go back to the side menu. */
    var toMenu by mutableStateOf(0)

    /** True while the remote is on the side menu (Back then leaves the app). */
    var inMenu by mutableStateOf(true)

    /** True when the remote is on nothing at all. */
    var adrift by mutableStateOf(false)
}

/** The whole app as it looks on a TV stick or box. */
@Composable
fun TvRoot() {
    val app = LocalContext.current.app
    val ui = remember { TvUi() }
    val state by app.tv.state.collectAsStateWithLifecycle()

    // Short messages from the rest of the app ("this channel is not answering...").
    LaunchedEffect(Unit) {
        app.messages.collect { text -> ui.message = text }
    }
    LaunchedEffect(ui.message) {
        if (ui.message.isNotEmpty()) {
            delay(5000)
            ui.message = ""
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        when {
            ui.playerOpen -> TvPlayer(ui)
            ui.setupOpen -> TvSignIn(ui)
            ui.details != null -> ui.details?.let { TvDetails(ui, it) }
            state.active == null && ui.page != TvPage.SETTINGS -> TvWelcome(ui)
            else -> Row(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { ui.adrift = !it.hasFocus },
            ) {
                // Back from inside a screen puts the remote on the side menu; Back on the menu leaves the app.
                BackHandler(!ui.inMenu) { ui.toMenu++ }
                // If the thing the remote was on disappears (a list changed under it), the remote
                // would be nowhere and the arrows would seem dead: put it back on the menu.
                LaunchedEffect(ui.adrift) {
                    if (ui.adrift) {
                        delay(700)
                        if (ui.adrift) ui.toMenu++
                    }
                }
                Rail(ui)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (ui.page) {
                        TvPage.LIVE -> TvLive(ui)
                        TvPage.MOVIES -> TvVod(ui, TvSection.MOVIES)
                        TvPage.SHOWS -> TvVod(ui, TvSection.SHOWS)
                        TvPage.SEARCH -> TvSearch(ui)
                        TvPage.SAVED -> TvSaved(ui)
                        TvPage.SETTINGS -> TvSettings(ui)
                    }
                }
            }
        }

        if (ui.message.isNotEmpty()) {
            Text(
                ui.message,
                style = MaterialTheme.typography.titleMedium,
                color = TextHi,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp, start = 40.dp, end = 40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface3)
                    .border(1.dp, Outline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** The menu down the left side. Moving the remote over an entry opens it. */
@Composable
private fun Rail(ui: TvUi) {
    val app = LocalContext.current.app
    val state by app.tv.state.collectAsStateWithLifecycle()
    val requesters = remember { TvPage.entries.associateWith { FocusRequester() } }
    // When the app opens, and whenever a screen hands the remote back, it rests on the current entry.
    LandOn(requesters.getValue(ui.page), ui.toMenu)
    val scope = rememberCoroutineScope()
    // True while the remote is moving up and down the menu itself.
    val along = remember { booleanArrayOf(false) }
    Column(
        Modifier
            .width(176.dp)
            .fillMaxHeight()
            .background(Color(0xFF0C0C13))
            .onFocusChanged {
                ui.inMenu = it.hasFocus
                if (!it.hasFocus) along[0] = false
            }
            // Coming back to the menu from a screen, the remote rests on the entry of that screen.
            .focusRestorer(requesters.getValue(ui.page))
            .focusGroup()
            .padding(vertical = 18.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
            Spacer(Modifier.width(10.dp))
            Text("Ultimate\nMedia Manager", style = MaterialTheme.typography.labelLarge, color = TextHi, maxLines = 2)
        }
        Spacer(Modifier.height(18.dp))
        for (page in TvPage.entries) {
            if (page == TvPage.SETTINGS) Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .remote({ ui.page = page }, chosen = ui.page == page, requester = requesters.getValue(page), onLanded = {
                        if (along[0] || page == ui.page) {
                            ui.page = page
                        } else {
                            // The remote arrived from somewhere else (a screen, or a button that
                            // has just gone away) and happened to touch this entry: that must not
                            // change the screen. Put it on the entry of the screen that is showing.
                            scope.launch { runCatching { requesters.getValue(ui.page).requestFocus() } }
                        }
                        along[0] = true
                    })
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(page.icon, contentDescription = null, tint = TextHi, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(page.title, style = MaterialTheme.typography.titleMedium, color = TextHi, maxLines = 1)
            }
        }
        state.active?.let { account ->
            Text(
                account.name,
                style = MaterialTheme.typography.labelSmall,
                color = TextLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 22.dp, end = 12.dp, top = 6.dp),
            )
        }
    }
}
