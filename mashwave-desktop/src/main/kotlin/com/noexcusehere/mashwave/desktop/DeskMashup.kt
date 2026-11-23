package com.noexcusehere.mashwave.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noexcusehere.mashwave.automash.FeatureCollector
import com.noexcusehere.mashwave.automash.FilePcmSource
import com.noexcusehere.mashwave.automash.MIX_RATE
import com.noexcusehere.mashwave.automash.MixPlan
import com.noexcusehere.mashwave.automash.MixPlanner
import com.noexcusehere.mashwave.automash.MixRenderer
import com.noexcusehere.mashwave.automash.SceneKind
import com.noexcusehere.mashwave.automash.SongAnalysis
import com.noexcusehere.mashwave.automash.SongAnalyzer
import com.noexcusehere.mashwave.automash.WavSink
import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.desktop.vlc.VlcConvert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** A mashup that was made: the file, and how it was put together. */
@Serializable
data class MadeMashup(
    val path: String,
    val name: String,
    /** "128 BPM · Song: +3% speed" */
    val summary: String = "",
    /** One line per moment: "0:31  Drop: ... takes over". */
    val story: List<String> = emptyList(),
    /** The songs it was made from, by where they are kept. */
    val sources: List<String> = emptyList(),
    val createdAt: Long = 0,
)

@Serializable
private data class MashupsSaved(val made: List<MadeMashup> = emptyList())

/**
 * "Pick songs, get a mashup" on the computer: listens to each song, works out a common tempo and
 * matching keys, arranges them, mixes everything into one file and puts it with the music.
 *
 * The listening, planning and mixing are the phone app's own code. What differs here is how a
 * song is read in and how the result is saved: VLC's engine does both.
 */
class DeskMashup(private val desk: Desk, private val scope: CoroutineScope, private val dataDir: File) {
    /** Songs chosen for the next mashup, in the order they will come in. */
    var songs by mutableStateOf(emptyList<LocalFile>())
        private set
    var working by mutableStateOf(false)
        private set

    /** What is happening right now, in plain words. */
    var step by mutableStateOf("")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The mashup made most recently in this visit. */
    var result by mutableStateOf<MadeMashup?>(null)
        private set

    /** Every mashup made so far whose file is still there, newest first. */
    var made by mutableStateOf(emptyList<MadeMashup>())
        private set

    /** How long the next one should be, in seconds; 0 lets the app decide. */
    var lengthSeconds by mutableIntStateOf(0)

    private var job: Job? = null
    private val workDir = File(dataDir, "mashup-work")
    private val listFile = File(dataDir, "mashups.json")

    /** Songs already read in and listened to in this visit, so "try another version" is quick. */
    private class Prepared(val file: File, val analysis: SongAnalysis)
    private val prepared = HashMap<String, Prepared>()

    init {
        scope.launch(Dispatchers.IO) {
            // Left-overs from a previous run are just wasted space.
            runCatching { workDir.deleteRecursively() }
            val kept = runCatching { AppJson.decodeFromString(MashupsSaved.serializer(), listFile.readText()).made }.getOrDefault(emptyList())
            made = kept.filter { File(it.path).isFile }
        }
    }

    fun add(song: LocalFile) {
        if (working || song.video || songs.size >= MOST_SONGS || songs.any { it.path == song.path }) return
        songs = songs + song
        error = null
    }

    fun remove(path: String) {
        if (working) return
        songs = songs.filter { it.path != path }
    }

    fun clear() {
        if (working) return
        songs = emptyList()
        error = null
    }

    fun cancel() {
        job?.cancel()
    }

    /** Forgets [mashup] and deletes its file. */
    fun delete(mashup: MadeMashup) {
        made = made.filter { it.path != mashup.path }
        if (result?.path == mashup.path) result = null
        if (desk.player?.current?.uri == mashup.path) desk.stopPlaying()
        scope.launch(Dispatchers.IO) {
            runCatching { File(mashup.path).delete() }
            saveList()
            desk.lookForMediaAgain()
        }
    }

