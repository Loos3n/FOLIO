package com.folio.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What a restore brought back, for the message afterwards. */
data class RestoreSummary(
    val counts: RestoreCounts,
    /** Audiobooks whose audio files aren't on this phone and need adding again. */
    val audioToReAdd: List<String>,
)

/**
 * Saves the whole library to one zip file the reader picks (on the phone, a memory card or
 * Google Drive) and brings it back: the EPUB files and covers, every reading place,
 * bookmark, highlight, note and collection, looked-up words, added fonts, reading stats and settings. Audiobooks are
 * saved as their place and chapters only; their audio files stay where they are.
 */
class Backup(
    private val context: Context,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val stats: ReadingStats,
    private val words: WordList,
    private val fonts: UserFonts,
) {
    companion object {
        const val MIME = "application/zip"
        private const val INDEX = "folio-backup.json"
        private const val VERSION = 1
        /** Largest single book or cover a backup may unpack (guards against a doctored backup filling storage). */
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

        fun suggestedName() = "Folio backup ${LocalDate.now()}.zip"
    }

    /** Writes the backup to [uri]. Returns how many books it holds. */
    suspend fun write(uri: Uri): Int = withContext(Dispatchers.IO) {
        val books = library.books.value
        val index = JSONObject()
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("books", JSONArray().apply { books.forEach { put(it.toJson()) } })
            .put("stats", stats.toJson())
            .put("settings", settings.toJson())
            .put("words", words.toJson())
        val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri)
            ?: error("Couldn't write to that file.")
        ZipOutputStream(out.buffered()).use { zip ->
            // The index goes first, so a restore knows what it's reading before the big files.
            zip.putNextEntry(ZipEntry(INDEX))
            zip.write(index.toString().toByteArray())
            zip.closeEntry()
            val written = HashSet<String>()
            fun add(name: String, file: File?) {
                if (file == null || !file.isFile || !written.add(name)) return
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (book in books) {
                if (!book.isAudio) add("books/${book.fileName}", library.bookFile(book))
                book.coverFileName?.let { add("covers/$it", library.coverFile(book)) }
            }
            fonts.fonts.value.forEach { add("fonts/${it.fileName}", fonts.file(it.fileName)) }
        }
        books.size
    }

    /** Restores the backup at [uri] into the library, alongside the books already here. */
    suspend fun restore(uri: Uri): RestoreSummary = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            var index: JSONObject? = null
            val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open that file.")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = runCatching { zip.nextEntry }.getOrNull() ?: break
                    val name = entry.name
                    when {
                        entry.isDirectory -> Unit
                        name == INDEX -> index = runCatching { JSONObject(BookSafety.readLimited(zip, 64L * 1024 * 1024).toString(Charsets.UTF_8)) }.getOrNull()
                        safeFile(name) -> FileOutputStream(File(dir, name).apply { parentFile?.mkdirs() }).use {
                            BookSafety.copyLimited(zip, it, MAX_FILE_BYTES)
                        }
                    }
                }
            }
            val o = index ?: error("That isn't a Folio backup.")
            if (o.optInt("version") > VERSION) error("That backup was made by a newer version of Folio. Update Folio, then try again.")
            val books = o.optJSONArray("books")?.let { a ->
                (0 until a.length()).mapNotNull { runCatching { Book.fromJson(a.getJSONObject(it)) }.getOrNull() }
            }.orEmpty()
            val counts = library.restore(books, dir)
            o.optJSONObject("stats")?.let { stats.merge(it, counts.ids) }
            o.optJSONObject("settings")?.let { settings.restore(it) }
            o.optJSONArray("words")?.let { words.merge(it, counts.ids) }
            File(dir, "fonts").listFiles()?.forEach { f ->
                val target = File(fonts.dir, f.name)
                if (f.isFile && UserFonts.safeName(f.name) && !target.exists()) f.copyTo(target)
            }
            fonts.refresh()
            val missing = books.mapNotNull { b -> library.get(counts.ids[b.id] ?: b.id) }
                .filter { it.isAudio && library.audioMissing(it) }
                .map { it.title }
            RestoreSummary(counts, missing)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Only `books/<name>`, `covers/<name>` and `fonts/<name>`: nothing that could land outside the restore folder. */
    private fun safeFile(name: String): Boolean {
        val parts = name.split('/')
        return parts.size == 2 && parts[0] in setOf("books", "covers", "fonts") &&
            parts[1].isNotEmpty() && parts[1] != "." && parts[1] != ".." && '\\' !in parts[1]
    }
}
