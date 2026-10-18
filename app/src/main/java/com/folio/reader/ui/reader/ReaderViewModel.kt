package com.folio.reader.ui.reader

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.FolioApp
import com.folio.reader.data.Bookmark
import com.folio.reader.data.Highlight
import com.folio.reader.data.HighlightColor
import com.folio.reader.data.ReaderSettings
import com.folio.reader.epub.EpubBook
import com.folio.reader.epub.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One request to show a chapter. [token] makes repeated requests for the same place distinct. */
data class ChapterRequest(
    val index: Int,
    val offset: Float,
    val fragment: String?,
    /** Open at this character of the chapter's text (a highlight), rather than at [offset]. */
    val charOffset: Int? = null,
    /** Open at, and mark, match number [findIndex] of this search pattern. */
    val find: String? = null,
    val findIndex: Int = 0,
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
    /** Characters left in the table-of-contents chapter from the top of the screen. */
    val charsLeft: Int = 0,
)

class ReaderViewModel(private val app: FolioApp, private val bookId: String) : ViewModel() {

    private val library = app.library
    val settings: StateFlow<ReaderSettings> = app.settings.settings

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = library.books
        .map { books -> books.firstOrNull { it.id == bookId }?.bookmarks.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val highlights: StateFlow<List<Highlight>> = library.books
        .map { books -> books.firstOrNull { it.id == bookId }?.highlights.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The colour the last highlight was made in; new ones use it too. */
    var highlightColor = HighlightColor.Yellow

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: Job? = null
    private val plainTexts = ConcurrentHashMap<Int, String>()

    /** Whether this book is read by scrolling rather than in pages. */
    private val _scrollMode = MutableStateFlow(
        library.get(bookId)?.scrollMode ?: app.settings.settings.value.scrollDefault,
    )
    val scrollMode: StateFlow<Boolean> = _scrollMode.asStateFlow()

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
    /** For reading stats: when the reader last did something, and the page they were on. */
    private var lastActivity = 0L
    private var lastPage: Pair<Int, Int>? = null
    /** For learning reading speed: a recent place in the text and when the reader was there. */
    private var speedMark: Triple<Int, Int, Long>? = null

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
            val scroll = _scrollMode.value
            return ChapterStart(
                req.offset, req.fragment, epub.language, sections, req.charOffset, req.find, req.findIndex,
                scroll = scroll,
                nextTitle = if (scroll) nextTitle(epub, req.index) else null,
            )
        }
        return ChapterStart(0f, null, epub?.language, sections, scroll = _scrollMode.value)
    }

    /** What comes after chapter [index], to label the end of it in scrolling mode; null at the end of the book. */
    private fun nextTitle(epub: EpubBook, index: Int): String? {
        if (index >= epub.spine.lastIndex) return null
        val nextPath = epub.spine[index + 1].path
        // A file with no contents entry of its own carries on the chapter before it.
        return epub.toc.firstOrNull { it.path == nextPath }?.title?.takeIf { it.isNotBlank() } ?: "Continue"
    }

