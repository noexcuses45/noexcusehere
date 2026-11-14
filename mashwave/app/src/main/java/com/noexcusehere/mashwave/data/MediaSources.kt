package com.noexcusehere.mashwave.data

import android.net.Uri

/** Where a photo, video or song came from, worked out from the folder it sits in. */
enum class MediaSource(val label: String) {
    WHATSAPP("WhatsApp"),
    MESSENGER("Messenger"),
    TEXTS("Texts"),
    TELEGRAM("Telegram"),
    INSTAGRAM("Instagram"),
    SNAPCHAT("Snapchat"),
    CAMERA("Camera"),
    SCREENSHOTS("Screenshots"),
    DOWNLOADS("Downloads"),
    CLOUD("Drive & cloud"),
    MADE_HERE("Made here"),
    OTHER("Other"),
}

/** Folder prefix of things added from Google Drive, Google Photos or another app. */
const val CLOUD_FOLDER = "/Cloud/"

/**
 * Sorts a file into a source by its folder. Chat apps keep what they receive in folders named
 * after themselves, which is what makes "show me my WhatsApp videos" possible.
 */
fun sourceOfFolder(folder: String): MediaSource {
    val path = folder.lowercase().trimEnd('/') + "/"
    return when {
        path.startsWith(CLOUD_FOLDER.lowercase()) -> MediaSource.CLOUD
        "whatsapp" in path -> MediaSource.WHATSAPP
        "/messenger/" in path || "facebook" in path || "com.facebook.orca" in path -> MediaSource.MESSENGER
        "/messages/" in path || "/mms/" in path || "/textra/" in path || "apps.messaging" in path || "android.messaging" in path ->
            MediaSource.TEXTS
        "telegram" in path -> MediaSource.TELEGRAM
        "instagram" in path -> MediaSource.INSTAGRAM
        "snapchat" in path -> MediaSource.SNAPCHAT
        "/ultimate beats/" in path || "/mashwave/" in path -> MediaSource.MADE_HERE
        "screenshot" in path || "screen recording" in path || "screenrecord" in path || "screen_record" in path -> MediaSource.SCREENSHOTS
        "/dcim/" in path -> MediaSource.CAMERA
        "/download/" in path || "/downloads/" in path -> MediaSource.DOWNLOADS
        else -> MediaSource.OTHER
    }
}

val MediaEntry.source: MediaSource
    get() = sourceOfFolder(folder)

/** True for something added from Google Drive, Google Photos or another app rather than found on the phone. */
val MediaEntry.isFromCloud: Boolean
    get() = folder.startsWith(CLOUD_FOLDER)

/** Plain name of the app a picked file came from, read from its address. */
fun providerLabel(uri: Uri): String = providerLabelOf(uri.toString())

fun providerLabelOf(address: String): String {
    val text = address.lowercase()
    return when {
        "com.google.android.apps.docs" in text -> "Google Drive"
        "com.google.android.apps.photos" in text -> "Google Photos"
        "com.microsoft.skydrive" in text -> "OneDrive"
        "dropbox" in text -> "Dropbox"
        "media/picker" in text || "photopicker" in text -> "Gallery"
        "com.android.providers.downloads" in text -> "Downloads"
        "com.android.externalstorage" in text -> "Files"
        "com.whatsapp" in text -> "WhatsApp"
        else -> "Other apps"
    }
}
