package com.folio.reader.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.folio.reader.audio.AudioChapters
import com.folio.reader.epub.EpubBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** A saved page: which chapter file, where in it (0..1), and enough text to recognise it. */
data class Bookmark(
    val id: String,
    val chapterIndex: Int,
    val chapterOffset: Float,
    /** Whole-book progress at the bookmark, 0..1. */
    val progress: Float,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("chapterIndex", chapterIndex)
        .put("chapterOffset", chapterOffset.toDouble())
        .put("progress", progress.toDouble())
        .put("chapterTitle", chapterTitle)
        .put("snippet", snippet)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            chapterIndex = o.optInt("chapterIndex"),
            chapterOffset = o.optDouble("chapterOffset", 0.0).toFloat(),
            progress = o.optDouble("progress", 0.0).toFloat(),
            chapterTitle = o.optString("chapterTitle"),
            snippet = o.optString("snippet"),
            createdAt = o.optLong("createdAt"),
        )
    }
}

/** Highlight colours, as stored; the reader draws each in a soft tint that works on any theme. */
enum class HighlightColor(val key: String, val argb: Long) {
    Yellow("yellow", 0xFFF2C94C),
    Green("green", 0xFF6FCF97),
    Blue("blue", 0xFF56A8F2),
    Pink("pink", 0xFFF28AB2);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Yellow
    }
}

/**
 * A highlighted passage, located by character offsets into the chapter file's text
 * (counted the way the reader script walks it), with an optional note.
 */
data class Highlight(
    val id: String,
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: HighlightColor,
    val note: String,
    /** Whole-book progress at the passage, 0..1. */
    val progress: Float,
    val chapterTitle: String,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("chapterIndex", chapterIndex)
        .put("start", start)
        .put("end", end)
        .put("text", text)
        .put("color", color.key)
        .put("note", note)
        .put("progress", progress.toDouble())
        .put("chapterTitle", chapterTitle)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Highlight(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            chapterIndex = o.optInt("chapterIndex"),
            start = o.optInt("start"),
            end = o.optInt("end"),
            text = o.optString("text"),
            color = HighlightColor.of(o.optString("color")),
            note = o.optString("note"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            chapterTitle = o.optString("chapterTitle"),
            createdAt = o.optLong("createdAt"),
        )
    }
}

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val fileName: String,
    val coverFileName: String?,
    val addedAt: Long,
    val lastOpenedAt: Long = 0L,
    /** Whole-book progress, 0..1. */
    val progress: Float = 0f,
    val chapterIndex: Int = 0,
    /** Scroll position inside the current chapter, 0..1. */
    val chapterOffset: Float = 0f,
    /** Saved pages, in reading order. */
    val bookmarks: List<Bookmark> = emptyList(),
    /** Highlights and notes, in reading order. */
    val highlights: List<Highlight> = emptyList(),
    /** Names of the reader's own collections this book is in. */
    val collections: List<String> = emptyList(),
    /** Read by scrolling (true) or in pages (false); null follows the reader's default. */
    val scrollMode: Boolean? = null,
    /** Set for audiobooks: their tracks, chapters and listening position. */
    val audio: AudioInfo? = null,
) {
    val isAudio: Boolean get() = audio != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author ?: JSONObject.NULL)
        .put("fileName", fileName)
        .put("coverFileName", coverFileName ?: JSONObject.NULL)
        .put("addedAt", addedAt)
        .put("lastOpenedAt", lastOpenedAt)
        .put("progress", progress.toDouble())
        .put("chapterIndex", chapterIndex)
        .put("chapterOffset", chapterOffset.toDouble())
        .put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it.toJson()) } })
        .put("highlights", JSONArray().apply { highlights.forEach { put(it.toJson()) } })
        .put("collections", JSONArray(collections))
        .put("scrollMode", scrollMode ?: JSONObject.NULL)
        .put("audio", audio?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getString("id"),
            title = o.getString("title"),
            author = o.optString("author").takeIf { !o.isNull("author") && it.isNotEmpty() },
            fileName = o.getString("fileName"),
            coverFileName = o.optString("coverFileName").takeIf { !o.isNull("coverFileName") && it.isNotEmpty() },
            addedAt = o.optLong("addedAt"),
            lastOpenedAt = o.optLong("lastOpenedAt"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            chapterIndex = o.optInt("chapterIndex"),
            chapterOffset = o.optDouble("chapterOffset", 0.0).toFloat(),
            bookmarks = o.optJSONArray("bookmarks")?.let { a ->
                List(a.length()) { Bookmark.fromJson(a.getJSONObject(it)) }
            }.orEmpty(),
            highlights = o.optJSONArray("highlights")?.let { a ->
                List(a.length()) { Highlight.fromJson(a.getJSONObject(it)) }
            }.orEmpty(),
            collections = o.optJSONArray("collections")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            scrollMode = if (o.has("scrollMode") && !o.isNull("scrollMode")) o.optBoolean("scrollMode") else null,
            audio = o.optJSONObject("audio")?.let { AudioInfo.fromJson(it) },
        )
    }
}

