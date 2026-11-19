package com.noexcusehere.mashwave.data

import kotlinx.serialization.json.Json

/**
 * Something the player can play. The phone app has a bigger version of this (songs, photos,
 * folders); the computer app only needs what the TV code fills in.
 */
data class MediaEntry(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val isVideo: Boolean = false,
    val durationMs: Long = 0,
    val track: Int = 0,
    /** Web address of a picture for it (a channel's logo, a movie's poster). */
    val art: String = "",
    /** Set for streams from a TV service: says whether it is a live channel, and which one. */
    val tv: String = "",
)

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}
