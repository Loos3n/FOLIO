package com.folio.reader.ui.library

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.FolioApp
import com.folio.reader.data.FreeBook
import com.folio.reader.data.FreeBooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Shelves to browse, as Gutenberg subjects and bookshelves; null is "most popular". */
private val TOPICS = listOf(
    null to "Popular",
    "adventure" to "Adventure",
    "detective" to "Mystery",
    "science fiction" to "Science fiction",
    "love" to "Romance",
    "horror" to "Horror",
    "children" to "Children's",
    "humor" to "Humour",
    "poetry" to "Poetry",
    "philosophy" to "Philosophy",
    "history" to "History",
)

sealed interface Getting {
    data object Downloading : Getting
    data class Added(val bookId: String) : Getting
    data object Failed : Getting
}

data class FreeBooksState(
    val books: List<FreeBook> = emptyList(),
    val next: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
)

class FreeBooksViewModel(private val app: FolioApp) : ViewModel() {
    val query = MutableStateFlow("")
    val topic = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(FreeBooksState())
    val state: StateFlow<FreeBooksState> = _state.asStateFlow()
    private val _getting = MutableStateFlow<Map<Int, Getting>>(emptyMap())
    val getting: StateFlow<Map<Int, Getting>> = _getting.asStateFlow()
    private var searchJob: Job? = null

    init {
        reload(0)
    }

    /** Searching looks through every book, so it leaves the shelf being browsed. */
    fun setQuery(q: String) {
        query.value = q
        if (q.isNotBlank()) topic.value = null
        reload(500) // wait for a pause in typing
    }

    fun setTopic(t: String?) {
        topic.value = t
        query.value = ""
        reload(0)
    }

    fun reload(delayMs: Long = 0) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(delayMs)
            _state.value = FreeBooksState(loading = true)
            val result = try {
                Result.success(FreeBooks.search(query.value, topic.value))
            } catch (e: CancellationException) {
                throw e // a newer search replaced this one
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.value = result.fold(
                    { FreeBooksState(it.books, it.next, loading = false) },
                    {
                        Log.w("FolioFree", "search: $it")
                        FreeBooksState(loading = false, error = true)
                    },
                )
        }
    }

    fun loadMore() {
        val s = _state.value
        val next = s.next ?: return
        if (s.loading || s.loadingMore) return
        _state.value = s.copy(loadingMore = true)
        val job = searchJob
        viewModelScope.launch {
            val page = runCatching { FreeBooks.page(next) }.getOrNull()
            if (searchJob !== job) return@launch // a new search started meanwhile
            _state.update { cur ->
                if (page == null) cur.copy(loadingMore = false)
                else cur.copy(books = (cur.books + page.books).distinctBy { it.id }, next = page.next, loadingMore = false)
            }
        }
    }

    /** The library book this one already is, if it's been added before. */
    fun inLibrary(book: FreeBook): String? {
        (_getting.value[book.id] as? Getting.Added)?.let { return it.bookId }
        val title = book.title.lowercase()
        return app.library.books.value.firstOrNull { !it.isAudio && it.title.lowercase().startsWith(title) }?.id
    }

    fun get(book: FreeBook) {
        if (_getting.value[book.id] == Getting.Downloading) return
        _getting.update { it + (book.id to Getting.Downloading) }
        viewModelScope.launch {
            val dir = File(app.cacheDir, "downloads")
            val result = runCatching {
                val file = FreeBooks.download(book, dir)
                try {
                    app.library.import(Uri.fromFile(file)).getOrThrow()
                } finally {
                    file.delete()
                }
            }
            result.onFailure { Log.w("FolioFree", "download ${book.epubUrl}: $it") }
            _getting.update { it + (book.id to (result.getOrNull()?.let { b -> Getting.Added(b.id) } ?: Getting.Failed)) }
        }
    }
}

/** Covers from Gutenberg, kept in memory while browsing. */
private object RemoteCovers {
    private val cache = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val bytes = runCatching { FreeBooks.coverBytes(url) }
            .onFailure { Log.w("FolioFree", "cover $url: $it") }
            .getOrNull() ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        if (bitmap == null) {
            Log.w("FolioFree", "cover $url: couldn't decode ${bytes.size} bytes")
            return null
        }
        cache.put(url, bitmap)
        return bitmap
    }
}

@Composable
fun FreeBooksScreen(vm: FreeBooksViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val topic by vm.topic.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val getting by vm.getting.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var expanded by remember { mutableStateOf<Int?>(null) }

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, state.books.size) { if (nearEnd && state.books.isNotEmpty()) vm.loadMore() }
    LaunchedEffect(query, topic) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.padding(start = 4.dp)) {
                Text("Free books", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Classics from Project Gutenberg, free to keep",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            SearchField(query = query, onQuery = vm::setQuery, placeholder = "Search 70,000 free books")
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(TOPICS) { (key, label) ->
                FilterChip(
                    selected = topic == key,
                    onClick = { vm.setTopic(key) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Couldn't reach Project Gutenberg. Check you're online and try again.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { vm.reload() }) { Text("Try again") }
                }
                state.books.isEmpty() -> Text(
                    if (query.isBlank()) "Nothing here yet." else "No free books match “${query.trim()}”.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 12.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.books, key = { it.id }) { book ->
                        val owned = getting[book.id].let { it as? Getting.Added }?.bookId ?: vm.inLibrary(book)
                        FreeBookRow(
                            book = book,
                            getting = if (owned != null) Getting.Added(owned) else getting[book.id],
                            expanded = expanded == book.id,
                            onToggle = { expanded = if (expanded == book.id) null else book.id },
                            onGet = { vm.get(book) },
                            onOpen = onOpen,
                        )
                    }
                    if (state.loadingMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeBookRow(
    book: FreeBook,
    getting: Getting?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onGet: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        val cover by produceState(book.coverUrl?.let { RemoteCovers.get(it) }, book.coverUrl) {
            if (value == null) book.coverUrl?.let { value = RemoteCovers.load(it) }
        }
        Box(
            Modifier
                .size(60.dp, 90.dp)
                .shadow(4.dp, CoverShape)
                .clip(CoverShape),
        ) {
            val image = cover
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                GeneratedCover(book.title, book.author, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = if (expanded) 4 else 2, overflow = TextOverflow.Ellipsis)
            book.author?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            book.summary?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            when (getting) {
                is Getting.Added -> TextButton(onClick = { onOpen(getting.bookId) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("In your library · Read")
                }
                Getting.Downloading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Adding to your library…", style = MaterialTheme.typography.bodyMedium)
                }
                Getting.Failed -> FilledTonalButton(onClick = onGet) { Text("Couldn't download · Try again") }
                null -> FilledTonalButton(onClick = onGet) { Text("Add to library") }
            }
        }
    }
}