    /** Starts mixing. [variation] makes a different arrangement of the same songs. */
    fun create(variation: Boolean = false) {
        val chosen = songs
        if (chosen.size < 2 || working) return
        val engine = desk.engine
        if (engine == null) {
            error = "The player is not ready yet, and mashups need it to read the songs. Try again in a moment."
            return
        }
        val length = lengthSeconds
        working = true
        step = "Getting ready"
        progress = 0f
        error = null
        job = scope.launch {
            try {
                val mashup = withContext(Dispatchers.Default) { make(VlcConvert(engine), chosen, variation, length) }
                made = listOf(mashup) + made.filter { it.path != mashup.path }
                result = mashup
                working = false
                step = ""
                progress = 1f
                withContext(Dispatchers.IO) { saveList() }
                desk.lookForMediaAgain()
                desk.playSongs(listOf(fileOf(mashup)), 0)
            } catch (e: CancellationException) {
                working = false
                step = ""
                progress = 0f
            } catch (e: Throwable) {
                working = false
                step = ""
                progress = 0f
                error = (e as? MashupProblem)?.message ?: "Something went wrong while mixing. Try different songs."
                if (e !is MashupProblem) System.err.println("mashup failed: $e")
            }
        }
    }

    /** [mashup] as a song the player can be handed. */
    fun fileOf(mashup: MadeMashup): LocalFile =
        LocalFile(path = mashup.path, title = mashup.name, artist = "Auto mashup", album = AppFolders.NAME)

    private class MashupProblem(message: String) : Exception(message)

    private fun report(text: String, done: Float) {
        step = text
        progress = done.coerceIn(0f, 1f)
    }

    private suspend fun make(convert: VlcConvert, chosen: List<LocalFile>, variation: Boolean, length: Int): MadeMashup = withContext(Dispatchers.Default) {
        workDir.mkdirs()
        // Forget prepared songs that are not part of this mashup.
        val wanted = chosen.mapTo(HashSet()) { it.path }
        synchronized(prepared) {
            prepared.keys.filter { it !in wanted }.forEach { key -> prepared.remove(key)?.file?.delete() }
        }

        // 1. Listen to every song (about 60% of the work).
        val analyses = ArrayList<SongAnalysis>()
        val files = ArrayList<File>()
        chosen.forEachIndexed { index, song ->
            ensureActive()
            val share = 0.6f / chosen.size
            val base = index * share
            val ready = synchronized(prepared) { prepared[song.path] }?.takeIf { it.file.exists() }
            if (ready != null) {
                analyses += ready.analysis
                files += ready.file
                return@forEachIndexed
            }
            report("Listening to \"${song.title}\"", base)
            // A song read in needs roughly 10 MB per minute while the mashup is being made.
            if (workDir.usableSpace < MIN_FREE_BYTES) {
                throw MashupProblem("Not enough free space on the disk to mix these songs. Free up about 500 MB and try again.")
            }
            val id = UUID.randomUUID().toString()
            val wav = File(workDir, "song-$id.wav")
            val pcm = File(workDir, "song-$id.pcm")
            val collector = FeatureCollector()
            try {
                val read = convert.toWav(File(song.path), wav, MAX_SONG_SECONDS, { isActive }) { report("Listening to \"${song.title}\"", base + share * 0.6f * it) }
                ensureActive()
                if (!read) throw IOException("the engine could not read it")
                listenTo(wav, pcm, collector)
            } catch (e: CancellationException) {
                pcm.delete()
                throw e
            } catch (e: Exception) {
                pcm.delete()
                System.err.println("could not read ${song.path}: $e")
                throw MashupProblem("Couldn't read \"${song.title}\". Try a different song.")
            } finally {
                wav.delete()
            }
            report("Listening to \"${song.title}\"", base + share * 0.9f)
            val analysis = SongAnalyzer.analyze(collector)
            if (analysis == null) {
                pcm.delete()
                throw MashupProblem("\"${song.title}\" is too short or has no steady beat to mix with. Try a different song.")
            }
            synchronized(prepared) { prepared[song.path] = Prepared(pcm, analysis) }
            analyses += analysis
            files += pcm
        }

        // 2. Decide the arrangement.
        ensureActive()
        report("Planning the mix like a DJ", 0.6f)
        val plan = MixPlanner.plan(
            analyses,
            seed = if (variation) Random.nextLong() else 1L,
            shuffle = variation,
            targetSeconds = length.toFloat(),
        )
        if (plan.clips.isEmpty()) throw MashupProblem("These songs are too short to mix. Try longer ones.")

        // 3. Mix it down to one file.
        val sources = files.map { FilePcmSource(it) }
        val mixed = File(workDir, "mix-${UUID.randomUUID()}.wav")
        try {
            val sink = WavSink(mixed)
            val finished = try {
                MixRenderer(plan, sources).render(sink, { report("Mixing", 0.6f + 0.3f * it) }, { isActive })
            } finally {
                sink.close()
            }
            if (!finished) throw CancellationException()

            // 4. Pack it small, and put it where the music lives.
            report("Saving", 0.9f)
            val name = nameFor(chosen)
            val folder = mashupFolder()
            val wantedMs = (plan.seconds * 1000).toLong()
            var saved: File? = null
            for (kind in VlcConvert.Kind.entries) {
                ensureActive()
                val packed = File(workDir, "packed-${UUID.randomUUID()}.${kind.ending}")
                val ok = runCatching { convert.pack(mixed, packed, kind, { isActive }) { report("Saving", 0.9f + 0.09f * it) } }.getOrDefault(false)
                // Believed only if it reads back as a sound file of the right length.
                val reads = ok && Tags.read(packed)?.durationMs?.let { abs(it - wantedMs) < 5000 } == true
                if (reads) {
                    saved = moveInto(packed, folder, name, kind.ending)
                    break
                }
                packed.delete()
            }
            ensureActive()
            // No way of packing it on this computer: the plain, larger file is kept instead.
            val file = saved ?: moveInto(mixed, folder, name, "wav")
            label(file, name)

            val changes = chosen.indices.mapNotNull { i ->
                val p = plan.songs[i]
                val parts = ArrayList<String>()
                val percent = ((p.stretch - 1f) * 100).roundToInt()
                if (percent != 0) parts += (if (percent > 0) "+$percent%" else "$percent%") + " speed"
                if (p.semitones != 0) parts += (if (p.semitones > 0) "+${p.semitones}" else "${p.semitones}") + " semitones"
                if (parts.isEmpty()) null else "${chosen[i].title}: ${parts.joinToString(", ")}"
            }
            MadeMashup(
                path = file.absolutePath,
                name = name,
                summary = "${plan.tempo.roundToInt()} BPM" + if (changes.isEmpty()) "" else " · " + changes.joinToString(" · "),
                story = storyOf(plan, chosen),
                sources = chosen.map { it.path },
                createdAt = System.currentTimeMillis(),
            )
        } finally {
            sources.forEach { it.close() }
            mixed.delete()
        }
    }

