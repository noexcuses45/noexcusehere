package com.noexcusehere.mashwave

import com.noexcusehere.mashwave.editor.EditClip
import com.noexcusehere.mashwave.editor.EditProject
import com.noexcusehere.mashwave.editor.EditText
import com.noexcusehere.mashwave.editor.EditorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorOpsTest {
    private fun video(id: String, lengthMs: Long, speed: Float = 1f) =
        EditClip(id = id, uri = "content://v/$id", sourceDurationMs = lengthMs, startMs = 0, endMs = lengthMs, speed = speed, width = 1280, height = 720)

    private fun photo(id: String, lengthMs: Long = 3000) =
        EditClip(id = id, uri = "content://p/$id", isImage = true, endMs = lengthMs, width = 600, height = 800)

    private val project = EditProject(id = "p", clips = listOf(video("a", 4000), video("b", 6000, speed = 2f), photo("c")))

    @Test
    fun durationsFollowSpeed() {
        // 4 s + (6 s at double speed = 3 s) + 3 s photo
        assertEquals(10_000, project.durationMs)
        assertEquals(listOf(0L, 4000L, 7000L), project.clipStarts())
        assertEquals(1 to 500L, project.clipAt(4500))
        assertEquals(2 to 3000L, project.clipAt(99_000))
    }

    @Test
    fun splitCutsTheClipUnderThePlayhead() {
        val split = EditorOps.splitAt(project, 1500, "a2")!!
        assertEquals(listOf("a", "a2", "b", "c"), split.clips.map { it.id })
        assertEquals(0L to 1500L, split.clips[0].startMs to split.clips[0].endMs)
        assertEquals(1500L to 4000L, split.clips[1].startMs to split.clips[1].endMs)
        assertEquals(project.durationMs, split.durationMs)
    }

    @Test
    fun splitInsideASpedUpClipUsesOriginalTime() {
        // 1 s into clip b on the timeline is 2 s into the original because it plays at 2x.
        val split = EditorOps.splitAt(project, 5000, "b2")!!
        assertEquals(0L to 2000L, split.clips[1].startMs to split.clips[1].endMs)
        assertEquals(2000L to 6000L, split.clips[2].startMs to split.clips[2].endMs)
        assertEquals(2f, split.clips[2].speed)
        assertEquals(project.durationMs, split.durationMs)
    }

    @Test
    fun splitRefusesSlivers() {
        assertNull(EditorOps.splitAt(project, 50, "x"))
        assertNull(EditorOps.splitAt(project, 3950, "x"))
        // Photos need at least half a second on each side.
        assertNull(EditorOps.splitAt(project, 7200, "x"))
        assertNotNull(EditorOps.splitAt(project, 8000, "x"))
    }

    @Test
    fun trimIsKeptInsideTheOriginal() {
        val trimmed = EditorOps.trim(project, "a", -500, 99_000)
        assertEquals(0L to 4000L, trimmed.clips[0].startMs to trimmed.clips[0].endMs)
        val tight = EditorOps.trim(project, "a", 3000, 3050)
        assertEquals(3000L, tight.clips[0].startMs)
        assertEquals(3000L + EditorOps.MIN_PIECE_MS, tight.clips[0].endMs)
        val picture = EditorOps.trim(project, "c", 0, 100)
        assertEquals(EditorOps.MIN_PHOTO_MS, picture.clips[2].endMs)
    }

    @Test
    fun moveDuplicateAndRemove() {
        assertEquals(listOf("b", "a", "c"), EditorOps.moveClip(project, "a", 1).clips.map { it.id })
        assertEquals(listOf("a", "b", "c"), EditorOps.moveClip(project, "a", -1).clips.map { it.id })
        assertEquals(listOf("a", "c", "b"), EditorOps.moveClip(project, "c", -1).clips.map { it.id })
        assertEquals(listOf("a", "a9", "b", "c"), EditorOps.duplicateClip(project, "a", "a9").clips.map { it.id })
        assertEquals(listOf("a", "c"), EditorOps.removeClip(project, "b").clips.map { it.id })
        assertEquals(listOf("a", "x", "b", "c"), EditorOps.addClips(project, listOf(video("x", 1000)), 1).clips.map { it.id })
    }

    @Test
    fun updatesAreKeptInRange() {
        val wild = EditorOps.updateClip(project, "a") { it.copy(speed = 99f, volume = 7f, rotation = 450, brightness = 3f) }.clips[0]
        assertEquals(EditorOps.MAX_SPEED, wild.speed)
        assertEquals(2f, wild.volume)
        assertEquals(90, wild.rotation)
        assertEquals(1f, wild.brightness)
        val back = EditorOps.updateClip(project, "a") { it.copy(rotation = -90) }.clips[0]
        assertEquals(270, back.rotation)
        // A turned clip swaps its shape.
        assertTrue(back.aspect < 1f)
    }

    @Test
    fun captionsArePulledBackInsideAShorterVideo() {
        val withText = EditorOps.addText(project, EditText("t", "Hi", startMs = 8000, endMs = 10_000))
        val shorter = EditorOps.removeClip(withText, "b")
        assertEquals(7000, shorter.durationMs)
        val text = shorter.texts.single()
        assertTrue(text.endMs <= shorter.durationMs)
        assertEquals(2000, text.endMs - text.startMs)
        // With nothing left to show them on, captions go too.
        val empty = EditorOps.removeClip(EditorOps.removeClip(shorter, "a"), "c")
        assertTrue(empty.texts.isEmpty())
    }

    @Test
    fun shapeOfTheFinishedVideo() {
        assertEquals(16f / 9f, project.aspect, 0.001f)
        assertEquals(9f / 16f, project.copy(ratio = "9:16").aspect, 0.001f)
        assertEquals(1f, project.copy(ratio = "1:1").aspect, 0.001f)
        assertEquals(0.75f, project.copy(clips = listOf(photo("c"))).aspect, 0.001f)
    }
}
