package com.folio.reader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.FolioApp
import com.folio.reader.data.Book
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Shelf(val label: String) { All("All"), Reading("Reading"), Unread("Not started"), Finished("Finished") }

enum class SortOrder(val label: String) { Recent("Recently read"), Added("Recently added"), Title("Title"), Author("Author") }

fun Book.isFinished() = progress >= 0.995f
fun Book.isReading() = lastOpenedAt > 0 && !isFinished()

sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
    data class Open(val bookId: String) : LibraryEvent
}

class LibraryViewModel(private val app: FolioApp) : ViewModel() {
    private val library = app.library

    val allBooks: StateFlow<List<Book>> = library.books

    val query = MutableStateFlow("")
    val shelf = MutableStateFlow(Shelf.All)
    val sort = MutableStateFlow(SortOrder.Recent)

    /** Books still being read, most recent first, for the "Continue reading" row. */
    val continueReading: StateFlow<List<Book>> = library.books
        .map { list -> list.filter { it.isReading() }.sortedByDescending { it.lastOpenedAt }.take(8) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The grid: filtered by shelf and search, in the chosen order. */
    val books: StateFlow<List<Book>> = combine(library.books, query, shelf, sort) { list, q, s, order ->
        val needle = q.trim()
        list
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
                    (it.author?.contains(needle, ignoreCase = true) == true)
            }
            .sortedWith(
                when (order) {
                    SortOrder.Recent -> compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt }
                    SortOrder.Added -> compareByDescending<Book> { it.addedAt }
                    SortOrder.Title -> compareBy<Book> { it.title.lowercase().removePrefix("the ").removePrefix("a ") }
                    SortOrder.Author -> compareBy<Book> { it.author?.lowercase() ?: "\uFFFF" }.thenBy { it.title.lowercase() }
                },
            )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            for (uri in uris) {
                library.import(uri)
                    .onSuccess { added++; lastBook = it }
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

    fun delete(book: Book) {
        viewModelScope.launch { library.delete(book) }
    }

    fun markUnread(book: Book) {
        viewModelScope.launch { library.resetProgress(book.id) }
    }
}
