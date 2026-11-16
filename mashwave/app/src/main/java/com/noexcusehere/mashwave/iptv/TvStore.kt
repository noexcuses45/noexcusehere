package com.noexcusehere.mashwave.iptv

import android.content.Context
import com.noexcusehere.mashwave.data.AppJson
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** What the TV tab remembers between launches. */
@Serializable
data class TvSaved(
    val accounts: List<TvAccount> = emptyList(),
    /** Id of the service being watched. */
    val active: String = "",
    /** Starred channels, movies and shows, per service. */
    val favourites: Map<String, List<TvItem>> = emptyMap(),
    /** Recently watched, newest first, per service. */
    val recents: Map<String, List<TvItem>> = emptyMap(),
    val section: TvSection = TvSection.LIVE,
    /** Name of the [LiveCushion] chosen for live channels. */
    val cushion: String = LiveCushion.SMOOTHER.name,
    /** The channels last shown side by side in the multiview, per service. */
    val multiview: Map<String, List<TvItem>> = emptyMap(),
)

/**
 * Files behind the TV tab. Logins live in a folder Android leaves out of cloud backups, and the
 * channel lists are kept next to them so the tab opens at once instead of downloading every time.
 */
class TvStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "tv").apply { mkdirs() }
    private val file = File(dir, "tv.json")

    fun load(): TvSaved = runCatching {
        if (file.exists()) AppJson.decodeFromString(TvSaved.serializer(), file.readText()) else TvSaved()
    }.getOrElse { TvSaved() }

    fun save(saved: TvSaved) {
        runCatching {
            val temp = File(dir, "tv.json.tmp")
            temp.writeText(AppJson.encodeToString(TvSaved.serializer(), saved))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun listFile(account: String, section: TvSection) = File(dir, "$account-${section.name.lowercase()}.list")

    fun saveCatalog(account: String, section: TvSection, catalog: TvCatalog) {
        runCatching {
            val temp = File(dir, "$account-${section.name.lowercase()}.tmp")
            DataOutputStream(temp.outputStream().buffered(256 * 1024)).use { out -> write(out, catalog) }
            val target = listFile(account, section)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    fun loadCatalog(account: String, section: TvSection): TvCatalog? = runCatching {
        val source = listFile(account, section)
        if (!source.exists()) return null
        DataInputStream(source.inputStream().buffered(256 * 1024)).use { input -> read(input, section) }
    }.getOrNull()

    fun forget(account: String) {
        dir.listFiles()?.forEach { if (it.name.startsWith("$account-")) it.delete() }
    }

    companion object {
        private const val FORMAT = 0x54560001

        /** Text longer than this cannot be stored by writeUTF; nothing genuine is anywhere near it. */
        private fun String.fit(): String = if (length > 8000) substring(0, 8000) else this

        fun write(out: DataOutputStream, catalog: TvCatalog) {
            out.writeInt(FORMAT)
            out.writeLong(catalog.loadedAt)
            out.writeInt(catalog.categories.size)
            for (category in catalog.categories) {
                out.writeUTF(category.id.fit())
                out.writeUTF(category.name.fit())
            }
            out.writeInt(catalog.items.size)
            for (item in catalog.items) {
                out.writeUTF(item.id.fit())
                out.writeUTF(item.name.fit())
                out.writeUTF(item.logo.fit())
                out.writeUTF(item.category.fit())
                // Playlists use the address as the id; store it once.
                out.writeBoolean(item.url == item.id)
                if (item.url != item.id) out.writeUTF(item.url.fit())
                out.writeUTF(item.ext)
                out.writeInt(item.number)
                out.writeFloat(item.rating)
                out.writeLong(item.added)
                out.writeUTF(item.epgId.fit())
                out.writeUTF(item.country)
            }
        }

        fun read(input: DataInputStream, section: TvSection): TvCatalog? {
            if (input.readInt() != FORMAT) return null
            val loadedAt = input.readLong()
            val categories = ArrayList<TvCategory>()
            repeat(input.readInt()) { categories += TvCategory(input.readUTF(), input.readUTF()) }
            val count = input.readInt()
            val items = ArrayList<TvItem>(count)
            val pool = HashMap<String, String>()
            fun shared(text: String): String = pool.getOrPut(text) { text }
            repeat(count) {
                val id = input.readUTF()
                val name = input.readUTF()
                val logo = input.readUTF()
                val category = shared(input.readUTF())
                val url = if (input.readBoolean()) id else input.readUTF()
                items += TvItem(
                    section = section,
                    id = id,
                    name = name,
                    logo = logo,
                    category = category,
                    url = url,
                    ext = shared(input.readUTF()),
                    number = input.readInt(),
                    rating = input.readFloat(),
                    added = input.readLong(),
                    epgId = input.readUTF(),
                    country = shared(input.readUTF()),
                )
            }
            return TvCatalog(categories, items, loadedAt)
        }
    }
}
