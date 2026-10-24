package com.folio.reader.ui.library

import com.folio.reader.data.ReaderSettings
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.FolioApp
import com.folio.reader.data.Backup
import com.folio.reader.data.Book
import android.provider.OpenableColumns
import com.folio.reader.data.AutoBackupState
import com.folio.reader.data.SavedWord
import com.folio.reader.data.StatsSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

enum class Shelf(val label: String) { All("All"), Reading("Reading"), Unread("Not started"), Finished("Finished") }

enum class SortOrder(val label: String) {
    Recent("Recently read"), Added("Recently added"), Title("Title"), Author("Author"), Series("Series"), Rating("Rating"),
}

fun Book.isFinished() = progress >= 0.995f
fun Book.isReading() = lastOpenedAt > 0 && !isFinished()

/** Books of one series shown as a single stack in the library, topped by the one to read next. */
data class SeriesStack(val name: String, val books: List<Book>, val next: Book)

/** The series' books in order, and the one to read next: the first not yet finished (else the last). */
private fun stackOf(books: List<Book>): SeriesStack {
    val ordered = books.sortedWith(compareBy<Book> { it.seriesIndex ?: Float.MAX_VALUE }.thenBy { it.title.lowercase() })
    return SeriesStack(ordered.first().series!!, ordered, ordered.firstOrNull { !it.isFinished() } ?: ordered.last())
}

private fun seriesKey(book: Book) = book.series?.trim()?.lowercase()

sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
    data class Open(val bookId: String) : LibraryEvent
    /** Worth more than a passing message: shown until dismissed. */
    data class Notice(val title: String, val text: String) : LibraryEvent
}

class LibraryViewModel(private val app: FolioApp) : ViewModel() {
    companion object {
        /** A backup to restore as if picked in the library (debug builds; used by the CI check). */
        val pendingRestore = MutableStateFlow<Uri?>(null)
    }

    private val library = app.library

    val allBooks: StateFlow<List<Book>> = library.books

    val query = MutableStateFlow("")
    val shelf = MutableStateFlow(Shelf.All)
    /** One of the reader's own collections to show instead of a shelf, or null. */
    val collection = MutableStateFlow<String?>(null)
    /** A series opened from its stack: the grid shows just its books, in order. */
    val openSeries = MutableStateFlow<String?>(null)
    val sort = MutableStateFlow(
        SortOrder.entries.firstOrNull { it.name == app.settings.settings.value.librarySort } ?: SortOrder.Recent,
    )

    val stats: StateFlow<StatsSnapshot> = app.stats.stats