/**
 * Stores imported books in app-private storage and keeps the library index in a small
 * JSON file. Small enough that a database would be overkill, and it loads instantly.
 */
class LibraryRepository(private val context: Context) {
    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav")
    }

    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "library.json")
    private val mutex = Mutex()

    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    fun bookFile(book: Book) = File(booksDir, book.fileName)

    fun coverFile(book: Book): File? = book.coverFileName?.let { File(coversDir, it) }?.takeIf { it.exists() }

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    /** Copies the EPUB at [uri] into the library. Returns the new (or already present) book. */
    suspend fun import(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = File(booksDir, "$id.epub")
        runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Couldn't open that file.")
            input.use { src -> FileOutputStream(target).use { dst -> src.copyTo(dst) } }

            val book = EpubBook(target).use { epub ->
                val existing = _books.value.firstOrNull {
                    it.title == epub.title && it.author == epub.author
                }
                if (existing != null) {
                    target.delete()
                    return@runCatching existing
                }
                val coverName = epub.coverPath?.let { saveCover(epub, it, id) }
                Book(
                    id = id,
                    title = epub.title.ifBlank { displayName(uri) ?: "Untitled" },
                    author = epub.author,
                    fileName = target.name,
                    coverFileName = coverName,
                    addedAt = System.currentTimeMillis(),
                )
            }
            mutate { it + book }
            book
        }.onFailure { target.delete() }
    }

    /**
     * Adds an audiobook made of the audio files at [uris] (one file, or several played in
     * name order). The files are played from where they are rather than copied, since
     * audiobooks are often hundreds of megabytes; Folio keeps permission to read them.
     */
    suspend fun importAudio(uris: List<Uri>): Result<Book> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        runCatching {
            val ordered = uris.map { it to (displayName(it) ?: it.lastPathSegment.orEmpty()) }
                .sortedWith { a, b -> naturalCompare(a.second, b.second) }
            var title: String? = null
            var author: String? = null
            var coverName: String? = null
            val tracks = ArrayList<AudioTrack>()
            val chapters = ArrayList<AudioChapter>()
            for ((index, pair) in ordered.withIndex()) {
                val (uri, name) = pair
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val retriever = MediaMetadataRetriever()
                val trackTitle: String
                val duration: Long
                try {
                    retriever.setDataSource(context, uri)
                    fun meta(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
                    duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    trackTitle = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name.substringBeforeLast('.')
                    if (index == 0) {
                        title = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                            ?: if (ordered.size == 1) meta(MediaMetadataRetriever.METADATA_KEY_TITLE) else null
                        author = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            ?: meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            ?: meta(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                            ?: meta(MediaMetadataRetriever.METADATA_KEY_WRITER)
                    }
                    if (coverName == null) retriever.embeddedPicture?.let { coverName = saveCover(it, id) }
                } catch (e: Exception) {
                    throw IllegalStateException("“$name” isn't an audio file Folio can play.", e)
                } finally {
                    runCatching { retriever.release() }
                }
                val marks = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                        FileInputStream(fd.fileDescriptor).channel.use { AudioChapters.read(it) }
                    }
                }.getOrNull().orEmpty()
                if (marks.isEmpty()) {
                    chapters += AudioChapter(trackTitle, index, 0)
                } else {
                    // Chapters after the first rarely start at exactly 0; the opening is still a chapter.
                    if (marks.first().startMs > 1_000) chapters += AudioChapter(trackTitle, index, 0)
                    marks.forEachIndexed { n, m -> chapters += AudioChapter(m.title.ifBlank { "Chapter ${n + 1}" }, index, m.startMs) }
                }
                tracks += AudioTrack(uri.toString(), trackTitle, duration)
            }
            val bookTitle = title ?: ordered.first().second.substringBeforeLast('.').ifBlank { "Untitled" }
            _books.value.firstOrNull { it.isAudio && it.title == bookTitle && it.author == author }?.let { existing ->
                coverName?.let { File(coversDir, it).delete() }
                return@runCatching existing
            }
            val book = Book(
                id = id,
                title = bookTitle,
                author = author,
                fileName = "",
                coverFileName = coverName,
                addedAt = System.currentTimeMillis(),
                audio = AudioInfo(tracks, chapters),
            )
            mutate { it + book }
            book
        }
    }

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        mutate { list -> list.filterNot { it.id == book.id } }
        if (book.isAudio) {
            book.audio?.tracks?.forEach { t ->
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(t.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        } else {
            bookFile(book).delete()
        }
        coverFile(book)?.delete()
    }

    /** Saves where the listener is in an audiobook. */
    suspend fun saveAudioPosition(id: String, track: Int, positionMs: Long, speed: Float) = update(id) { book ->
        val audio = book.audio ?: return@update book
        val next = audio.copy(track = track, positionMs = positionMs, speed = speed)
        book.copy(audio = next, progress = next.progress())
    }

    /** Fills in track lengths the file metadata didn't give (learned once the player has opened them). */
    suspend fun setTrackDuration(id: String, track: Int, durationMs: Long) = update(id) { book ->
        val audio = book.audio ?: return@update book
        val t = audio.tracks.getOrNull(track) ?: return@update book
        if (t.durationMs == durationMs) return@update book
        book.copy(audio = audio.copy(tracks = audio.tracks.toMutableList().also { it[track] = t.copy(durationMs = durationMs) }))
    }

    suspend fun markOpened(id: String) = update(id) { it.copy(lastOpenedAt = System.currentTimeMillis()) }

    suspend fun saveProgress(id: String, chapterIndex: Int, chapterOffset: Float, progress: Float) =
        update(id) { it.copy(chapterIndex = chapterIndex, chapterOffset = chapterOffset, progress = progress) }

    suspend fun addBookmark(id: String, bookmark: Bookmark) = update(id) { book ->
        book.copy(
            bookmarks = (book.bookmarks + bookmark)
                .sortedWith(compareBy<Bookmark>({ it.chapterIndex }, { it.chapterOffset })),
        )
    }

    suspend fun removeBookmark(id: String, bookmarkId: String) =
        update(id) { book -> book.copy(bookmarks = book.bookmarks.filterNot { it.id == bookmarkId }) }

    /** Adds [highlight], or replaces the one with the same id (e.g. after editing its note). */
    suspend fun saveHighlight(id: String, highlight: Highlight) = update(id) { book ->
        book.copy(
            highlights = (book.highlights.filterNot { it.id == highlight.id } + highlight)
                .sortedWith(compareBy<Highlight>({ it.chapterIndex }, { it.start })),
        )
    }

    suspend fun removeHighlight(id: String, highlightId: String) =
        update(id) { book -> book.copy(highlights = book.highlights.filterNot { it.id == highlightId }) }

    suspend fun setCollections(id: String, names: List<String>) =
        update(id) { it.copy(collections = names.map { n -> n.trim() }.filter { n -> n.isNotEmpty() }.distinct()) }

    suspend fun setScrollMode(id: String, scroll: Boolean) = update(id) { it.copy(scrollMode = scroll) }

    suspend fun resetProgress(id: String) =
        update(id) { it.copy(chapterIndex = 0, chapterOffset = 0f, progress = 0f, lastOpenedAt = 0L, audio = it.audio?.copy(track = 0, positionMs = 0)) }

    private suspend fun update(id: String, transform: (Book) -> Book) = withContext(Dispatchers.IO) {
        mutate { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private suspend fun mutate(transform: (List<Book>) -> List<Book>) = mutex.withLock {
        _books.update(transform)
        persist(_books.value)
    }

    private fun load(): List<Book> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val array = JSONArray(indexFile.readText())
        List(array.length()) { Book.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun persist(books: List<Book>) {
        val array = JSONArray()
        books.forEach { array.put(it.toJson()) }
        // Write-then-rename so a crash mid-write can't corrupt the library.
        val tmp = File(indexFile.parentFile, "library.json.tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(indexFile)
    }

    private fun saveCover(epub: EpubBook, path: String, id: String): String? =
        epub.readBytes(path)?.let { saveCover(it, id) }

    private fun saveCover(bytes: ByteArray, id: String): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Downsample to roughly 600px tall; plenty for a grid and keeps scrolling smooth.
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val name = "$id.jpg"
        FileOutputStream(File(coversDir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        name
    }.getOrNull()

    /** Whether the file at [uri] is audio (by its type, or its name when the type is vague). */
    fun isAudio(uri: Uri): Boolean {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        if (type.startsWith("audio/")) return true
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        return name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }

    /** Orders "Part 2" before "Part 10". */
    private fun naturalCompare(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val x = re.findAll(a.lowercase()).map { it.value }.toList()
        val y = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) (p.toBigInteger()).compareTo(q.toBigInteger()) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()
}
