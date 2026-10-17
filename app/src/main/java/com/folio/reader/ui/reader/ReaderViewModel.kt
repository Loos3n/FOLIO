package com.folio.reader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.FolioApp
import com.folio.reader.data.ReaderSettings
import com.folio.reader.epub.EpubBook
import com.folio.reader.epub.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One request to show a chapter. [token] makes repeated requests for the same place distinct. */
data class ChapterRequest(
    val index: Int,
    val offset: Float,
    val fragment: String?,
    val token: Long = System.nanoTime(),
)

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val toc: List<TocEntry> = emptyList(),
    val chapterCount: Int = 0,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    /** Whole-book progress, 0..1. */
    val progress: Float = 0f,
    /** Index into [toc] of the entry the reader is currently in. */
    val currentTocIndex: Int = -1,
    /** Page within the current chapter, and how many pages it has (0 until laid out). */
    val page: Int = 0,
    val pageCount: Int = 0,
    /** Last page (within this file) of the table-of-contents chapter being read. */
    val sectionEnd: Int = 0,
    /** False while a chapter is being loaded and laid out. */
    val chapterReady: Boolean = false,
)

class ReaderViewModel(private val app: FolioApp, private val bookId: String) : ViewModel() {

    private val library = app.library
    val settings: StateFlow<ReaderSettings> = app.settings.settings

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _request = MutableStateFlow<ChapterRequest?>(null)
    val request: StateFlow<ChapterRequest?> = _request.asStateFlow()

    @Volatile var book: EpubBook? = null
        private set

