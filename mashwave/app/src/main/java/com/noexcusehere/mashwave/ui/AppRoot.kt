package com.noexcusehere.mashwave.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.playback.PlayerUiState
import com.noexcusehere.mashwave.ui.components.Artwork
import com.noexcusehere.mashwave.ui.components.ArtworkLoader
import com.noexcusehere.mashwave.ui.components.rememberAccent
import com.noexcusehere.mashwave.ui.screens.CollectionScreen
import com.noexcusehere.mashwave.ui.screens.EditorHomeScreen
import com.noexcusehere.mashwave.ui.screens.EditorScreen
import com.noexcusehere.mashwave.ui.screens.EqualizerScreen
import com.noexcusehere.mashwave.ui.screens.HomeScreen
import com.noexcusehere.mashwave.ui.screens.LibraryScreen
import com.noexcusehere.mashwave.ui.screens.MashupScreen
import com.noexcusehere.mashwave.ui.screens.NowPlayingScreen
import com.noexcusehere.mashwave.ui.screens.PlaylistsScreen
import com.noexcusehere.mashwave.ui.screens.SearchScreen
import com.noexcusehere.mashwave.ui.screens.VideoPlayerScreen
import com.noexcusehere.mashwave.ui.screens.VideosScreen
import com.noexcusehere.mashwave.ui.screens.WelcomeScreen
import com.noexcusehere.mashwave.ui.theme.BrandGradientHorizontal
import com.noexcusehere.mashwave.ui.theme.Ink
import com.noexcusehere.mashwave.ui.theme.Pink
import com.noexcusehere.mashwave.ui.theme.Surface2
import com.noexcusehere.mashwave.ui.theme.Surface3
import com.noexcusehere.mashwave.ui.theme.TextHi
import com.noexcusehere.mashwave.ui.theme.TextLow
import com.noexcusehere.mashwave.ui.theme.TextMid

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Rounded.Home),
    Tab(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    Tab(Routes.VIDEOS, "Videos", Icons.Rounded.VideoLibrary),
    Tab(Routes.MASHUP, "Mashup", Icons.Rounded.GraphicEq),
    Tab(Routes.EDITOR, "Edit", Icons.Rounded.MovieCreation),
    Tab(Routes.PLAYLISTS, "Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
)

fun mediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
    arrayOf(
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.POST_NOTIFICATIONS,
    )
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun AppRoot(ui: UiState) {
    val app = LocalContext.current.app
    val nav = rememberNavController()
    val player by app.player.state.collectAsStateWithLifecycle()
    val cast by app.cast.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // While a TV is connected, whatever the user starts plays there instead of on the phone.
    ui.castRoute = { entries, index ->
        if (app.cast.isConnected) {
            app.player.pause()
            app.cast.play(entries, index)
            true
        } else {
            false
        }
    }
    LaunchedEffect(cast.device?.id) {
        if (cast.device != null) {
            ui.videoFullscreen = false
            ui.nowPlayingOpen = false
        }
    }

    var hasPermission by remember { mutableStateOf(app.library.hasAudioPermission()) }
    var skippedWelcome by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = app.library.hasAudioPermission()
        app.store.setOnboarded()
        app.library.refresh()
    }
    // Picks up a permission granted from the system Settings screen, and new files.
    LifecycleResumeEffect(Unit) {
        hasPermission = app.library.hasAudioPermission()
        if (hasPermission) app.library.refresh()
        ArtworkLoader.forget()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        app.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }
    LaunchedEffect(ui.pendingTab) {
        ui.pendingTab?.let {
            nav.openTab(it)
            ui.pendingTab = null
        }
    }

    CompositionLocalProvider(LocalUi provides ui, LocalNav provides nav) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink),
        ) {
            if (!hasPermission && !skippedWelcome) {
                WelcomeScreen(
                    onAllow = { permissionLauncher.launch(mediaPermissions()) },
                    onSkip = { skippedWelcome = true },
                    askedBefore = app.store.data.collectAsStateWithLifecycle().value.onboarded,
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        NavHost(
                            navController = nav,
                            startDestination = Routes.HOME,
                            enterTransition = { fadeIn(tween(180)) },
                            exitTransition = { fadeOut(tween(120)) },
                            popEnterTransition = { fadeIn(tween(180)) },
                            popExitTransition = { fadeOut(tween(120)) },
                        ) {
                            composable(Routes.HOME) { HomeScreen() }
                            composable(Routes.LIBRARY) { LibraryScreen() }
                            composable(Routes.VIDEOS) { VideosScreen() }
                            composable(Routes.MASHUP) { MashupScreen() }
                            composable(Routes.PLAYLISTS) { PlaylistsScreen() }
                            composable(Routes.EDITOR) { EditorHomeScreen() }
                            composable(Routes.SEARCH) { SearchScreen() }
                            composable(Routes.EQUALIZER) { EqualizerScreen() }
                            composable(
                                Routes.COLLECTION,
                                arguments = listOf(
                                    navArgument("type") { type = NavType.StringType },
                                    navArgument("key") { type = NavType.StringType },
                                ),
                            ) { entry ->
                                CollectionScreen(
                                    type = entry.arguments?.getString("type") ?: "liked",
                                    key = entry.arguments?.getString("key") ?: "-",
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = cast.device != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        CastBar(cast, onOpen = { ui.castOpen = true })
                    }
                    AnimatedVisibility(
                        // While a TV is connected its bar replaces the mini player, unless the phone itself is playing.
                        visible = player.current != null && (cast.device == null || player.isPlaying),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        MiniPlayer(
                            state = player,
                            onOpen = { if (player.hasVideo) ui.videoFullscreen = true else ui.nowPlayingOpen = true },
                            onToggle = { app.player.togglePlay() },
                            onNext = { app.player.next() },
                        )
                    }
                    BottomBar(
                        current = nav.currentBackStackEntryAsState().value?.destination?.route,
                        onSelect = { nav.openTab(it) },
                    )
                }

                AnimatedVisibility(
                    visible = ui.nowPlayingOpen && !ui.videoFullscreen && player.current != null,
                    enter = slideInVertically(tween(280)) { it } + fadeIn(tween(160)),
                    exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(200)),
                ) {
                    NowPlayingScreen()
                }

                if (ui.videoFullscreen) {
                    VideoPlayerScreen()
                }

                ui.menuEntry?.let { entry ->
                    TrackMenuSheet(entry, ui.menuPlaylistId) { ui.menuEntry = null }
                }
                ui.addToPlaylist?.let { entries ->
                    AddToPlaylistSheet(entries) { ui.addToPlaylist = null }
                }
                if (ui.queueOpen) {
                    QueueSheet { ui.queueOpen = false }
                }
                if (ui.streamDialogOpen) {
                    OpenStreamDialog { ui.streamDialogOpen = false }
                }

                // The video editor covers everything else while a project is open.
                ui.editingProjectId?.let { id ->
                    EditorScreen(id, onClose = { ui.editingProjectId = null })
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 132.dp),
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = Surface3,
                        contentColor = TextHi,
                        shape = RoundedCornerShape(14.dp),
                    )
                }

                if (ui.castOpen) {
                    CastSheet { ui.castOpen = false }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    // Detail pages keep the tab they were opened from highlighted.
    var selected by rememberSaveable { mutableStateOf(Routes.HOME) }
    if (current != null && tabs.any { it.route == current }) selected = current

    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xF007070C), Color(0xFF07070C))))
            .navigationBarsPadding()
            .height(62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSelected = tab.route == selected
            Column(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(tab.route) },
                    )
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    tint = if (isSelected) Pink else TextLow,
                    modifier = Modifier.size(25.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) TextHi else TextLow,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlayerUiState, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val entry = state.current ?: return
    val app = LocalContext.current.app
    val accent = rememberAccent(entry)
    val position by rememberPlaybackPosition()
    val duration = app.player.durationMs
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(60.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.08f))))
            .drawBehind {
                // Thin progress line along the bottom edge.
                val h = 2.5.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.12f), Offset(0f, size.height - h), Size(size.width, h))
                drawRect(BrandGradientHorizontal, Offset(0f, size.height - h), Size(size.width * progress, h))
            }
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            entry,
            if (entry.isVideo) Modifier.size(width = 70.dp, height = 44.dp) else Modifier.size(44.dp),
            RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = TextHi,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = TextHi,
                modifier = Modifier.size(30.dp),
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = TextHi, modifier = Modifier.size(28.dp))
        }
    }
}
