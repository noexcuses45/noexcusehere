package com.noexcusehere.mashwave.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.data.MediaEntry
import java.util.concurrent.ConcurrentHashMap

private const val EXTRA_ENTRY = "mashwave.entry"

/** Remembers the full entry behind each queue item so the UI never has to re-parse it. */
private val entryCache = ConcurrentHashMap<String, MediaEntry>()

fun MediaEntry.toMediaItem(): MediaItem {
    entryCache[uri] = this
    val address = Uri.parse(uri)
    val extras = Bundle().apply {
        putString(EXTRA_ENTRY, AppJson.encodeToString(MediaEntry.serializer(), this@toMediaItem))
    }
    return MediaItem.Builder()
        .setMediaId(uri)
        .setUri(address)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(address).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setAlbumTitle(album.ifBlank { null })
                .setArtworkUri(artworkUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build(),
        )
        .build()
}

fun MediaItem.toEntry(): MediaEntry {
    entryCache[mediaId]?.let { return it }
    val json = mediaMetadata.extras?.getString(EXTRA_ENTRY)
    val decoded = json?.let { runCatching { AppJson.decodeFromString(MediaEntry.serializer(), it) }.getOrNull() }
    val entry = decoded ?: MediaEntry(
        uri = mediaId,
        title = mediaMetadata.title?.toString() ?: "Unknown",
        artist = mediaMetadata.artist?.toString() ?: "",
        album = mediaMetadata.albumTitle?.toString() ?: "",
        isVideo = mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO,
    )
    entryCache[mediaId] = entry
    return entry
}

/**
 * Uses the phone's own decoders first and falls back to the bundled FFmpeg audio decoders
 * (AC3, E-AC3, DTS, TrueHD, ALAC ...) for formats the phone can't decode by itself.
 */
fun mashwaveRenderers(context: Context): RenderersFactory =
    DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
