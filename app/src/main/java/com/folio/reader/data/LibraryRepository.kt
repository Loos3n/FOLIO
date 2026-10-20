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
import com.folio.reader.pdf.PdfBook
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
import java.io.IOException
import java.util.UUID

/** What a restore did: books added, books already here that were updated, and books skipped. */
data class RestoreCounts(
    val added: Int,
    val merged: Int,
    val skipped: Int,
    /** Backup book id to the id it has in this library. */
    val ids: Map<String, String>,
)

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
    /** For a PDF: its text turned into an EPUB (in the books folder), read like any other book. */
    val textFileName: String? = null,
    /** Which version of the PDF-to-text conversion made [textFileName]; older ones are redone. */
    val textVersion: Int = 0,
    /** For a PDF: show its original pages rather than the text (chosen, or it has no text). */
    val pdfPages: Boolean = false,
    /** For a PDF's page view: the page the reader was on, and pages they bookmarked. */
    val pdfPage: Int = 0,
    val pdfMarks: List<Int> = emptyList(),
    /** The series this book is part of, and its number in it (1, 2, 2.5…). */
    val series: String? = null,
    val seriesIndex: Float? = null,
    /** The reader's own rating, 1..5 stars; 0 = not rated. */
    val rating: Int = 0,
    /** When the book was finished; 0 if it hasn't been. */
    val finishedAt: Long = 0L,
) {
    val isAudio: Boolean get() = audio != null

    val isPdf: Boolean get() = fileName.endsWith(".pdf")

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
        .put("textFileName", textFileName ?: JSONObject.NULL)
        .put("textVersion", textVersion)
        .put("pdfPages", pdfPages)
        .put("pdfPage", pdfPage)
        .put("pdfMarks", JSONArray(pdfMarks))
        .put("series", series ?: JSONObject.NULL)
        .put("seriesIndex", seriesIndex?.toDouble() ?: JSONObject.NULL)
        .put("rating", rating)
        .put("finishedAt", finishedAt)

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
            textFileName = o.optString("textFileName").takeIf { !o.isNull("textFileName") && it.isNotEmpty() },
            textVersion = o.optInt("textVersion"),
            pdfPages = o.optBoolean("pdfPages"),
            pdfPage = o.optInt("pdfPage"),
            pdfMarks = o.optJSONArray("pdfMarks")?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty(),
            series = o.optString("series").takeIf { !o.isNull("series") && it.isNotBlank() },
            seriesIndex = if (o.has("seriesIndex") && !o.isNull("seriesIndex")) o.optDouble("seriesIndex").toFloat() else null,
            rating = o.optInt("rating").coerceIn(0, 5),
            // Books finished before this was recorded count from when they were last opened.
            finishedAt = o.optLong("finishedAt").takeIf { it > 0 }
                ?: if (o.optDouble("progress", 0.0) >= FINISHED) o.optLong("lastOpenedAt") else 0L,
        )

        /** Progress at which a book counts as finished. */
        const val FINISHED = 0.995
    }
}

/**
 * Stores imported books in app-private storage and keeps the library index in a small
 * JSON file. Small enough that a database would be overkill, and it loads instantly.
 */