    val goalMinutes: StateFlow<Int> = app.settings.settings
        .map { it.dailyGoalMinutes }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.settings.settings.value.dailyGoalMinutes)

    /** Display settings (fonts, theme, light), shared with the reader. */
    val display: StateFlow<ReaderSettings> = app.settings.settings

    fun updateDisplay(transform: (ReaderSettings) -> ReaderSettings) = app.settings.update(transform)

    fun setGoal(minutes: Int) = app.settings.update { it.copy(dailyGoalMinutes = minutes) }

    val yearGoal: StateFlow<Int> = app.settings.settings
        .map { it.yearlyBookGoal }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.settings.settings.value.yearlyBookGoal)

    fun setYearGoal(books: Int) = app.settings.update { it.copy(yearlyBookGoal = books) }

    /** Words looked up with Define, newest first. */
    val words: StateFlow<List<SavedWord>> = app.words.words

    fun removeWord(word: SavedWord) = app.words.remove(word.id)

    val autoBackup: StateFlow<AutoBackupState> = app.autoBackup.state

    fun turnOnAutoBackup(uri: Uri) {
        val name = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        app.autoBackup.turnOn(uri, name)
    }

    fun turnOffAutoBackup() = app.autoBackup.turnOff()

    fun autoBackupNow() = app.autoBackup.runNow()

    /** Every series name in use, for suggestions when editing a book. */
    val seriesNames: StateFlow<List<String>> = library.books
        .map { list -> list.mapNotNull { it.series }.distinct().sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every collection name in use, alphabetically. */
    val collections: StateFlow<List<String>> = library.books
        .map { list -> list.flatMap { it.collections }.distinct().sortedBy { it.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSort(order: SortOrder) {
        sort.value = order
        app.settings.update { it.copy(librarySort = order.name) }
    }

    fun showShelf(s: Shelf) {
        shelf.value = s
        collection.value = null
    }

    fun showCollection(name: String) {
        shelf.value = Shelf.All
        collection.value = name
    }

    fun setCollections(book: Book, names: List<String>) {
        viewModelScope.launch {
            library.setCollections(book.id, names)
            // Don't leave the grid on a collection that no longer exists.
            val c = collection.value
            if (c != null && library.books.value.none { c in it.collections }) collection.value = null
        }
    }

    /**
     * Books still being read, most recent first, for the "Continue reading" row; then, for a
     * series whose latest book was finished, the next one if it hasn't been started.
     */
    val continueReading: StateFlow<List<Book>> = library.books
        .map { list ->
            val reading = list.filter { it.isReading() }.sortedByDescending { it.lastOpenedAt }
            val upNext = upNext(list).filter { it !in reading }
            (reading + upNext).take(8)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** For each series, the unstarted book that follows the last one finished, latest finish first. */
    private fun upNext(list: List<Book>): List<Book> =
        list.filter { it.series != null }.groupBy { seriesKey(it) }.values.mapNotNull { books ->
            val ordered = stackOf(books).books
            val last = ordered.indexOfLast { it.isFinished() }
            if (last < 0 || ordered.any { it.isReading() }) return@mapNotNull null
            ordered.drop(last + 1).firstOrNull { it.lastOpenedAt == 0L }?.let { it to ordered[last].finishedAt }
        }.sortedByDescending { it.second }.map { it.first }

    fun openSeries(name: String) {
        openSeries.value = name
    }

    fun closeSeries() {
        openSeries.value = null
    }

    private val filters = combine(query, shelf, sort, collection, openSeries) { q, s, order, c, open -> Filters(q.trim(), s, order, c, open) }

    private data class Filters(val needle: String, val shelf: Shelf, val order: SortOrder, val collection: String?, val series: String?)

    /** Grid books with each series folded into its next book, and the stacks those stand for. */
    private val grid: StateFlow<Pair<List<Book>, Map<String, SeriesStack>>> = combine(library.books, filters, app.settings.settings) { list, f, settings ->
        val sorted = sortAndFilter(list, f)
        if (f.series != null) {
            val key = f.series.trim().lowercase()
            val members = list.filter { seriesKey(it) == key }
            if (members.isNotEmpty()) return@combine stackOf(members).books to emptyMap()
        }
        // Stacks only in the plain covers grid: a search, or the bookshelf, shows every book.
        if (!settings.groupSeries || settings.shelfView || f.needle.isNotEmpty()) return@combine sorted to emptyMap()
        val stacks = sorted.filter { it.series != null }.groupBy { seriesKey(it)!! }
            .filterValues { it.size > 1 }.mapValues { stackOf(it.value) }
        val shown = ArrayList<Book>()
        val byTop = HashMap<String, SeriesStack>()
        val placed = HashSet<String>()
        // Each stack takes the place of its first book in the chosen order.
        for (book in sorted) {
            val key = seriesKey(book)
            val stack = if (key != null) stacks[key] else null
            if (key == null || stack == null) shown += book
            else if (placed.add(key)) {
                shown += stack.next
                byTop[stack.next.id] = stack
            }
        }
        shown to byTop
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<Book>() to emptyMap())

    /** The grid: filtered by shelf and search, in the chosen order, with series stacked. */
    val books: StateFlow<List<Book>> = grid.map { it.first }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The stacks in [books], by the id of the book on top. */
    val stacks: StateFlow<Map<String, SeriesStack>> = grid.map { it.second }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun sortAndFilter(list: List<Book>, f: Filters): List<Book> {
        val needle = f.needle
        val s = f.shelf
        val c = f.collection
        val order = f.order
        return list
            .filter { c == null || c in it.collections }
            .filter {
                when (s) {
                    Shelf.All -> true
                    Shelf.Reading -> it.isReading()
                    Shelf.Unread -> it.lastOpenedAt == 0L
                    Shelf.Finished -> it.isFinished()
                }
            }
            .filter {
                needle.isEmpty() || it.title.contains(needle, ignoreCase = true) ||
                    (it.author?.contains(needle, ignoreCase = true) == true) ||
                    (it.series?.contains(needle, ignoreCase = true) == true)
            }
            .sortedWith(
                when (order) {
                    SortOrder.Recent -> compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt }
                    SortOrder.Added -> compareByDescending<Book> { it.addedAt }
                    SortOrder.Title -> compareBy<Book> { it.title.lowercase().removePrefix("the ").removePrefix("a ") }
                    SortOrder.Author -> compareBy<Book> { it.author?.lowercase() ?: "\uFFFF" }.thenBy { it.title.lowercase() }
                    // Each series together and in order; books not in one follow, by title.
                    SortOrder.Series -> compareBy<Book> { it.series?.lowercase() ?: "\uFFFF" }
                        .thenBy { it.seriesIndex ?: Float.MAX_VALUE }.thenBy { it.title.lowercase() }
                    SortOrder.Rating -> compareByDescending<Book> { it.rating }.thenByDescending { it.lastOpenedAt }
                },
            )
    }

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun coverFile(book: Book) = library.coverFile(book)

    fun import(uris: List<Uri>, openAfter: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            var added = 0
            var lastBook: Book? = null
            val failures = ArrayList<String>()
            // Audio files picked together make up one audiobook; each EPUB or PDF is its own book.
            val (audio, epubs) = withContext(Dispatchers.IO) { uris.partition { library.isAudio(it) } }
            if (audio.isNotEmpty()) {
                library.importAudio(audio)
                    .onSuccess { added++; lastBook = it }
                    .onFailure { failures += it.message ?: "Unknown error" }
            }
            for (uri in epubs) {
                library.import(uri)
                    .onSuccess {
                        added++
                        lastBook = it
                        // Not when it's about to be opened: opening it starts the same work.
                        if (it.isPdf && !openAfter) app.pdfPrep.prepare(it.id)
                    }
                    .onFailure { failures += it.message ?: "Unknown error" }
            }
            _importing.value = false
            val opened = lastBook
            when {
                openAfter && opened != null -> _events.send(LibraryEvent.Open(opened.id))
                failures.isNotEmpty() && added == 0 ->
                    _events.send(LibraryEvent.Message("Couldn't add that book. ${failures.first()}"))
                failures.isNotEmpty() ->
                    _events.send(LibraryEvent.Message("Added $added, but ${failures.size} couldn't be read."))
                added == 1 -> _events.send(LibraryEvent.Message("Added “${opened?.title}”"))
                added > 1 -> _events.send(LibraryEvent.Message("Added $added books"))
            }
        }
    }

    fun textReady(book: Book) = library.textReady(book)

    /** PDFs being got ready to read: pages done and in all. */
    val preparing: StateFlow<Map<String, Pair<Int, Int>>> = app.pdfPrep.progress

    fun showPdfPages(id: String) {
        // Saved before the page view opens, so it opens on the pages rather than the text.
        kotlinx.coroutines.runBlocking { library.setPdfPages(id, true) }
    }

    fun delete(book: Book) {
        viewModelScope.launch { library.delete(book) }
    }

    private val backup = Backup(app, library, app.settings, app.stats, app.words, app.fonts)

    init {
        viewModelScope.launch {
            pendingRestore.filterNotNull().collect {
                pendingRestore.value = null
                restore(it)
            }
        }
    }

    fun backupName() = Backup.suggestedName()

    fun backUp(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            val result = runCatching { backup.write(uri) }
            _importing.value = false
            result
                .onSuccess { n -> _events.send(LibraryEvent.Message(if (n == 1) "Backed up 1 book" else "Backed up $n books")) }
                .onFailure { _events.send(LibraryEvent.Message("Couldn't back up. ${it.message ?: ""}".trim())) }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            val result = runCatching { backup.restore(uri) }
            _importing.value = false
            result.onFailure {
                _events.send(LibraryEvent.Notice("Couldn't restore", it.message ?: "Something went wrong reading that file."))
            }
            val summary = result.getOrNull() ?: return@launch
            app.pdfPrep.prepareAll()
            val c = summary.counts
            val parts = ArrayList<String>()
            parts += when {
                c.added == 0 && c.merged == 0 -> if (c.skipped == 0) "There were no books in that backup." else "None of its books could be restored yet."
                c.merged == 0 -> if (c.added == 1) "Added 1 book with its place, highlights and notes." else "Added ${c.added} books with their places, highlights and notes."
                c.added == 0 -> "All ${c.merged} books were already here; their places, highlights and notes are up to date."
                else -> "Added ${c.added} and updated ${c.merged} already here, with places, highlights and notes."
            }
            if (c.skipped > 0) parts += when {
                summary.hadBookFiles -> "${c.skipped} couldn't be restored because the backup was missing their files."
                c.skipped == 1 -> "1 book in the backup isn't on this phone. Add it with Add books, then restore again to bring back its place and notes."
                else -> "${c.skipped} books in the backup aren't on this phone. Add them with Add books, then restore again to bring back their places and notes."
            }
            if (summary.audioToReAdd.isNotEmpty()) {
                val names = summary.audioToReAdd.joinToString { "“$it”" }
                parts += "To keep listening to $names, add the audio files again with Add books. Your place is kept."
            }
            parts += "Your settings and reading stats were restored too."
            _events.send(LibraryEvent.Notice("Backup restored", parts.joinToString("\n\n")))
        }
    }

    internal fun editDetails(book: Book, details: BookDetails) {
        viewModelScope.launch {
            library.setSeriesAndRating(book.id, details.series, details.seriesIndex, details.rating)
            library.editDetails(book.id, details.title, details.author, details.cover, details.removeCover)
                .onFailure { _events.send(LibraryEvent.Message("Couldn't save. ${it.message ?: ""}".trim())) }
        }
    }

    fun markUnread(book: Book) {
        viewModelScope.launch { library.resetProgress(book.id) }
    }
}