    /** Switches between pages and scrolling for this book, staying at the same place. New books follow the last choice. */
    fun setScrollMode(scroll: Boolean) {
        if (_scrollMode.value == scroll) return
        _scrollMode.value = scroll
        app.settings.update { it.copy(scrollDefault = scroll) }
        app.appScope.launch { library.setScrollMode(bookId, scroll) }
        if (book != null && _request.value != null) show(_state.value.chapterIndex, chapterOffset, null)
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
    fun onPage(info: PageInfo) {
        val (page, count, offset, section, sectionEnd) = info
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
                charsLeft = info.charsLeft,
            )
        }
        learnSpeed(_state.value.chapterIndex, info.charAt)
        updateProgress()
        scheduleSave()
        val here = _state.value.chapterIndex to page
        val turned = lastPage != null && lastPage != here
        lastPage = here
        recordActivity(if (turned) 1 else 0)
    }

    /**
     * Learns how fast the reader reads, from how far through the text they get over time,
     * skipping jumps (links, the contents, seeking) and long breaks.
     */
    private fun learnSpeed(chapter: Int, charAt: Int) {
        val now = SystemClock.elapsedRealtime()
        val mark = speedMark
        if (mark == null || mark.first != chapter || charAt < mark.second - 50 || now - mark.third > 600_000) {
            speedMark = Triple(chapter, charAt, now)
            return
        }
        val seconds = (now - mark.third) / 1000f
        if (seconds < 20f) return
        val rate = (charAt - mark.second) / seconds * 60f
        speedMark = Triple(chapter, charAt, now)
        if (rate < 150f || rate > 5000f) return // skimming, or not reading at all
        app.settings.update {
            val old = it.charsPerMinute
            it.copy(charsPerMinute = if (old <= 0f) rate else old * 0.8f + rate * 0.2f)
        }
    }

    /**
     * Counts reading time as the gaps between page turns (and read-aloud ticks), each capped
     * so a book left open on the table doesn't count as hours of reading.
     */
    fun recordActivity(pages: Int = 0) {
        val now = SystemClock.elapsedRealtime()
        val seconds = if (lastActivity == 0L) 0L else ((now - lastActivity) / 1000).coerceAtMost(180)
        lastActivity = now
        app.stats.record(bookId, seconds, pages)
    }

    fun resumeActivity() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    /** The app went to the background: count up to now, then stop the clock. */
    fun pauseActivity() {
        if (lastActivity != 0L) recordActivity()
        lastActivity = 0L
    }

    /** The bookmark on the page being shown, if there is one. */
    fun bookmarkHere(marks: List<Bookmark>, s: ReaderUiState): Bookmark? {
        if (!s.chapterReady || s.pageCount == 0) return null
        val last = (s.pageCount - 1).coerceAtLeast(1)
        return marks.firstOrNull { it.chapterIndex == s.chapterIndex && Math.round(it.chapterOffset * last) == s.page.coerceAtMost(last) }
    }

    fun addBookmark(snippet: String) {
        val s = _state.value
        if (book == null || !s.chapterReady) return
        val mark = Bookmark(
            id = UUID.randomUUID().toString(),
            chapterIndex = s.chapterIndex,
            chapterOffset = chapterOffset,
            progress = s.progress,
            chapterTitle = s.chapterTitle,
            snippet = snippet,
            createdAt = System.currentTimeMillis(),
        )
        app.appScope.launch { library.addBookmark(bookId, mark) }
    }

    fun removeBookmark(mark: Bookmark) {
        app.appScope.launch { library.removeBookmark(bookId, mark.id) }
    }

    fun openBookmark(mark: Bookmark) {
        val epub = book ?: return
        show(mark.chapterIndex.coerceIn(0, epub.spine.lastIndex), mark.chapterOffset, null)
    }

    /** Saves a new highlight over characters [start] to [end] of the chapter on screen. */
    fun addHighlight(start: Int, end: Int, text: String, note: String = ""): Highlight? {
        val s = _state.value
        if (book == null || !s.chapterReady) return null
        val pageOffset = if (s.pageCount > 1) s.page.toFloat() / (s.pageCount - 1) else 0f
        val mark = Highlight(
            id = UUID.randomUUID().toString(),
            chapterIndex = s.chapterIndex,
            start = start,
            end = end,
            text = text,
            color = highlightColor,
            note = note,
            progress = progressFor(s.chapterIndex, pageOffset),
            chapterTitle = s.chapterTitle,
            createdAt = System.currentTimeMillis(),
        )
        saveHighlight(mark)
        return mark
    }

    fun saveHighlight(mark: Highlight) {
        highlightColor = mark.color
        app.appScope.launch { library.saveHighlight(bookId, mark) }
    }

    fun removeHighlight(mark: Highlight) {
        app.appScope.launch { library.removeHighlight(bookId, mark.id) }
    }

    fun openHighlight(mark: Highlight) {
        val epub = book ?: return
        val index = mark.chapterIndex.coerceIn(0, epub.spine.lastIndex)
        show(index, chapterOffsetOf(index, mark.progress), null, charOffset = mark.start)
    }

    /** Searches the whole book, publishing matches chapter by chapter as they're found. */
    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        _search.value = SearchState(query = query, running = q.length >= 2)
        if (q.length < 2) return
        searchJob = viewModelScope.launch {
            delay(250) // wait for typing to pause
            val epub = book ?: return@launch
            val regex = Regex(BookSearch.pattern(q), RegexOption.IGNORE_CASE)
            val hits = mutableListOf<SearchHit>()
            var truncated = false
            for (i in epub.spine.indices) {
                val found = withContext(Dispatchers.Default) {
                    val text = plainTexts.getOrPut(i) {
                        epub.readBytes(epub.spine[i].path)?.let { BookSearch.plainText(String(it, Charsets.UTF_8)) }.orEmpty()
                    }
                    val title = epub.toc.getOrNull(tocIndexFor(epub, i))?.title ?: "Chapter ${i + 1}"
                    regex.findAll(text).take(BookSearch.MAX_HITS - hits.size + 1).mapIndexed { n, m ->
                        val (before, match, after) = BookSearch.excerpt(text, m.range.first, m.range.last + 1)
                        val at = if (text.isEmpty()) 0f else m.range.first.toFloat() / text.length
                        SearchHit(i, n, before, match, after, title, progressFor(i, at))
                    }.toList()
                }
                if (hits.size + found.size > BookSearch.MAX_HITS) {
                    hits += found.take(BookSearch.MAX_HITS - hits.size)
                    truncated = true
                } else {
                    hits += found
                }
                _search.update { it.copy(hits = hits.toList()) }
                if (truncated) break
            }
            _search.update { it.copy(running = false, truncated = truncated) }
        }
    }

    fun openSearchHit(hit: SearchHit) {
        val epub = book ?: return
        val index = hit.chapterIndex.coerceIn(0, epub.spine.lastIndex)
        show(index, chapterOffsetOf(index, hit.progress), null, find = BookSearch.pattern(_search.value.query), findIndex = hit.occurrence)
    }

    /** Where in chapter [index] a whole-book [progress] falls, 0..1. */
    private fun chapterOffsetOf(index: Int, progress: Float): Float {
        if (weights.isEmpty()) return 0f
        val before = weights.take(index).sum()
        return ((progress * totalWeight - before) / weights[index]).coerceIn(0f, 1f)
    }

    val language: String? get() = book?.language

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = app.settings.update(transform)

    fun currentOffset() = chapterOffset

    fun progressFor(index: Int, offset: Float): Float {
        if (weights.isEmpty()) return 0f
        val before = weights.take(index).sum()
        return ((before + offset * weights[index]) / totalWeight).coerceIn(0f, 1f)
    }

    private fun show(index: Int, offset: Float, fragment: String?, charOffset: Int? = null, find: String? = null, findIndex: Int = 0) {
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
                // The page reloads even within the same file; it reports ready again once laid out.
                chapterReady = false,
            )
        }
        updateProgress()
        _request.value = ChapterRequest(index, offset, fragment, charOffset, find, findIndex)
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
