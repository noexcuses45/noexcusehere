package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.data.AppJson
import com.noexcusehere.mashwave.data.MediaEntry
import com.noexcusehere.mashwave.data.MediaSource
import com.noexcusehere.mashwave.data.Playlist
import com.noexcusehere.mashwave.data.UserData
import com.noexcusehere.mashwave.data.isFromCloud
import com.noexcusehere.mashwave.data.providerLabelOf
import com.noexcusehere.mashwave.data.source
import com.noexcusehere.mashwave.data.sourceOfFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    @Test
    fun chatAppsAreRecognisedByTheirFolders() {
        // Where WhatsApp keeps received media on new and old Android versions.
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"))
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder("/storage/emulated/0/WhatsApp/Media/WhatsApp Video"))
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder("/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Images"))
        // Messenger only has what was saved out of a chat.
        assertEquals(MediaSource.MESSENGER, sourceOfFolder("/storage/emulated/0/Pictures/Messenger"))
        assertEquals(MediaSource.MESSENGER, sourceOfFolder("/storage/emulated/0/Movies/Messenger"))
        assertEquals(MediaSource.MESSENGER, sourceOfFolder("/storage/emulated/0/DCIM/Facebook"))
        // Pictures saved from text messages.
        assertEquals(MediaSource.TEXTS, sourceOfFolder("/storage/emulated/0/Pictures/Messages"))
        assertEquals(MediaSource.TELEGRAM, sourceOfFolder("/storage/emulated/0/Pictures/Telegram"))
        assertEquals(MediaSource.INSTAGRAM, sourceOfFolder("/storage/emulated/0/Pictures/Instagram"))
    }

    @Test
    fun phoneFoldersAreRecognised() {
        assertEquals(MediaSource.CAMERA, sourceOfFolder("/storage/emulated/0/DCIM/Camera"))
        assertEquals(MediaSource.CAMERA, sourceOfFolder("/storage/1234-5678/DCIM/100ANDRO"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("/storage/emulated/0/Pictures/Screenshots"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("/storage/emulated/0/DCIM/Screenshots"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("/storage/emulated/0/Movies/Screen recordings"))
        assertEquals(MediaSource.DOWNLOADS, sourceOfFolder("/storage/emulated/0/Download"))
        assertEquals(MediaSource.MADE_HERE, sourceOfFolder("/storage/emulated/0/Movies/Ultimate Beats"))
        assertEquals(MediaSource.OTHER, sourceOfFolder("/storage/emulated/0/Movies/Holiday 2025"))
        assertEquals(MediaSource.OTHER, sourceOfFolder(""))
        // "Messenger" must be a folder of its own, not just part of another name.
        assertEquals(MediaSource.OTHER, sourceOfFolder("/storage/emulated/0/Pictures/MessengerBackupOld2"))
    }

    @Test
    fun thingsAddedFromOtherAppsAreMarked() {
        val fromDrive = MediaEntry(uri = "content://x/1", title = "Song", folder = "/Cloud/Google Drive")
        assertTrue(fromDrive.isFromCloud)
        assertEquals(MediaSource.CLOUD, fromDrive.source)
        assertEquals("Google Drive", fromDrive.folderName)
        // With no artist to show, the list says where it came from instead.
        assertEquals("Google Drive", fromDrive.subtitle)
        assertEquals("Queen", fromDrive.copy(artist = "Queen").subtitle)
        assertFalse(MediaEntry(uri = "content://media/external/audio/media/5", title = "Local", folder = "/storage/emulated/0/Music").isFromCloud)
    }

    @Test
    fun appNamesComeFromTheAddress() {
        assertEquals("Google Drive", providerLabelOf("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dabc"))
        assertEquals("Google Photos", providerLabelOf("content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/123"))
        assertEquals("Google Photos", providerLabelOf("content://com.google.android.apps.photos.contentprovider/0/1/content"))
        assertEquals("Gallery", providerLabelOf("content://media/picker/0/com.android.providers.media.photopicker/media/1000000033"))
        assertEquals("OneDrive", providerLabelOf("content://com.microsoft.skydrive.content.StorageAccessProvider/document/1"))
        assertEquals("Downloads", providerLabelOf("content://com.android.providers.downloads.documents/document/msf%3A31"))
        assertEquals("Files", providerLabelOf("content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.mp3"))
        assertEquals("Other apps", providerLabelOf("content://com.example.provider/file/9"))
    }

    @Test
    fun dataSavedByOlderVersionsStillLoads() {
        // What the previous version wrote: no watch lists, no imported files, no photos.
        val old = """{"playlists":[{"id":"p1","name":"Road trip","items":[{"uri":"content://a/1","title":"One"}]}],"onboarded":true}"""
        val data = AppJson.decodeFromString(UserData.serializer(), old)
        assertEquals(1, data.playlists.size)
        assertFalse(data.playlists[0].video)
        assertTrue(data.imported.isEmpty())
        assertFalse(data.playlists[0].items[0].isImage)
        // And a watch list survives a save and load.
        val withWatchList = data.copy(playlists = data.playlists + Playlist(id = "w1", name = "Friday", video = true))
        val again = AppJson.decodeFromString(UserData.serializer(), AppJson.encodeToString(UserData.serializer(), withWatchList))
        assertTrue(again.playlists.single { it.id == "w1" }.video)
    }
}

class ResumeRulesTest {
    private val hour = 3_600_000L

    @Test
    fun aVideoPartWayThroughIsRemembered() {
        assertTrue(com.noexcusehere.mashwave.data.ResumeRules.worthKeeping(12 * 60_000L, hour))
        // Barely started: just begin again.
        assertFalse(com.noexcusehere.mashwave.data.ResumeRules.worthKeeping(4_000, hour))
        // The last two minutes of an hour (credits) count as finished.
        assertFalse(com.noexcusehere.mashwave.data.ResumeRules.worthKeeping(hour - 60_000, hour))
        assertTrue(com.noexcusehere.mashwave.data.ResumeRules.nearEnd(hour - 60_000, hour))
        assertFalse(com.noexcusehere.mashwave.data.ResumeRules.nearEnd(30 * 60_000L, hour))
        // Short clips never resume.
        assertFalse(com.noexcusehere.mashwave.data.ResumeRules.worthKeeping(20_000, 45_000))
        // A three-minute video: 15 seconds from the end is "finished", 30 seconds is not.
        assertFalse(com.noexcusehere.mashwave.data.ResumeRules.worthKeeping(170_000, 180_000))
        assertTrue(com.noexcusehere.mashwave.data.ResumeRules.worthKeeping(150_000, 180_000))
    }

    @Test
    fun resumingStartsALittleEarly() {
        val rules = com.noexcusehere.mashwave.data.ResumeRules
        assertEquals(0, rules.startFrom(null))
        assertEquals(718_000, rules.startFrom(com.noexcusehere.mashwave.data.ResumePoint(720_000, hour)))
        assertEquals(0.2f, rules.fraction(com.noexcusehere.mashwave.data.ResumePoint(720_000, hour)), 0.001f)
        assertEquals(0f, rules.fraction(null), 0f)
    }

    @Test
    fun savingForgettingAndTheLimit() {
        val rules = com.noexcusehere.mashwave.data.ResumeRules
        var all = emptyMap<String, com.noexcusehere.mashwave.data.ResumePoint>()
        all = rules.updated(all, "a", 10 * 60_000L, hour, now = 1)
        assertEquals(10 * 60_000L, all.getValue("a").positionMs)
        // Moving on replaces the place.
        all = rules.updated(all, "a", 20 * 60_000L, hour, now = 2)
        assertEquals(20 * 60_000L, all.getValue("a").positionMs)
        // Reaching the end forgets it.
        all = rules.updated(all, "a", hour - 5_000, hour, now = 3)
        assertFalse("a" in all)
        // Nothing to forget: the very same map comes back, so nothing is rewritten.
        assertTrue(rules.updated(all, "zzz", 1_000, hour, now = 4) === all)
        // Only the newest places are kept.
        for (i in 0 until rules.MAX_KEPT + 20) all = rules.updated(all, "v$i", 5 * 60_000L, hour, now = 100L + i)
        assertEquals(rules.MAX_KEPT, all.size)
        assertFalse("v0" in all)
        assertTrue("v${rules.MAX_KEPT + 19}" in all)
    }
}
