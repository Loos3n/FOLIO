package com.folio.reader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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
        .put("bookmarks", JSONArray().apply { bookmarks.forEach { put(it.toJson()) } })
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
) {
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
        )
    }
}

/**
 * Stores imported books in app-private storage and keeps the library index in a small
 * JSON file. Small enough that a database would be overkill, and it loads instantly.
 */
class LibraryRepository(private val context: Context) {

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

    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        mutate { list -> list.filterNot { it.id == book.id } }
        bookFile(book).delete()
        coverFile(book)?.delete()
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

    suspend fun resetProgress(id: String) =
        update(id) { it.copy(chapterIndex = 0, chapterOffset = 0f, progress = 0f, lastOpenedAt = 0L) }

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

    private fun saveCover(epub: EpubBook, path: String, id: String): String? = runCatching {
        val bytes = epub.readBytes(path) ?: return null
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

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull()
}