    /**
     * Goes through the sound in [wav] once: every stretch of it is shown to [collector] (which
     * works out the beat and the notes) and written to [pcm] without the file's heading, which is
     * the form the mixer reads.
     */
    private fun listenTo(wav: File, pcm: File, collector: FeatureCollector) {
        DataInputStream(BufferedInputStream(FileInputStream(wav), 1 shl 16)).use { input ->
            val dataBytes = findSound(input, wav.length())
            BufferedOutputStream(FileOutputStream(pcm), 1 shl 16).use { out ->
                val bytes = ByteArray(8192 * 4)
                val shorts = ShortArray(8192 * 2)
                val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                var left = dataBytes
                while (left >= 4) {
                    val want = minOf(bytes.size.toLong(), left).toInt() and 3.inv()
                    var got = 0
                    while (got < want) {
                        val n = input.read(bytes, got, want - got)
                        if (n < 0) break
                        got += n
                    }
                    val frames = got / 4
                    if (frames == 0) break
                    view.position(0)
                    view.get(shorts, 0, frames * 2)
                    collector.add(shorts, frames)
                    out.write(bytes, 0, frames * 4)
                    left -= frames * 4L
                    if (got < want) break
                }
            }
        }
    }

    /** Moves past the heading of a WAV file to its sound, checking it is the kind asked for. Returns how many bytes of sound follow. */
    private fun findSound(input: DataInputStream, fileLength: Long): Long {
        fun text(): String = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        fun number(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xFFFFFFFFL
        if (text() != "RIFF") throw IOException("not a sound file")
        number()
        if (text() != "WAVE") throw IOException("not a sound file")
        var read = 12L
        var formatOk = false
        while (true) {
            val name = text()
            val size = number()
            read += 8
            if (name == "data") {
                if (!formatOk) throw IOException("the sound is not in the form asked for")
                // Some writers leave the size blank; whatever is left of the file is the sound.
                val rest = fileLength - read
                return if (size in 1..rest) size else rest
            }
            if (name == "fmt ") {
                val body = ByteArray(size.toInt().coerceIn(16, 64)).also { input.readFully(it) }
                val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val channels = b.getShort(2).toInt()
                val rate = b.getInt(4)
                val bits = b.getShort(14).toInt()
                formatOk = channels == 2 && rate == MIX_RATE && bits == 16
                val extra = size - body.size
                if (extra > 0) input.skipBytes(extra.toInt())
            } else {
                input.skipBytes(size.toInt())
            }
            read += size
            if (size % 2 == 1L) {
                input.skipBytes(1)
                read++
            }
        }
    }

    /** The mix told as a short running order, with the time each thing happens. */
    private fun storyOf(plan: MixPlan, chosen: List<LocalFile>): List<String> = plan.scenes.map { scene ->
        val seconds = (scene.beat * 60.0 / plan.tempo).toInt()
        val time = "%d:%02d".format(seconds / 60, seconds % 60)
        fun title(index: Int) = chosen.getOrNull(index)?.title?.take(32) ?: "?"
        val what = when (scene.kind) {
            SceneKind.INTRO -> "${title(scene.song)} starts on its own"
            SceneKind.BLEND -> "${title(scene.song)} comes in over the beat of ${title(scene.other)}"
            SceneKind.DROP -> "Drop: the beat cuts out and ${title(scene.song)} takes over at full power"
            SceneKind.OUTRO -> "${title(scene.song)} plays out and fades"
        }
        "$time  $what"
    }

    private fun nameFor(chosen: List<LocalFile>): String {
        fun short(title: String) = title.trim().take(28).trim()
        val base = when (chosen.size) {
            2 -> "${short(chosen[0].title)} × ${short(chosen[1].title)}"
            else -> "${short(chosen[0].title)} × ${short(chosen[1].title)} + ${chosen.size - 2} more"
        }
        // Each version gets its own name so files and list entries never collide.
        val taken = made.mapTo(HashSet()) { it.name }
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    /** The folder mashups are kept in: "Ultimate Media Manager" inside the music folder, made if need be. */
    private fun mashupFolder(): File {
        val home = File(System.getProperty("user.home").orEmpty())
        val music = desk.folders.map { File(it) }.firstOrNull { it.isDirectory && it.name.equals("Music", ignoreCase = true) }
            ?: File(home, "Music").takeIf { it.isDirectory }
            ?: desk.folders.map { File(it) }.firstOrNull { it.isDirectory }
            ?: dataDir
        return File(music, AppFolders.NAME).apply { mkdirs() }
    }

    private fun moveInto(file: File, folder: File, name: String, ending: String): File {
        var clean = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').ifEmpty { "Mashup" }
        // A computer set up for plain English letters only cannot have every character in a file's
        // name; there the name is spelled with the letters it does have.
        if (runCatching { File(folder, "$clean.$ending").toPath() }.isFailure) {
            clean = clean.replace('\u00D7', 'x').replace(Regex("[^\\x20-\\x7E]"), "").replace(Regex("\\s+"), " ").trim().ifEmpty { "Mashup" }
        }
        var target = File(folder, "$clean.$ending")
        var n = 2
        while (target.exists()) target = File(folder, "$clean ($n).$ending").also { n++ }
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            // Another disk: copy, then tidy up.
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            file.delete()
        }
        return target
    }

    /** Writes the name into the file itself, so it reads properly in any music player. */
    private fun label(file: File, name: String) {
        runCatching {
            val audio = AudioFileIO.read(file)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, name)
            tag.setField(FieldKey.ARTIST, "Auto mashup")
            tag.setField(FieldKey.ALBUM, AppFolders.NAME)
            audio.commit()
        }
    }

    private fun saveList() {
        runCatching {
            val temp = File(listFile.parentFile, listFile.name + ".tmp")
            temp.writeText(AppJson.encodeToString(MashupsSaved.serializer(), MashupsSaved(made)))
            Files.move(temp.toPath(), listFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MOST_SONGS = 6
        private const val MAX_SONG_SECONDS = 6 * 60
        private const val MIN_FREE_BYTES = 150L * 1024 * 1024
    }
}
