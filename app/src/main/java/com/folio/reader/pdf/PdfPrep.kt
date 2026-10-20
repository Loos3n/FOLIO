package com.folio.reader.pdf

import android.util.Log
import com.folio.reader.FolioApp
import com.folio.reader.data.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gets PDFs ready to read as text in the background, one at a time: as soon as they're
 * added, and again when a newer version of the conversion can do better (keeping the
 * reader's place). Opening a PDF that's still being prepared just waits for it.
 */
class PdfPrep(private val app: FolioApp) {
    companion object {
        /** Bumped when the conversion improves; text made by an older one is redone. 2: pictures, tidier contents. */
        const val VERSION = 2
    }

    sealed interface Outcome {
        data object Ready : Outcome
        /** The PDF has no text to show (a scan). */
        data object Scanned : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val library = app.library
    private val one = Mutex()
    private val jobs = HashMap<String, Deferred<Outcome>>()

    /** Pages done and in all, for each PDF being prepared right now (0 to 0 while waiting its turn). */
    private val _progress = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val progress: StateFlow<Map<String, Pair<Int, Int>>> = _progress.asStateFlow()

    fun needsPrep(book: Book): Boolean = book.isPdf && when {
        book.textFileName != null -> book.textVersion < VERSION || !library.textReady(book)
        else -> !book.pdfPages
    }

    /** Prepares every PDF that needs it, in the order they were added. */
    fun prepareAll() {
        library.books.value.filter(::needsPrep).sortedBy { it.addedAt }.forEach { prepare(it.id) }
    }

    @Synchronized
    fun prepare(id: String): Deferred<Outcome> {
        jobs[id]?.takeIf { it.isActive }?.let { return it }
        _progress.update { it + (id to (0 to 0)) }
        val job = app.appScope.async {
            try {
                one.withLock { run(id) }
            } finally {
                _progress.update { it - id }
            }
        }
        jobs[id] = job
        return job
    }

    private suspend fun run(id: String): Outcome {
        val entry = library.get(id) ?: return Outcome.Failed("This book is no longer in your library.")
        if (!needsPrep(entry)) return if (library.textReady(entry)) Outcome.Ready else Outcome.Scanned
        val file = library.bookFile(entry)
        return try {
            val text = PdfTextConverter.extract(app, file) { done, total ->
                _progress.update { it + (id to (done to total)) }
            }
            if (text.scanned || text.chapters.isEmpty()) {
                // Text made earlier is kept; there's just nothing better to replace it with.
                if (!library.textReady(entry)) library.setPdfText(id, null, null)
                return if (library.textReady(entry)) Outcome.Ready else Outcome.Scanned
            }
            withContext(Dispatchers.IO) {
                val target = library.newTextFile(entry)
                val author = entry.author ?: text.author.takeIf(::meaningful)
                PdfBook.open(file).use { pdf ->
                    val cover = runCatching { pdf.render(0, 900) }.getOrNull()
                    try {
                        PdfEpubWriter.write(text, entry.title, author, cover, target) { page, l, t, r, b ->
                            pdf.renderRegion(page, l, t, r, b, 1200)
                        }
                    } finally {
                        cover?.recycle()
                    }
                }
                val old = library.textFile(entry)?.takeIf { it.exists() }
                val carryOver = old?.let { TextCarryOver.build(it, target, text.chapters.map { c -> c.title }) }
                library.setPdfText(id, target, author, VERSION, carryOver)
                // Removed from the library while this ran.
                if (library.get(id) == null) target.delete()
            }
            Outcome.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FolioPdf", "Couldn't get the text of $id", e)
            if (library.textReady(entry)) return Outcome.Ready
            library.setPdfPages(id, true)
            Outcome.Failed(e.message ?: "Couldn't read the text in this PDF.")
        }
    }

    private fun meaningful(s: String) =
        s.isNotBlank() && s.lowercase() !in setOf("anonymous", "unknown", "author", "user", "administrator", "untitled")
}