    private var weights: List<Long> = emptyList()
    private var totalWeight = 1L
    @Volatile private var chapterOffset = 0f
    /** For the file on screen: TOC indices of the sections it contains, in reading order. */
    private var sectionToc: List<Int> = emptyList()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val entry = library.get(bookId)
            if (entry == null) {
                _state.update { it.copy(loading = false, error = "This book is no longer in your library.") }
                return@launch
            }
            val opened = withContext(Dispatchers.IO) { runCatching { EpubBook(library.bookFile(entry)) } }
            opened.onSuccess { epub ->
                book = epub
                weights = epub.spineWeights
                totalWeight = weights.sum().coerceAtLeast(1L)
                val index = entry.chapterIndex.coerceIn(0, epub.spine.lastIndex)
                chapterOffset = entry.chapterOffset
                _state.update {
                    it.copy(
                        loading = false,
                        title = entry.title,
                        toc = epub.toc,
                        chapterCount = epub.spine.size,
                    )
                }
                library.markOpened(bookId)
                show(index, entry.chapterOffset, null)
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't open this book.") }
            }
        }
    }

    /** Where to start a chapter when the WebView asks for it. */
    fun startFor(path: String): ChapterStart {
        val req = _request.value
        val epub = book
        val sections = epub?.toc.orEmpty().filter { it.path == path }.map { it.fragment.orEmpty() }
        if (req != null && epub != null && epub.spine.getOrNull(req.index)?.path == path) {
            return ChapterStart(req.offset, req.fragment, epub.language, sections)
        }
        return ChapterStart(0f, null, epub?.language, sections)
    }

    /** Re-issues the current position, e.g. for a freshly created WebView after rotation. */
    fun resume() {
        if (book == null || _request.value == null) return
        _request.value = ChapterRequest(_state.value.chapterIndex, chapterOffset, null)
    }

    fun hasNextChapter() = _state.value.let { it.chapterIndex < it.chapterCount - 1 }

    fun hasPrevChapter() = _state.value.chapterIndex > 0

    fun nextChapter() {
        if (hasNextChapter()) show(_state.value.chapterIndex + 1, 0f, null)
    }

    fun prevChapter() {
        if (hasPrevChapter()) show(_state.value.chapterIndex - 1, 1f, null)
    }

    fun openToc(entry: TocEntry) {
        val index = book?.spineIndexOf(entry.path)?.takeIf { it >= 0 } ?: return
        show(index, 0f, entry.fragment)
    }

    /** Handles a link inside the book; returns false if the path isn't part of the book. */
    fun openLink(path: String, fragment: String?): Boolean {
        val index = book?.spineIndexOf(path)?.takeIf { it >= 0 } ?: return false
        show(index, 0f, fragment)
        return true
    }

    fun seekTo(progress: Float) {
        if (weights.isEmpty()) return
        val target = progress.coerceIn(0f, 1f) * totalWeight
        var cumulative = 0L
        for ((i, w) in weights.withIndex()) {
            if (cumulative + w >= target || i == weights.lastIndex) {
                val offset = ((target - cumulative) / w).coerceIn(0f, 1f)
                show(i, offset, null)
                return
            }
            cumulative += w
        }
    }

    /** Called from the page whenever the reader lands on a page. */
    fun onPage(page: Int, count: Int, offset: Float, section: Int, sectionEnd: Int) {
        chapterOffset = offset
        val epub = book
        val tocIndex = sectionToc.getOrNull(section) ?: epub?.let { tocIndexBefore(it, _state.value.chapterIndex) } ?: -1
        _state.update {
            it.copy(
                page = page,
                pageCount = count,
                sectionEnd = sectionEnd.coerceIn(page, (count - 1).coerceAtLeast(page)),
                chapterReady = true,
                currentTocIndex = tocIndex,
                chapterTitle = epub?.toc?.getOrNull(tocIndex)?.title ?: it.chapterTitle,
            )
        }
        updateProgress()
        scheduleSave()
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = app.settings.update(transform)

    fun currentOffset() = chapterOffset

    fun progressFor(index: Int, offset: Float): Float {
        if (weights.isEmpty()) return 0f
        val before = weights.take(index).sum()
        return ((before + offset * weights[index]) / totalWeight).coerceIn(0f, 1f)
    }

    private fun show(index: Int, offset: Float, fragment: String?) {
        val epub = book ?: return
        chapterOffset = offset
        val path = epub.spine[index].path
        sectionToc = epub.toc.indices.filter { epub.toc[it].path == path }
        // A best guess until the page reports where it actually is.
        val toc = sectionToc.firstOrNull { epub.toc[it].fragment == fragment && fragment != null }
            ?: sectionToc.firstOrNull()?.takeIf { offset == 0f }
            ?: tocIndexFor(epub, index)
        _state.update {
            it.copy(
                chapterIndex = index,
                chapterTitle = epub.toc.getOrNull(toc)?.title ?: "Chapter ${index + 1}",
                currentTocIndex = toc,
                chapterReady = it.chapterReady && it.chapterIndex == index,
            )
        }
        updateProgress()
        _request.value = ChapterRequest(index, offset, fragment)
        scheduleSave()
    }

    private fun updateProgress() {
        val s = _state.value
        _state.update { it.copy(progress = progressFor(s.chapterIndex, chapterOffset)) }
    }

    private fun tocIndexFor(epub: EpubBook, index: Int): Int {
        // The last TOC entry that points at this chapter or an earlier one.
        var best = -1
        for ((i, entry) in epub.toc.withIndex()) {
            val spineIndex = epub.spineIndexOf(entry.path)
            if (spineIndex in 0..index) best = i
        }
        return best
    }

    /** The last TOC entry in a file before this one: the chapter still running at its top. */
    private fun tocIndexBefore(epub: EpubBook, index: Int): Int {
        var best = -1
        for ((i, entry) in epub.toc.withIndex()) {
            if (epub.spineIndexOf(entry.path) in 0 until index) best = i
        }
        return best
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            save()
        }
    }

    private fun save() {
        val s = _state.value
        if (book == null) return
        val offset = chapterOffset
        app.appScope.launch { library.saveProgress(bookId, s.chapterIndex, offset, s.progress) }
    }

    override fun onCleared() {
        save()
        val epub = book
        book = null
        // Let any in-flight WebView requests finish before the archive closes.
        app.appScope.launch(Dispatchers.IO) {
            delay(1_000)
            runCatching { epub?.close() }
        }
    }
}
