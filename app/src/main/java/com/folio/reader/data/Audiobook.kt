package com.folio.reader.data

import org.json.JSONArray
import org.json.JSONObject

/** One audio file of an audiobook, played from where the reader picked it (a content or file URI). */
data class AudioTrack(val uri: String, val title: String, val durationMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("uri", uri).put("title", title).put("durationMs", durationMs)

    companion object {
        fun fromJson(o: JSONObject) = AudioTrack(o.optString("uri"), o.optString("title"), o.optLong("durationMs"))
    }
}

/** A chapter: which track it's in and where in that track it starts. */
data class AudioChapter(val title: String, val track: Int, val startMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("track", track).put("startMs", startMs)

    companion object {
        fun fromJson(o: JSONObject) = AudioChapter(o.optString("title"), o.optInt("track"), o.optLong("startMs"))
    }
}

/** What makes a library book an audiobook, and where the listener is in it. */
data class AudioInfo(
    val tracks: List<AudioTrack>,
    /** In listening order; never empty (a book with no chapter marks gets one per track). */
    val chapters: List<AudioChapter>,
    val track: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
) {
    val totalMs: Long get() = tracks.sumOf { it.durationMs }

    /** Milliseconds from the start of the book to [positionMs] in [track]. */
    fun absolute(track: Int, positionMs: Long): Long =
        tracks.take(track.coerceIn(0, tracks.size)).sumOf { it.durationMs } + positionMs

    fun absoluteOf(chapter: AudioChapter) = absolute(chapter.track, chapter.startMs)

    /** The chapter playing at [track]/[positionMs]. */
    fun chapterAt(track: Int, positionMs: Long): Int {
        val at = absolute(track, positionMs)
        var index = 0
        for ((i, c) in chapters.withIndex()) if (absoluteOf(c) <= at + 250) index = i
        return index
    }

    /** Where chapter [index] ends, from the start of the book. */
    fun chapterEnd(index: Int): Long = chapters.getOrNull(index + 1)?.let { absoluteOf(it) } ?: totalMs

    /** The track and position for a point [absoluteMs] into the book. */
    fun locate(absoluteMs: Long): Pair<Int, Long> {
        var rest = absoluteMs.coerceAtLeast(0)
        for ((i, t) in tracks.withIndex()) {
            if (rest < t.durationMs || i == tracks.lastIndex) return i to rest.coerceAtMost(t.durationMs)
            rest -= t.durationMs
        }
        return 0 to 0L
    }

    fun progress(): Float {
        val total = totalMs
        return if (total <= 0) 0f else (absolute(track, positionMs).toFloat() / total).coerceIn(0f, 1f)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
        .put("chapters", JSONArray().apply { chapters.forEach { put(it.toJson()) } })
        .put("track", track)
        .put("positionMs", positionMs)
        .put("speed", speed.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = AudioInfo(
            tracks = o.optJSONArray("tracks")?.let { a -> List(a.length()) { AudioTrack.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            chapters = o.optJSONArray("chapters")?.let { a -> List(a.length()) { AudioChapter.fromJson(a.getJSONObject(it)) } }.orEmpty(),
            track = o.optInt("track"),
            positionMs = o.optLong("positionMs"),
            speed = o.optDouble("speed", 1.0).toFloat(),
        )
    }
}