class LibraryRepository(private val context: Context) {
    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav")
        private const val MAX_COVER_BYTES = 30L * 1024 * 1024
    }

    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "library.json")
    private val mutex = Mutex()

    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    fun bookFile(book: Book) = File(booksDir, book.fileName)

    /** The EPUB to read: the book itself, or for a PDF the text version made from it. */
    fun readableFile(book: Book): File = textFile(book)?.takeIf { it.exists() } ?: bookFile(book)

    fun textFile(book: Book): File? = book.textFileName?.let { File(booksDir, it) }

    /** Whether a PDF's text version is ready to read. */
    fun textReady(book: Book): Boolean = textFile(book)?.exists() == true

    /** Where a PDF's text version is written: a new name each time, so a book open on the old one keeps working. */
    fun newTextFile(book: Book): File = File(booksDir, "${book.id}-text-${System.currentTimeMillis()}.epub")

    /**
     * Records [file] (or the lack of usable text, when null) for the PDF [id], made by
     * conversion [version]. When the text replaces an earlier one, [carryOver] moves the
     * reader's place, bookmarks and highlights across to it.
     */
    suspend fun setPdfText(id: String, file: File?, author: String?, version: Int = 0, carryOver: ((Book) -> Book)? = null) {
        val old = get(id)?.let(::textFile)
        update(id) { book ->
            if (file == null) book.copy(textFileName = null, pdfPages = true)
            else if (carryOver != null) {
                carryOver(book).copy(textFileName = file.name, textVersion = version, author = book.author ?: author)
            } else {
                // Bookmarks made on pages by an earlier version mark pages, not places in the text.
                val (pageMarks, kept) = book.bookmarks.partition { it.snippet.isEmpty() && it.chapterTitle.startsWith("Page ") }
                book.copy(
                    textFileName = file.name,
                    textVersion = version,
                    author = book.author ?: author,
                    chapterIndex = 0,
                    chapterOffset = 0f,
                    bookmarks = kept,
                    pdfMarks = (book.pdfMarks + pageMarks.map { it.chapterIndex }).distinct().sorted(),
                )
            }
        }
        if (old != null && old.name != file?.name) withContext(Dispatchers.IO) { old.delete() }
    }

    suspend fun setPdfPages(id: String, pages: Boolean) = update(id) { it.copy(pdfPages = pages) }

    suspend fun savePdfPage(id: String, page: Int, progress: Float) =
        update(id) { it.copy(pdfPage = page, progress = progress) }

    suspend fun togglePdfMark(id: String, page: Int) = update(id) { book ->
        book.copy(pdfMarks = if (page in book.pdfMarks) book.pdfMarks - page else (book.pdfMarks + page).sorted())
    }

    fun coverFile(book: Book): File? = book.coverFileName?.let { File(coversDir, it) }?.takeIf { it.exists() }

    fun get(id: String): Book? = _books.value.firstOrNull { it.id == id }

    /**
     * Copies the EPUB or PDF at [uri] into the library, after checking it really is one.
     * Returns the new (or already present) book.
     */
    suspend fun import(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val incoming = File(booksDir, "$id.part")
        var target: File? = null
        runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Couldn't open that file.")
            input.use { src -> FileOutputStream(incoming).use { dst -> src.copyTo(dst) } }

            val format = BookSafety.sniff(incoming)
                ?: error("That file isn't an EPUB or PDF book, so it wasn't added.")
            val file = File(booksDir, "$id.${format.extension}")
            if (!incoming.renameTo(file)) error("Couldn't save the book.")
            target = file
            val book = when (format) {
                BookFormat.Epub -> importEpub(file, id, uri)
                BookFormat.Pdf -> importPdf(file, id, uri)
            }
            if (book.id != id) {
                file.delete()
                return@runCatching book
            }
            mutate { it + book }
            book
        }.onFailure {
            incoming.delete()
            target?.delete()
        }
    }

    /** The book for a checked EPUB, or the copy already in the library. */
    private fun importEpub(file: File, id: String, uri: Uri): Book {
        BookSafety.checkEpub(file)
        return EpubBook(file).use { epub ->
            _books.value.firstOrNull { it.title == epub.title && it.author == epub.author }?.let { return it }
            val coverName = epub.coverPath?.let { saveCover(epub, it, id) }
            Book(
                id = id,
                title = epub.title.ifBlank { displayName(uri) ?: "Untitled" },
                author = epub.author,
                fileName = file.name,
                coverFileName = coverName,
                addedAt = System.currentTimeMillis(),
                series = epub.series,
                seriesIndex = epub.seriesIndex,
            )
        }
    }

    /** The book for a PDF (titled from its file name, with its first page as the cover). */
    private fun importPdf(file: File, id: String, uri: Uri): Book {
        val title = (displayName(uri) ?: uri.lastPathSegment?.substringBeforeLast('.'))?.replace('_', ' ')?.trim()?.ifBlank { null } ?: "Untitled PDF"
        val coverName = PdfBook.open(file).use { pdf ->
            if (pdf.pageCount == 0) error("This PDF has no pages.")
            pdf.renderCover()?.let { bitmap ->
                val name = "$id.jpg"
                FileOutputStream(File(coversDir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bitmap.recycle()
                name
            }
        }
        _books.value.firstOrNull { it.isPdf && it.title == title && bookFile(it).length() == file.length() }?.let {
            coverName?.let { n -> File(coversDir, n).delete() }
            return it
        }
        return Book(
            id = id,
            title = title,
            author = null,
            fileName = file.name,
            coverFileName = coverName,
            addedAt = System.currentTimeMillis(),
        )
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
                // Already in the library (perhaps restored from a backup made on another phone):
                // play it from these files, keeping the listener's place.
                val old = existing.audio ?: return@runCatching existing
                val keep = old.track < tracks.size
                val relinked = existing.copy(
                    audio = old.copy(
                        tracks = tracks,
                        chapters = chapters,
                        track = if (keep) old.track else 0,
                        positionMs = if (keep) old.positionMs else 0,
                    ),
                )
                mutate { list -> list.map { if (it.id == existing.id) relinked else it } }
                return@runCatching relinked
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

    /** True if any of an audiobook's files can't be opened on this phone (e.g. after a restore). */
    fun audioMissing(book: Book): Boolean = book.audio?.tracks?.any { t ->
        runCatching { context.contentResolver.openFileDescriptor(Uri.parse(t.uri), "r")!!.close() }.isFailure
    } ?: false

    /**
     * Adds the books from a backup, taking their files from [dir] (`books/` and `covers/`).
     * A book already here keeps its file and gains the backup's bookmarks, highlights and
     * collections, and its reading place if the backup's is more recent.
     */
    suspend fun restore(backup: List<Book>, dir: File): RestoreCounts = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _books.value.toMutableList()
            val ids = HashMap<String, String>()
            var added = 0
            var merged = 0
            var skipped = 0
            for (theirs in backup) {
                val i = list.indexOfFirst { it.id == theirs.id }.takeIf { it >= 0 }
                    ?: list.indexOfFirst { it.isAudio == theirs.isAudio && it.title == theirs.title && it.author == theirs.author }
                if (i >= 0) {
                    list[i] = mergeBook(list[i], theirs)
                    ids[theirs.id] = list[i].id
                    merged++
                    continue
                }
                if (!theirs.isAudio) {
                    val file = File(dir, "books/${theirs.fileName}")
                    val target = File(booksDir, theirs.fileName)
                    if (!file.isFile || target.exists()) {
                        skipped++
                        continue
                    }
                    file.copyTo(target)
                }
                val cover = theirs.coverFileName?.let { File(dir, "covers/$it") }?.takeIf { it.isFile }
                val coverTarget = theirs.coverFileName?.let { File(coversDir, it) }
                if (cover != null && coverTarget != null && !coverTarget.exists()) cover.copyTo(coverTarget)
                list += theirs.copy(coverFileName = theirs.coverFileName?.takeIf { coverTarget?.exists() == true }, textFileName = null)
                ids[theirs.id] = theirs.id
                added++
            }
            _books.value = list
            persist(list)
            RestoreCounts(added, merged, skipped, ids)
        }
    }

    private fun mergeBook(mine: Book, theirs: Book): Book {
        val newer = theirs.lastOpenedAt > mine.lastOpenedAt
        val place = if (newer) theirs else mine
        return mine.copy(
            lastOpenedAt = place.lastOpenedAt,
            progress = place.progress,
            chapterIndex = place.chapterIndex,
            chapterOffset = place.chapterOffset,
            bookmarks = (mine.bookmarks + theirs.bookmarks.filter { b -> mine.bookmarks.none { it.id == b.id } })
                .sortedWith(compareBy<Bookmark>({ it.chapterIndex }, { it.chapterOffset })),
            highlights = (mine.highlights + theirs.highlights.filter { h -> mine.highlights.none { it.id == h.id } })
                .sortedWith(compareBy<Highlight>({ it.chapterIndex }, { it.start })),
            collections = (mine.collections + theirs.collections).distinct(),
            pdfPage = place.pdfPage,
            pdfMarks = (mine.pdfMarks + theirs.pdfMarks).distinct().sorted(),
            scrollMode = mine.scrollMode ?: theirs.scrollMode,
            series = mine.series ?: theirs.series,
            seriesIndex = if (mine.series != null) mine.seriesIndex else theirs.seriesIndex,
            rating = if (mine.rating > 0) mine.rating else theirs.rating,
            finishedAt = if (mine.finishedAt > 0) mine.finishedAt else theirs.finishedAt,
            audio = mine.audio?.let { a ->
                val t = theirs.audio
                val placed = if (newer && t != null && t.track < a.tracks.size) a.copy(track = t.track, positionMs = t.positionMs, speed = t.speed) else a
                val marks = (a.bookmarks + t?.bookmarks.orEmpty().filter { b -> a.bookmarks.none { it.id == b.id } }).sortedBy { it.positionMs }
                placed.copy(bookmarks = marks)
            },
        )
    }

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        mutate { list -> list.filterNot { it.id == book.id } }
        if (book.isAudio) {
            book.audio?.tracks?.forEach { t ->
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(t.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        } else {
            bookFile(book).delete()
            textFile(book)?.delete()
        }
        coverFile(book)?.delete()
    }

    /** Saves where the listener is in an audiobook. */
    suspend fun saveAudioPosition(id: String, track: Int, positionMs: Long, speed: Float) = update(id) { book ->
        val audio = book.audio ?: return@update book
        val next = audio.copy(track = track, positionMs = positionMs, speed = speed)
        book.copy(audio = next, progress = next.progress())
    }

    /** Adds [bookmark] to an audiobook, or replaces the one with the same id (e.g. after editing its note). */
    suspend fun saveAudioBookmark(id: String, bookmark: AudioBookmark) = update(id) { book ->
        val audio = book.audio ?: return@update book
        val list = (audio.bookmarks.filterNot { it.id == bookmark.id } + bookmark).sortedBy { it.positionMs }
        book.copy(audio = audio.copy(bookmarks = list))
    }

    suspend fun removeAudioBookmark(id: String, bookmarkId: String) = update(id) { book ->
        val audio = book.audio ?: return@update book
        book.copy(audio = audio.copy(bookmarks = audio.bookmarks.filterNot { it.id == bookmarkId }))
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
        update(id) { it.copy(chapterIndex = 0, chapterOffset = 0f, progress = 0f, lastOpenedAt = 0L, finishedAt = 0L, audio = it.audio?.copy(track = 0, positionMs = 0)) }

    private suspend fun update(id: String, transform: (Book) -> Book) = withContext(Dispatchers.IO) {
        mutate { list -> list.map { if (it.id == id) stampFinished(transform(it)) else it } }
    }

    /** Notes when a book is first finished (kept if it's read again; "Mark as unread" clears it). */
    private fun stampFinished(after: Book): Book =
        if (after.progress >= Book.FINISHED && after.finishedAt == 0L) after.copy(finishedAt = System.currentTimeMillis()) else after

    /** Sets a book's series (and number in it) and star rating. */
    suspend fun setSeriesAndRating(id: String, series: String?, seriesIndex: Float?, rating: Int) = update(id) {
        val name = series?.trim()?.takeIf { s -> s.isNotEmpty() }
        it.copy(series = name, seriesIndex = seriesIndex?.takeIf { name != null }, rating = rating.coerceIn(0, 5))
    }

    /**
     * Fills in the series for EPUBs added before Folio read it from the book's own details.
     * Runs once; the reader's own edits are left alone.
     */
    suspend fun fillSeriesFromBooks() = withContext(Dispatchers.IO) {
        val found = _books.value.filter { !it.isAudio && !it.isPdf && it.series == null }.mapNotNull { b ->
            runCatching { EpubBook(bookFile(b)).use { e -> e.series?.let { b.id to (it to e.seriesIndex) } } }.getOrNull()
        }.toMap()
        if (found.isEmpty()) return@withContext
        mutate { list ->
            list.map { b -> found[b.id]?.takeIf { b.series == null }?.let { (s, i) -> b.copy(series = s, seriesIndex = i) } ?: b }
        }
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

    /**
     * Changes a book's title and author, and its cover when [cover] is given (a picture the
     * reader picked) or [removeCover] is set (back to the plain drawn cover).
     */
    suspend fun editDetails(id: String, title: String, author: String?, cover: Uri?, removeCover: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val book = get(id) ?: throw IOException("That book is no longer in your library.")
                // A new name each time, so covers already drawn on screen are replaced.
                val newCover = cover?.let { uri ->
                    val bytes = context.contentResolver.openInputStream(uri)?.use { BookSafety.readLimited(it, MAX_COVER_BYTES) }
                        ?: throw IOException("Couldn't open that picture.")
                    saveCover(bytes, "$id-${System.currentTimeMillis()}") ?: throw IOException("That file isn't a picture Folio can show.")
                }
                val coverName = when {
                    newCover != null -> newCover
                    removeCover -> null
                    else -> book.coverFileName
                }
                if (coverName != book.coverFileName) coverFile(book)?.delete()
                update(id) {
                    it.copy(
                        title = title.trim().ifEmpty { it.title },
                        author = author?.trim()?.takeIf { a -> a.isNotEmpty() },
                        coverFileName = coverName,
                    )
                }
                Unit
            }
        }

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
