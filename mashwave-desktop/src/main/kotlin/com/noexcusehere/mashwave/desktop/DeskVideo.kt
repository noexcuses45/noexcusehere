package com.noexcusehere.mashwave.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.desktop.video.Ffmpeg
import com.noexcusehere.mashwave.desktop.video.RenderPlan
import com.noexcusehere.mashwave.desktop.video.TextPictures
import com.noexcusehere.mashwave.desktop.video.VideoMaths
import com.noexcusehere.mashwave.desktop.vlc.PlayStatus
import com.noexcusehere.mashwave.desktop.vlc.VlcPlayer
import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.editor.EditMusic
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditText
import com.noexcusehere.mashwave.editor.EditorOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

@Serializable
private data class VideoShelf(val projects: List<EditProject> = emptyList(), val silent: List<String> = emptyList())

/** The video projects kept on this computer, newest first. */
class VideoProjects(dataDir: File, scope: CoroutineScope) {
    private val file = File(dataDir, "videos.json")
    private val writes = Channel<VideoShelf>(Channel.CONFLATED)

    var projects by mutableStateOf(emptyList<EditProject>())
        private set

    /** Addresses of video files found to have no sound of their own. */
    var silent = emptySet<String>()
        private set

    init {
        val kept = runCatching { AppJson.decodeFromString(VideoShelf.serializer(), file.readText()) }.getOrDefault(VideoShelf())
        projects = kept.projects.sortedByDescending { it.updatedAt }
        silent = kept.silent.toSet()
        scope.launch(Dispatchers.IO) {
            for (shelf in writes) {
                runCatching {
                    val temp = File(file.parentFile, file.name + ".tmp")
                    temp.writeText(AppJson.encodeToString(VideoShelf.serializer(), shelf))
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun write() {
        writes.trySend(VideoShelf(projects, silent.toList()))
    }

    /** Keeps [project] (new, or in place of the one with the same id). */
    fun keep(project: EditProject) {
        projects = (listOf(project) + projects.filterNot { it.id == project.id })
        write()
    }

    fun remove(id: String) {
        projects = projects.filterNot { it.id == id }
        write()
    }

    fun hasNoSound(uri: String) {
        if (uri in silent) return
        silent = silent + uri
        write()
    }
}

/** How making the finished video is going. */
class ExportState(val progress: Float, val done: File? = null, val problem: String = "")

/**
 * The video editor while one project is open: the project and its undo steps, the clip chosen,
 * the playhead and the frame under it, preview playback, and making the finished video.
 */
class VideoEditing(private val desk: Desk, val engine: Ffmpeg, start: EditProject, private val scope: CoroutineScope) {
    var project by mutableStateOf(start)
        private set

    private val undone = ArrayDeque<EditProject>()
    private val redone = ArrayDeque<EditProject>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** The clip being worked on, by id. */
    var selected by mutableStateOf(start.clips.firstOrNull()?.id)

    /** The caption being worked on, by id. */
    var selectedText by mutableStateOf<String?>(null)

    /** Where in the finished video the playhead is, in milliseconds. */
    var playhead by mutableLongStateOf(0L)
        private set

    /** The finished video as it looks under the playhead. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /** True while clips are being read in. */
    var adding by mutableStateOf(false)
        private set

    var playing by mutableStateOf(false)
        private set

    /** How far along getting the preview ready is (0 to 1); null when it is not being made. */
    var preparing by mutableStateOf<Float?>(null)
        private set

    /** Set while the finished video is being made, and afterwards until the box is closed. */
    var export by mutableStateOf<ExportState?>(null)

    /** Plays the preview; made the first time it is needed. */
    var player by mutableStateOf<VlcPlayer?>(null)
        private set

    private val work = File(desk.dataDir, "video-work").apply { mkdirs() }
    private val texts = File(work, "texts")
    private var frameJob: Job? = null
    private var playJob: Job? = null
    private var exportJob: Job? = null
    private var tweaking: String? = null

    val clip: EditClip? get() = project.clips.firstOrNull { it.id == selected }
    val text: EditText? get() = project.texts.firstOrNull { it.id == selectedText }

    init {
        showFrame()
    }

    // ---- changes ---------------------------------------------------------------------------

    /**
     * Makes [next] the project. A run of small changes with the same [tweak] name (a slider
     * being dragged) counts as one step for "undo"; [settle] ends the run.
     */
    fun change(next: EditProject, tweak: String? = null) {
        if (next == project) return
        stopPlaying()
        if (tweak == null || tweak != tweaking) {
            undone.addLast(project)
            while (undone.size > 60) undone.removeFirst()
            redone.clear()
        }
        tweaking = tweak
        apply(next)
    }

    fun settle() {
        tweaking = null
    }

    private fun apply(next: EditProject) {
        project = next.copy(updatedAt = System.currentTimeMillis())
        desk.videos.keep(project)
        canUndo = undone.isNotEmpty()
        canRedo = redone.isNotEmpty()
        if (selected != null && project.clips.none { it.id == selected }) selected = project.clips.firstOrNull()?.id
        if (selectedText != null && project.texts.none { it.id == selectedText }) selectedText = null
        playhead = playhead.coerceIn(0, project.durationMs)
        showFrame()
    }

    fun undo() {
        val back = undone.removeLastOrNull() ?: return
        stopPlaying()
        tweaking = null
        redone.addLast(project)
        apply(back)
    }

    fun redo() {
        val forward = redone.removeLastOrNull() ?: return
        stopPlaying()
        tweaking = null
        undone.addLast(project)
        apply(forward)
    }

    fun rename(name: String) = change(project.copy(name = name.trim().take(80).ifEmpty { "My video" }), "name")

    fun update(id: String, tweak: String? = null, how: (EditClip) -> EditClip) = change(EditorOps.updateClip(project, id, how), tweak?.let { "$it:$id" })

    fun trim(id: String, startMs: Long, endMs: Long, tweak: String) = change(EditorOps.trim(project, id, startMs, endMs), "$tweak:$id")

    fun remove(id: String) = change(EditorOps.removeClip(project, id))

    fun duplicate(id: String) {
        val copy = UUID.randomUUID().toString()
        change(EditorOps.duplicateClip(project, id, copy))
        selected = copy
    }

    fun move(id: String, by: Int) = change(EditorOps.moveClip(project, id, by))

    /** Cuts the clip under the playhead in two there. */
    fun split() {
        val cut = EditorOps.splitAt(project, playhead, UUID.randomUUID().toString())
        if (cut == null) desk.say("Move the line a little further from the start or end of the clip, then cut.") else change(cut)
    }

    fun addText() {
        val total = project.durationMs
        val from = playhead.coerceIn(0, (total - EditorOps.MIN_TEXT_MS).coerceAtLeast(0))
        val item = EditText(id = UUID.randomUUID().toString(), text = "Your text", startMs = from, endMs = (from + 3000).coerceAtMost(maxOf(total, from + EditorOps.MIN_TEXT_MS)))
        change(EditorOps.addText(project, item))
        selectedText = item.id
    }

    fun updateText(id: String, tweak: String? = null, how: (EditText) -> EditText) = change(EditorOps.updateText(project, id, how), tweak?.let { "$it:$id" })

    fun removeText(id: String) = change(EditorOps.removeText(project, id))

    fun music(tweak: String? = null, how: (EditMusic) -> EditMusic) {
        val now = project.music ?: return
        change(EditorOps.setMusic(project, how(now)), tweak)
    }

    fun noMusic() = change(EditorOps.setMusic(project, null))

    /** Reads [files] in and puts them on the end of the video. Photos show for three seconds to begin with. */
    fun addFiles(files: List<File>) {
        if (adding || files.isEmpty()) return
        adding = true
        scope.launch {
            val clips = withContext(Dispatchers.IO) { files.mapNotNull { describe(it) } }
            adding = false
            if (clips.isEmpty()) {
                desk.say(if (files.size == 1) "That file is not a video or a photo the editor can read." else "None of those files is a video or a photo the editor can read.")
                return@launch
            }
            if (clips.size < files.size) desk.say("${files.size - clips.size} of the files could not be read and were left out.")
            change(EditorOps.addClips(project, clips))
            if (selected == null) selected = clips.first().id
        }
    }

    private fun describe(file: File): EditClip? {
        val id = UUID.randomUUID().toString()
        val title = Library.tidyName(file.nameWithoutExtension)
        if (PhotoFiles.isPicture(file.name)) {
            // A photo stored lying down (cameras do that, with a note saying how to hold it) is stood
            // upright in a copy of the app's own, so that every video engine shows it the same way.
            if (PhotoFiles.isTurned(file)) {
                val upright = runCatching { PhotoFiles.read(file) }.getOrNull() ?: return null
                val key = MessageDigest.getInstance("SHA-1").digest("${file.absolutePath}|${file.length()}|${file.lastModified()}".toByteArray()).joinToString("") { "%02x".format(it) }
                val copy = File(File(work, "photos").apply { mkdirs() }, "$key.jpg")
                if (!copy.isFile || copy.length() == 0L) copy.outputStream().use { upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                val clip = EditClip(id = id, uri = copy.absolutePath, title = title, isImage = true, startMs = 0, endMs = DEFAULT_PHOTO_MS, width = upright.width, height = upright.height, original = file.absolutePath)
                upright.recycle()
                return clip
            }
            val facts = runCatching { engine.facts(file) }.getOrNull() ?: return null
            if (!facts.hasPicture || facts.width <= 0 || facts.height <= 0) return null
            return EditClip(id = id, uri = file.absolutePath, title = title, isImage = true, startMs = 0, endMs = DEFAULT_PHOTO_MS, width = facts.width, height = facts.height)
        }
        val facts = runCatching { engine.facts(file) }.getOrNull() ?: return null
        if (!facts.hasPicture || facts.width <= 0 || facts.height <= 0) return null
        if (facts.durationMs < EditorOps.MIN_PIECE_MS) return null
        if (!facts.hasSound) desk.videos.hasNoSound(file.absolutePath)
        return EditClip(id = id, uri = file.absolutePath, title = title, sourceDurationMs = facts.durationMs, startMs = 0, endMs = facts.durationMs, width = facts.width, height = facts.height)
    }

    /** Puts the song in [file] under the video. */
    fun chooseMusic(file: File) {
        scope.launch {
            val facts = withContext(Dispatchers.IO) { runCatching { engine.facts(file) }.getOrNull() }
            if (facts == null || !facts.hasSound) {
                desk.say("That file has no sound the editor can read.")
                return@launch
            }
            val entry = MediaEntry(uri = file.absolutePath, title = Library.tidyName(file.nameWithoutExtension), durationMs = facts.durationMs)
            change(EditorOps.setMusic(project, EditMusic(entry)))
        }
    }

    // ---- the frame under the playhead --------------------------------------------------------

    /** Moves the playhead to [ms] and shows the video as it looks there. */
    fun seek(ms: Long) {
        val to = ms.coerceIn(0, project.durationMs)
        if (playing) {
            player?.seekTo(to)
            playhead = to
            return
        }
        playhead = to
        project.clipAt(to)?.let { (index, _) -> selected = project.clips[index].id }
        showFrame()
    }

    private fun showFrame() {
        frameJob?.cancel()
        if (project.clips.isEmpty()) {
            frame = null
            return
        }
        val now = project
        val at = playhead
        frameJob = scope.launch {
            // A slider being dragged asks many times a second; only the last asking is answered.
            delay(90)
            val (w, h) = VideoMaths.outputSize(now, STILL_SHORT_SIDE)
            val made = withContext(Dispatchers.IO) {
                val showing = TextPictures.make(now, w, h, texts) { at >= it.startMs && at < it.endMs }
                val args = RenderPlan.still(now, at, w, h, showing) ?: return@withContext null
                engine.picture(args)?.let { bytes -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() } }.getOrNull() }
            }
            if (isActive && made != null) frame = made
        }
    }

    // ---- preview playback --------------------------------------------------------------------

    private fun fingerprint(of: EditProject): String {
        val what = AppJson.encodeToString(EditProject.serializer(), of.copy(name = "", updatedAt = 0))
        return MessageDigest.getInstance("SHA-1").digest(what.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }

    /** Plays the video from the playhead. The first time after a change, a quick small copy is made to play. */
    fun play() {
        if (playing || preparing != null || project.clips.isEmpty()) return
        val now = project
        if (playhead >= now.durationMs - 150) playhead = 0
        playJob = scope.launch {
            val draft = File(work, "preview-${now.id.take(8)}-${fingerprint(now)}.mp4")
            if (!draft.isFile || draft.length() == 0L) {
                preparing = 0f
                // Earlier previews of this project are of no use any more.
                work.listFiles { f -> f.name.startsWith("preview-${now.id.take(8)}-") }?.forEach { it.delete() }
                val (w, h) = VideoMaths.outputSize(now, PREVIEW_SHORT_SIDE)
                val part = File(work, draft.name + ".part.mp4")
                val problem = try {
                    val pictures = withContext(Dispatchers.IO) { TextPictures.make(now, w, h, texts) }
                    val encoder = if ("libx264" in engine.encoders) "libx264" else "mpeg4"
                    engine.render(RenderPlan.whole(now, w, h, pictures, encoder, true, part.absolutePath, desk.videos.silent)) { preparing = it }
                } finally {
                    preparing = null
                }
                if (problem != null) {
                    part.delete()
                    System.err.println("the preview could not be made: $problem")
                    desk.say("The preview could not be made. One of the clips may be damaged or missing.")
                    return@launch
                }
                withContext(Dispatchers.IO) { Files.move(part.toPath(), draft.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
            val shows = player ?: desk.spareNewPlayer()?.also { player = it }
            if (shows == null) {
                desk.say("The preview needs the video player, which could not be started.")
                return@launch
            }
            // Only one thing makes a sound at a time.
            desk.player?.pause()
            shows.play(listOf(MediaEntry(uri = draft.absolutePath, title = now.name, isVideo = true, durationMs = now.durationMs)), 0, startMs = playhead)
            playing = true
            var started = false
            while (isActive && playing) {
                delay(60)
                when (shows.status) {
                    PlayStatus.PLAYING -> {
                        started = true
                        playhead = shows.positionMs.coerceIn(0, project.durationMs)
                    }
                    PlayStatus.ENDED, PlayStatus.IDLE, PlayStatus.FAILED -> if (started || shows.status == PlayStatus.FAILED) {
                        if (shows.status == PlayStatus.ENDED) playhead = project.durationMs
                        break
                    }
                    else -> Unit
                }
            }
            playing = false
            showFrame()
        }
    }

    fun pause() {
        if (!playing) {
            playJob?.cancel()
            return
        }
        player?.pause()
        playing = false
        showFrame()
    }

    private fun stopPlaying() {
        if (playing) player?.pause()
        playing = false
        playJob?.cancel()
        preparing = null
    }

    // ---- the finished video ------------------------------------------------------------------

    /** Makes the finished video, its shorter side [shortSide] dots, in the videos folder. */
    fun make(shortSide: Int, into: File = savedFolder()) {
        if (exportJob?.isActive == true || project.clips.isEmpty()) return
        stopPlaying()
        val now = project
        export = ExportState(0f)
        exportJob = scope.launch {
            val (w, h) = VideoMaths.outputSize(now, shortSide)
            into.mkdirs()
            val target = AppFolders.freeFile(into, now.name, "mp4", "My video")
            val part = File(into, target.nameWithoutExtension + ".part.mp4")
            var problem: String? = "no way of packing the video was found"
            try {
                val pictures = withContext(Dispatchers.IO) { TextPictures.make(now, w, h, texts) }
                // The best way of packing first; if this computer cannot do it, the next one.
                for (encoder in engine.videoEncoders()) {
                    problem = engine.render(RenderPlan.whole(now, w, h, pictures, encoder, false, part.absolutePath, desk.videos.silent)) { export = ExportState(it) }
                    if (problem == null) break
                    System.err.println("making the video with $encoder did not work: $problem")
                }
            } finally {
                if (!isActive) part.delete()
            }
            if (problem == null && part.length() > 0) {
                withContext(Dispatchers.IO) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                export = ExportState(1f, done = target)
                desk.lookForMediaAgain()
            } else {
                part.delete()
                export = ExportState(0f, problem = "The video could not be made. One of the clips may be damaged or missing, or the disk may be full.")
            }
        }
    }

    fun cancelMaking() {
        exportJob?.cancel()
        export = null
    }

    /** The key presses the editor answers wherever the pointer is: undo, redo, and Esc to leave. True when used. */
    fun key(control: Boolean, shift: Boolean, letter: Char?, escape: Boolean): Boolean = when {
        escape -> {
            if (export != null) {
                if (export?.done != null || export?.problem?.isNotEmpty() == true) export = null
            } else {
                desk.closeVideo()
            }
            true
        }
        control && letter == 'z' && shift || control && letter == 'y' -> {
            redo()
            true
        }
        control && letter == 'z' -> {
            undo()
            true
        }
        else -> false
    }

    /** The editor is being shut. */
    fun close() {
        stopPlaying()
        exportJob?.cancel()
        frameJob?.cancel()
        scope.cancel()
        val shown = player
        player = null
        if (shown != null) Thread { runCatching { shown.release() } }.apply { isDaemon = true }.start()
    }

    companion object {
        const val DEFAULT_PHOTO_MS = 3000L

        /** The shorter side of the frame shown under the playhead, and of the quick copy made for playing. */
        const val STILL_SHORT_SIDE = 480
        const val PREVIEW_SHORT_SIDE = 360

        /** Where finished videos go: a folder named after the app, inside the videos folder. */
        fun savedFolder(): File {
            val home = File(System.getProperty("user.home").orEmpty())
            val mac = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
            val videos = File(home, if (mac) "Movies" else "Videos").takeIf { it.isDirectory } ?: home
            return File(videos, AppFolders.NAME)
        }

        /** A new, empty project. */
        fun fresh(name: String = "My video"): EditProject = EditProject(id = UUID.randomUUID().toString(), name = name, updatedAt = System.currentTimeMillis())
    }
}
