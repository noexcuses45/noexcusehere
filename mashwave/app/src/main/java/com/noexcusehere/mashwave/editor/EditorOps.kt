package com.noexcusehere.mashwave.editor

import kotlin.math.roundToLong

/**
 * Every edit the editor can make, as plain functions from one project to the next.
 * Nothing here touches Android, so each rule can be checked by ordinary unit tests.
 */
object EditorOps {
    /** The shortest piece of a clip that may remain, measured in the original video's time. */
    const val MIN_PIECE_MS = 200L
    const val MIN_PHOTO_MS = 500L
    const val MAX_PHOTO_MS = 30_000L
    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4f
    const val MIN_TEXT_MS = 300L

    fun addClips(project: EditProject, clips: List<EditClip>, atIndex: Int = project.clips.size): EditProject {
        if (clips.isEmpty()) return project
        val at = atIndex.coerceIn(0, project.clips.size)
        return project.copy(clips = project.clips.take(at) + clips + project.clips.drop(at)).tidy()
    }

    fun removeClip(project: EditProject, id: String): EditProject =
        project.copy(clips = project.clips.filterNot { it.id == id }).tidy()

    /** Puts a copy of the clip right after the original. */
    fun duplicateClip(project: EditProject, id: String, newId: String): EditProject {
        val index = project.clips.indexOfFirst { it.id == id }
        if (index < 0) return project
        val copy = project.clips[index].copy(id = newId)
        return project.copy(clips = project.clips.take(index + 1) + copy + project.clips.drop(index + 1)).tidy()
    }

    /** Moves a clip earlier ([by] < 0) or later ([by] > 0) in the running order. */
    fun moveClip(project: EditProject, id: String, by: Int): EditProject {
        val index = project.clips.indexOfFirst { it.id == id }
        if (index < 0) return project
        val target = (index + by).coerceIn(0, project.clips.lastIndex)
        if (target == index) return project
        val list = project.clips.toMutableList()
        list.add(target, list.removeAt(index))
        return project.copy(clips = list).tidy()
    }

    fun updateClip(project: EditProject, id: String, change: (EditClip) -> EditClip): EditProject =
        project.copy(clips = project.clips.map { if (it.id == id) change(it).sane() else it }).tidy()

    /**
     * Cuts the clip playing at [timeMs] (finished-video time) in two at that moment.
     * Returns null when the cut would leave a sliver too short to be worth keeping.
     */
    fun splitAt(project: EditProject, timeMs: Long, newId: String): EditProject? {
        val (index, into) = project.clipAt(timeMs) ?: return null
        val clip = project.clips[index]
        val first: EditClip
        val second: EditClip
        if (clip.isImage) {
            if (into < MIN_PHOTO_MS || clip.endMs - into < MIN_PHOTO_MS) return null
            first = clip.copy(endMs = into)
            second = clip.copy(id = newId, endMs = clip.endMs - into)
        } else {
            val cut = clip.startMs + (into * clip.speed).roundToLong()
            if (cut - clip.startMs < MIN_PIECE_MS || clip.endMs - cut < MIN_PIECE_MS) return null
            first = clip.copy(endMs = cut)
            second = clip.copy(id = newId, startMs = cut)
        }
        return project.copy(clips = project.clips.take(index) + first + second + project.clips.drop(index + 1)).tidy()
    }

    /** Keeps only the part of the original between [startMs] and [endMs]. */
    fun trim(project: EditProject, id: String, startMs: Long, endMs: Long): EditProject = updateClip(project, id) { clip ->
        if (clip.isImage) {
            clip.copy(startMs = 0, endMs = endMs.coerceIn(MIN_PHOTO_MS, MAX_PHOTO_MS))
        } else {
            val limit = if (clip.sourceDurationMs > 0) clip.sourceDurationMs else maxOf(endMs, clip.endMs)
            val start = startMs.coerceIn(0, (limit - MIN_PIECE_MS).coerceAtLeast(0))
            val end = endMs.coerceIn(start + MIN_PIECE_MS, maxOf(limit, start + MIN_PIECE_MS))
            clip.copy(startMs = start, endMs = end)
        }
    }

    fun setMusic(project: EditProject, music: EditMusic?): EditProject = project.copy(music = music)

    fun addText(project: EditProject, text: EditText): EditProject = project.copy(texts = project.texts + text).tidy()

    fun updateText(project: EditProject, id: String, change: (EditText) -> EditText): EditProject =
        project.copy(texts = project.texts.map { if (it.id == id) change(it) else it }).tidy()

    fun removeText(project: EditProject, id: String): EditProject = project.copy(texts = project.texts.filterNot { it.id == id })

    /** Keeps numbers inside the ranges the engine can handle. */
    private fun EditClip.sane(): EditClip {
        val turns = ((rotation % 360) + 360) % 360 / 90 * 90
        return if (isImage) {
            copy(startMs = 0, endMs = endMs.coerceIn(MIN_PHOTO_MS, MAX_PHOTO_MS), speed = 1f, rotation = turns)
        } else {
            copy(
                speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
                volume = volume.coerceIn(0f, 2f),
                rotation = turns,
                brightness = brightness.coerceIn(-1f, 1f),
                contrast = contrast.coerceIn(-1f, 1f),
                saturation = saturation.coerceIn(-1f, 1f),
            )
        }
    }

    /** After the clips change, captions that now hang off the end are pulled back inside the video. */
    private fun EditProject.tidy(): EditProject {
        val total = durationMs
        if (texts.isEmpty()) return this
        if (clips.isEmpty()) return copy(texts = emptyList())
        val fixed = texts.map { text ->
            val length = (text.endMs - text.startMs).coerceIn(MIN_TEXT_MS, maxOf(total, MIN_TEXT_MS))
            val start = text.startMs.coerceIn(0, (total - length).coerceAtLeast(0))
            val end = (start + length).coerceAtMost(maxOf(total, start + 1))
            if (start == text.startMs && end == text.endMs) text else text.copy(startMs = start, endMs = end)
        }
        return if (fixed == texts) this else copy(texts = fixed)
    }
}
