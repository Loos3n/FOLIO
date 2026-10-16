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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryEvent {
    data class Message(val text: String) : LibraryEvent
    data class Open(val bookId: String) : LibraryEvent
}

class LibraryViewModel(private val app: FolioApp) : ViewModel() {
    private val library = app.library

    /** Most recently read first, then most recently added. */
    val books: StateFlow<List<Book>> = library.books
        .map { list -> list.sortedWith(compareByDescending<Book> { it.lastOpenedAt }.thenByDescending { it.addedAt }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
