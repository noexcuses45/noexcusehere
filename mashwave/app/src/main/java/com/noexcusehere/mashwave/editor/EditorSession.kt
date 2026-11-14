package com.noexcusehere.mashwave.editor

import android.content.Context
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer

/** One open project: what it looks like now, where the playhead is, and the undo history. */
class EditorSession(private val store: EditorStore, initial: EditProject) {
    var project by mutableStateOf(initial)
        private set

    /** Playhead, in finished-video time. */
    var positionMs by mutableLongStateOf(0L)

    /** Goes up whenever the project changes, so the preview knows to rebuild. */
    var revision by mutableIntStateOf(0)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<EditProject>()
    private val redoStack = ArrayDeque<EditProject>()

    val currentIndex: Int
        get() = if (project.clips.isEmpty()) -1 else project.clipAt(positionMs)?.first ?: -1

    val currentClip: EditClip?
        get() = project.clips.getOrNull(currentIndex)

    /** Applies an edit and remembers the previous state so it can be undone. */
    fun change(next: EditProject) {
        if (next == project) return
        undoStack.addLast(project)
        while (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        set(next)
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(project)
        set(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(project)
        set(next)
    }

    /** Renaming is not worth an undo step. */
    fun rename(name: String) {
        project = project.copy(name = name)
        store.save(project)
    }

    private fun set(next: EditProject) {
        project = next
        positionMs = positionMs.coerceIn(0, next.durationMs.coerceAtLeast(0))
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        revision++
        store.save(next)
    }
}

/** Plays the project as it will look when saved, straight from the original files. */
class EditorPreview(private val context: Context) {
    private var player: CompositionPlayer? = null
    private var surface: SurfaceView? = null
    private var durationMs = 0L

    var isPlaying by mutableStateOf(false)
        private set

    /** True from the first frame onwards. */
    var hasPicture by mutableStateOf(false)
        private set

    /** Set when the phone cannot play the preview; saving the video may still work. */
    var problem by mutableStateOf<String?>(null)
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onRenderedFirstFrame() {
            hasPicture = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) player?.pause()
        }

        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.w("UltimateBeats", "Preview stopped", error)
            problem = "The preview stopped (${error.errorCodeName}). You can keep editing and still save the video."
            dropPlayer()
        }
    }

    fun attach(view: SurfaceView) {
        surface = view
        runCatching { player?.setVideoSurfaceView(view) }
    }

    fun detach(view: SurfaceView) {
        if (surface === view) {
            runCatching { player?.clearVideoSurfaceView(view) }
            surface = null
        }
    }

    /**
     * Loads [project] and shows the frame at [positionMs]. Safe to call after every edit.
     *
     * Each call starts a fresh player. Handing a second project to a player that is already
     * running trips over itself inside the video engine, so the old one is shut down first.
     */
    fun show(project: EditProject, positionMs: Long) {
        durationMs = project.durationMs
        val wasPlaying = player?.playWhenReady == true
        dropPlayer()
        if (project.clips.isEmpty()) {
            hasPicture = false
            return
        }
        try {
            val composition = CompositionFactory.build(context, project, PREVIEW_SHORT_SIDE, forExport = false)
            val created = CompositionPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    true,
                )
                .build()
            player = created
            created.addListener(listener)
            surface?.let { created.setVideoSurfaceView(it) }
            created.setComposition(composition, positionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0)))
            created.prepare()
            created.playWhenReady = wasPlaying
            problem = null
        } catch (e: Exception) {
            android.util.Log.w("UltimateBeats", "Preview could not be built", e)
            problem = "The preview can't show this yet (${e.message ?: e.javaClass.simpleName})."
            dropPlayer()
        }
    }

    val positionMs: Long
        get() = runCatching { player?.currentPosition }.getOrNull()?.coerceIn(0, durationMs.coerceAtLeast(0)) ?: 0

    val atEnd: Boolean
        get() = player?.playbackState == Player.STATE_ENDED || (durationMs > 0 && positionMs >= durationMs - 60)

    fun play() {
        val target = player ?: return
        runCatching {
            if (atEnd) target.seekTo(0)
            target.play()
        }
    }

    fun pause() {
        runCatching { player?.pause() }
    }

    fun seekTo(positionMs: Long) {
        runCatching { player?.seekTo(positionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))) }
    }

    /** While a finger drags the timeline, favour quick rough frames over exact ones. */
    fun setScrubbing(on: Boolean) {
        runCatching { player?.setScrubbingModeEnabled(on) }
    }

    private fun dropPlayer() {
        val old = player ?: return
        player = null
        isPlaying = false
        runCatching { old.removeListener(listener) }
        // Let go of the screen first so the next player can draw on it.
        surface?.let { view -> runCatching { old.clearVideoSurfaceView(view) } }
        runCatching { old.release() }
    }

    fun release() {
        dropPlayer()
        surface = null
    }

    companion object {
        /** The preview is drawn smaller than the saved video so editing stays smooth. */
        const val PREVIEW_SHORT_SIDE = 540
    }
}
