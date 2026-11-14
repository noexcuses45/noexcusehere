package com.noexcusehere.mashwave.automash

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.noexcusehere.mashwave.app
import com.noexcusehere.mashwave.data.AutoMashup
import com.noexcusehere.mashwave.data.MediaEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

data class AutoMashState(
    /** Songs chosen for the next mashup, in the order they will enter. */
    val songs: List<MediaEntry> = emptyList(),
    val working: Boolean = false,
    /** What is happening right now, in plain words. */
    val step: String = "",
    val progress: Float = 0f,
    val error: String? = null,
    /** The most recently finished mashup. */
    val result: AutoMashup? = null,
    /** A line about how the songs were fitted together. */
    val summary: String = "",
)

/**
 * "Pick songs, get a mashup": listens to each song, works out a common tempo and matching keys,
 * arranges them, mixes everything into one audio file and adds it to the library.
 */
class AutoMashupMaker(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AutoMashState())
    val state: StateFlow<AutoMashState> = _state
    private var job: Job? = null

    private val workDir = File(context.cacheDir, "automash")

    /** Songs already decoded and analysed in this session, so "make another version" is quick. */
    private class Prepared(val file: File, val analysis: SongAnalysis)
    private val prepared = HashMap<String, Prepared>()

    init {
        // Left-overs from a previous run are just wasted space.
        scope.launch(Dispatchers.IO) { runCatching { workDir.deleteRecursively() } }
    }

    fun add(entries: List<MediaEntry>) = _state.update { s ->
        val known = s.songs.mapTo(HashSet()) { it.uri }
        s.copy(songs = (s.songs + entries.filter { !it.isStream && known.add(it.uri) }).take(MAX_SONGS), error = null)
    }

    fun remove(uri: String) {
        _state.update { s -> s.copy(songs = s.songs.filterNot { it.uri == uri }, error = null) }
        tidy()
    }

    fun clear() {
        _state.update { it.copy(songs = emptyList(), error = null) }
        tidy()
    }

    /** Deletes the decoded copies of songs that are no longer chosen (they are large). */
    private fun tidy() {
        if (_state.value.working) return
        val wanted = _state.value.songs.mapTo(HashSet()) { it.uri }
        val stale = synchronized(prepared) {
            prepared.keys.filter { it !in wanted }.mapNotNull { prepared.remove(it)?.file }
        }
        if (stale.isNotEmpty()) scope.launch(Dispatchers.IO) { stale.forEach { it.delete() } }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Starts mixing. [variation] makes a different arrangement of the same songs. */
    fun create(variation: Boolean = false) {
        val songs = _state.value.songs
        if (songs.size < 2 || _state.value.working) return
        _state.update { it.copy(working = true, step = "Getting ready", progress = 0f, error = null) }
        job = scope.launch {
            try {
                val made = withContext(Dispatchers.Default) { make(songs, variation) }
                context.app.store.addAutoMashup(made.first)
                context.app.library.refresh()
                _state.update { it.copy(working = false, step = "", progress = 1f, result = made.first, summary = made.second) }
                context.app.player.play(listOf(made.first.entry))
            } catch (e: CancellationException) {
                _state.update { it.copy(working = false, step = "", progress = 0f) }
            } catch (e: Throwable) {
                Log.w(TAG, "Auto mashup failed", e)
                val message = (e as? MashupProblem)?.message ?: "Something went wrong while mixing. Try different songs."
                _state.update { it.copy(working = false, step = "", progress = 0f, error = message) }
            }
        }
    }

    private class MashupProblem(message: String) : Exception(message)

    private fun report(step: String, progress: Float) = _state.update { it.copy(step = step, progress = progress.coerceIn(0f, 1f)) }

    private suspend fun make(songs: List<MediaEntry>, variation: Boolean): Pair<AutoMashup, String> = withContext(Dispatchers.Default) {
        workDir.mkdirs()
        // Forget prepared songs that are not part of this mashup.
        val wanted = songs.mapTo(HashSet()) { it.uri }
        synchronized(prepared) {
            prepared.keys.filter { it !in wanted }.forEach { key -> prepared.remove(key)?.file?.delete() }
        }

        // 1. Listen to every song (about 60% of the work).
        val analyses = ArrayList<SongAnalysis>()
        val files = ArrayList<File>()
        songs.forEachIndexed { index, entry ->
            ensureActive()
            val share = 0.6f / songs.size
            val base = index * share
            val ready = synchronized(prepared) { prepared[entry.uri] }?.takeIf { it.file.exists() }
            if (ready != null) {
                analyses += ready.analysis
                files += ready.file
                return@forEachIndexed
            }
            report("Listening to \"${entry.title}\"", base)
            // A decoded song needs roughly 10 MB per minute while the mashup is being made.
            if (workDir.usableSpace < MIN_FREE_BYTES) {
                throw MashupProblem("Not enough free storage to mix these songs. Free up about 500 MB and try again.")
            }
            val file = File(workDir, "song-${UUID.randomUUID()}.pcm")
            val collector = FeatureCollector()
            try {
                PcmDecoder.decode(
                    context, Uri.parse(entry.uri), file, collector, MAX_SONG_SECONDS,
                    keepGoing = { isActive },
                    onProgress = { report("Listening to \"${entry.title}\"", base + share * 0.9f * it) },
                )
            } catch (e: InterruptedException) {
                file.delete()
                throw CancellationException()
            } catch (e: Exception) {
                file.delete()
                Log.w(TAG, "Could not decode ${entry.uri}", e)
                throw MashupProblem("Couldn't read \"${entry.title}\". Try a different song.")
            }
            val analysis = SongAnalyzer.analyze(collector)
            if (analysis == null) {
                file.delete()
                throw MashupProblem("\"${entry.title}\" is too short or has no steady beat to mix with. Try a different song.")
            }
            Log.d(TAG, "${entry.title}: ${analysis.bpm} BPM, ${analysis.keyName}, ${analysis.beats.size} beats")
            synchronized(prepared) { prepared[entry.uri] = Prepared(file, analysis) }
            analyses += analysis
            files += file
        }

        // 2. Decide the arrangement.
        ensureActive()
        report("Matching tempo and key", 0.6f)
        val plan = MixPlanner.plan(analyses, seed = if (variation) Random.nextLong() else 1L, shuffle = variation)
        if (plan.clips.isEmpty()) throw MashupProblem("These songs are too short to mix. Try longer ones.")

        // 3. Mix it down to one file.
        val sources = files.map { FilePcmSource(it) }
        val temp = File(workDir, "mix-${UUID.randomUUID()}")
        var extension = "m4a"
        var mime = "audio/mp4"
        try {
            val finished = try {
                val sink = AacSink(temp)
                var ok = false
                try {
                    ok = MixRenderer(plan, sources).render(sink, { report("Mixing", 0.6f + 0.38f * it) }, { isActive })
                } finally {
                    sink.close(abandon = !ok)
                }
                ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No usable AAC encoder on this phone: fall back to an uncompressed file.
                Log.w(TAG, "AAC encoding failed, writing WAV instead", e)
                extension = "wav"
                mime = "audio/x-wav"
                val sink = WavSink(temp)
                val ok = try {
                    MixRenderer(plan, sources).render(sink, { report("Mixing", 0.6f + 0.38f * it) }, { isActive })
                } finally {
                    sink.close()
                }
                ok
            }
            if (!finished) throw CancellationException()

            // 4. Put it where the music lives.
            report("Saving", 0.99f)
            val name = nameFor(songs)
            val uri = save(temp, name, extension, mime)
            val entry = MediaEntry(
                uri = uri,
                title = name,
                artist = "Auto mashup",
                album = "Mashwave",
                durationMs = (plan.seconds * 1000).toLong(),
                dateAdded = System.currentTimeMillis() / 1000,
                size = temp.length(),
            )
            val changes = songs.indices.mapNotNull { i ->
                val p = plan.songs[i]
                val parts = ArrayList<String>()
                val percent = ((p.stretch - 1f) * 100).roundToInt()
                if (percent != 0) parts += (if (percent > 0) "+$percent%" else "$percent%") + " speed"
                if (p.semitones != 0) parts += (if (p.semitones > 0) "+${p.semitones}" else "${p.semitones}") + " semitones"
                if (parts.isEmpty()) null else "${songs[i].title}: ${parts.joinToString(", ")}"
            }
            val summary = "${plan.tempo.roundToInt()} BPM" + if (changes.isEmpty()) "" else " · " + changes.joinToString(" · ")
            AutoMashup(
                id = UUID.randomUUID().toString(),
                name = name,
                entry = entry,
                sources = songs,
                tempo = plan.tempo,
                createdAt = System.currentTimeMillis(),
            ) to summary
        } finally {
            sources.forEach { it.close() }
            temp.delete()
        }
    }

    private fun nameFor(songs: List<MediaEntry>): String {
        fun short(title: String) = title.trim().take(28).trim()
        val base = when (songs.size) {
            2 -> "${short(songs[0].title)} × ${short(songs[1].title)}"
            else -> "${short(songs[0].title)} × ${short(songs[1].title)} + ${songs.size - 2} more"
        }
        // Each version gets its own name so files and list entries never collide.
        val taken = context.app.store.data.value.autoMashups.mapTo(HashSet()) { it.name }
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    /** Copies the finished file into the phone's Music/Mashwave folder and returns its address. */
    private fun save(file: File, name: String, extension: String, mime: String): String {
        val fileName = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim() + "." + extension
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Mashwave")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: throw IOException("Could not create the file")
            try {
                resolver.openOutputStream(item)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Could not open the file")
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(item, values, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(item, null, null) }
                throw e
            }
            // Same address form the library scan uses, so likes and history line up.
            val id = ContentUris.parseId(item)
            return ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id).toString()
        }
        // Older Android: keep it in the app's own music folder (no extra permission needed).
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Mashwave")
        dir.mkdirs()
        var target = File(dir, fileName)
        var n = 2
        while (target.exists()) target = File(dir, fileName.substringBeforeLast('.') + " ($n)." + extension).also { n++ }
        file.copyTo(target, overwrite = true)
        return Uri.fromFile(target).toString()
    }

    /** Removes a finished mashup from the list and deletes its file. */
    fun delete(mashup: AutoMashup) {
        context.app.store.deleteAutoMashup(mashup.id)
        _state.update { if (it.result?.id == mashup.id) it.copy(result = null, summary = "") else it }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(mashup.entry.uri)
                if (uri.scheme == "file") uri.path?.let { File(it).delete() } else context.contentResolver.delete(uri, null, null)
            }.onFailure { Log.w(TAG, "Could not delete ${mashup.entry.uri}", it) }
            withContext(Dispatchers.Main) { context.app.library.refresh() }
        }
    }

    companion object {
        const val MAX_SONGS = 6
        private const val MAX_SONG_SECONDS = 6 * 60
        private const val MIN_FREE_BYTES = 150L * 1024 * 1024
        private const val TAG = "Mashwave"
    }
}
