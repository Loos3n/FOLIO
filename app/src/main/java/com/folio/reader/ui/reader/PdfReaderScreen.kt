package com.folio.reader.ui.reader

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.FolioApp
import com.folio.reader.data.Bookmark
import com.folio.reader.data.ReaderSettings
import com.folio.reader.data.ReaderTheme
import com.folio.reader.pdf.PdfBook
import com.folio.reader.ui.theme.ChromeStyle
import com.folio.reader.ui.theme.FolioTheme
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
import kotlin.math.roundToInt

data class PdfUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    /** Each page's width / height. */
    val ratios: List<Float> = emptyList(),
    val startPage: Int = 0,
    /** How far down [startPage] the screen started, 0..1. */
    val startOffset: Float = 0f,
)

class PdfReaderViewModel(private val app: FolioApp, private val bookId: String) : ViewModel() {
    private val library = app.library
    val settings: StateFlow<ReaderSettings> = app.settings.settings

    private val _state = MutableStateFlow(PdfUiState())
    val state: StateFlow<PdfUiState> = _state.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> = library.books
        .map { books -> books.firstOrNull { it.id == bookId }?.bookmarks.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @Volatile private var pdf: PdfBook? = null
    /** The latest drawing of each page, up to about 80 MB. */
    private val pages = object : LruCache<Int, Bitmap>(80 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private var saveJob: Job? = null
    private var lastActivity = 0L
    private var lastPage = -1

    init {
        viewModelScope.launch {
            val entry = library.get(bookId)
            if (entry == null) {
                _state.update { it.copy(loading = false, error = "This book is no longer in your library.") }
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val book = PdfBook.open(library.bookFile(entry))
                    pdf = book
                    book.aspectRatios()
                }
            }.onSuccess { ratios ->
                _state.update {
                    it.copy(
                        loading = false,
                        title = entry.title,
                        ratios = ratios,
                        startPage = entry.chapterIndex.coerceIn(0, (ratios.size - 1).coerceAtLeast(0)),
                        startOffset = entry.chapterOffset,
                    )
                }
                library.markOpened(bookId)
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't open this PDF.") }
            }
        }
    }

    fun cached(page: Int): Bitmap? = pages.get(page)

    suspend fun render(page: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { pdf?.render(page, widthPx) }.getOrNull()?.also { pages.put(page, it) }
    }

    /** Remembers the place a moment after the reader stops on it, and counts reading time. */
    fun onPlace(page: Int, offset: Float) {
        val count = _state.value.ratios.size.coerceAtLeast(1)
        saveJob?.cancel()
        saveJob = app.appScope.launch {
            delay(400)
            library.saveProgress(bookId, page, offset, ((page + 1f) / count).coerceIn(0f, 1f))
        }
        // Reading time is the gaps between reaching new pages, each capped (see ReaderViewModel).
        if (page == lastPage) return
        val now = SystemClock.elapsedRealtime()
        val seconds = if (lastActivity == 0L) 0L else ((now - lastActivity) / 1000).coerceAtMost(180)
        app.stats.record(bookId, seconds, if (lastPage < 0) 0 else 1)
        lastActivity = now
        lastPage = page
    }

    fun toggleBookmark(page: Int) {
        val existing = bookmarks.value.firstOrNull { it.chapterIndex == page }
        val count = _state.value.ratios.size.coerceAtLeast(1)
        viewModelScope.launch {
            if (existing != null) library.removeBookmark(bookId, existing.id)
            else library.addBookmark(
                bookId,
                Bookmark(
                    id = UUID.randomUUID().toString(),
                    chapterIndex = page,
                    chapterOffset = 0f,
                    progress = (page + 1f) / count,
                    chapterTitle = "Page ${page + 1}",
                    snippet = "",
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun removeBookmark(id: String) {
        viewModelScope.launch { library.removeBookmark(bookId, id) }
    }

    override fun onCleared() {
        pdf?.close()
        pdf = null
        pages.evictAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(bookId: String, onBack: () -> Unit) {
    val vm: PdfReaderViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PdfReaderViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FolioApp, bookId)
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val theme = settings.resolvedTheme(isSystemInDarkTheme())
    val chrome = when (theme) {
        ReaderTheme.Light -> ChromeStyle.Light
        ReaderTheme.Sepia -> ChromeStyle.Sepia
        ReaderTheme.Dark -> ChromeStyle.Dark
        ReaderTheme.Black -> ChromeStyle.Night
    }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showBookmarks by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pageCount = state.ratios.size

    /** The page across the middle of the screen. */
    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.firstOrNull { it.offset <= mid && it.offset + it.size > mid }?.index
                ?: listState.firstVisibleItemIndex
        }
    }

    LaunchedEffect(state.loading) {
        if (!state.loading && state.error == null) {
            delay(2_000)
            controlsVisible = false
        }
    }

    SystemBarsEffect(visible = controlsVisible || state.error != null, darkContent = theme.isDark)

    FolioTheme(style = chrome) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(theme.background)),
        ) {
            when {
                state.error != null -> ErrorView(state.error!!, onBack, Modifier.align(Alignment.Center))
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> PdfPages(
                    vm = vm,
                    state = state,
                    listState = listState,
                    theme = theme,
                    onTap = { controlsVisible = !controlsVisible },
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && state.error == null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                val marked = bookmarks.any { it.chapterIndex == currentPage }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                        }
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (pageCount > 0) {
                            IconButton(onClick = { showBookmarks = true }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Bookmarks")
                            }
                            IconButton(onClick = { vm.toggleBookmark(currentPage) }) {
                                Icon(
                                    if (marked) ReaderIcons.Bookmark else ReaderIcons.BookmarkBorder,
                                    contentDescription = if (marked) "Remove bookmark" else "Bookmark this page",
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible && pageCount > 0,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val shown = dragging?.roundToInt() ?: currentPage
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "Page ${shown + 1} of $pageCount",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (pageCount > 1) {
                            Slider(
                                value = dragging ?: currentPage.toFloat(),
                                onValueChange = { dragging = it },
                                onValueChangeFinished = {
                                    val target = dragging?.roundToInt()
                                    dragging = null
                                    if (target != null) scope.launch { listState.scrollToItem(target) }
                                },
                                valueRange = 0f..(pageCount - 1).toFloat(),
                            )
                        }
                    }
                }
            }
        }

        if (showBookmarks) {
            ModalBottomSheet(
                onDismissRequest = { showBookmarks = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Text(
                    "Bookmarks",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                if (bookmarks.isEmpty()) {
                    Text(
                        "Tap the bookmark at the top to save the page you're on.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(bookmarks, key = { it.id }) { mark ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showBookmarks = false
                                    scope.launch { listState.scrollToItem(mark.chapterIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
                                }
                                .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(mark.chapterTitle, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.removeBookmark(mark.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The pages in one scrolling column. Pinch or double-tap to zoom; when zoomed in, drag
 * sideways to see the rest of the page.
 */
@Composable
private fun PdfPages(
    vm: PdfReaderViewModel,
    state: PdfUiState,
    listState: LazyListState,
    theme: ReaderTheme,
    onTap: () -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val viewWidth = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        var zoom by rememberSaveable { mutableFloatStateOf(1f) }
        var panX by rememberSaveable { mutableFloatStateOf(0f) }
        val contentWidth = viewWidth * zoom

        fun clampPan(x: Float) = x.coerceIn(0f, (viewWidth * zoom - viewWidth).coerceAtLeast(0f))

        /** Zooms to [target] keeping the point under [centre] where it is. */
        fun zoomTo(target: Float, centre: Offset) {
            val old = zoom
            val new = target.coerceIn(1f, 4f)
            if (new == old) return
            val ratio = new / old
            zoom = new
            panX = ((panX + centre.x) * ratio - centre.x).coerceIn(0f, (viewWidth * new - viewWidth).coerceAtLeast(0f))
            // Items keep their index and pixel offset as they grow, so add the growth above the centre.
            listState.dispatchRawDelta((listState.firstVisibleItemScrollOffset + centre.y) * (ratio - 1f))
        }

        // Put the reader back where they left off.
        LaunchedEffect(Unit) {
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 && state.startPage + state.startOffset > 0f) {
                val ratio = state.ratios.getOrNull(state.startPage) ?: 0.707f
                val pageHeight = viewWidth * zoom / ratio
                listState.scrollToItem(state.startPage, (state.startOffset * pageHeight).roundToInt())
            }
        }

        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: return@collect
                    vm.onPlace(index, (offset.toFloat() / size.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
        }

        val filter = remember(theme) {
            when {
                theme.isDark -> ColorFilter.colorMatrix(
                    ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
                theme == ReaderTheme.Sepia -> ColorFilter.tint(Color(theme.background), BlendMode.Multiply)
                else -> null
            }
        }
        val pageBackground = if (theme.isDark) Color.Black else Color(if (theme == ReaderTheme.Sepia) theme.background else 0xFFFFFFFF)
        // Sharp enough at the zoom shown, without drawing pages bigger than about two screens wide.
        val renderWidth = (minOf(contentWidth, viewWidth * 2f) / 128f).roundToInt().coerceAtLeast(1) * 128

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(viewWidth) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                zoomTo(zoom * event.calculateZoom(), event.calculateCentroid(useCurrent = true))
                                val pan = event.calculatePan()
                                panX = clampPan(panX - pan.x)
                                listState.dispatchRawDelta(-pan.y)
                                event.changes.forEach { it.consume() }
                            } else if (fingers == 1 && zoom > 1f) {
                                // Sideways drags pan the zoomed page; the list still scrolls up and down.
                                val change = event.changes.first { it.pressed }
                                panX = clampPan(panX - change.positionChange().x)
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(viewWidth) {
                    detectTapGestures(
                        onTap = { currentOnTap() },
                        onDoubleTap = { at -> zoomTo(if (zoom > 1.05f) 1f else 2.5f, at) },
                    )
                },
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset { IntOffset(-panX.roundToInt(), 0) }
                    .width(with(density) { contentWidth.toDp() })
                    .fillMaxHeight(),
            ) {
                items(state.ratios.size) { index ->
                    PdfPage(
                        vm = vm,
                        index = index,
                        ratio = state.ratios[index],
                        renderWidth = renderWidth,
                        background = pageBackground,
                        filter = filter,
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    vm: PdfReaderViewModel,
    index: Int,
    ratio: Float,
    renderWidth: Int,
    background: Color,
    filter: ColorFilter?,
) {
    var bitmap by remember(index) { mutableStateOf(vm.cached(index)) }
    LaunchedEffect(index, renderWidth) {
        val shown = bitmap
        if (shown != null && shown.width == renderWidth) return@LaunchedEffect
        // While a pinch is under way, keep the current drawing until the zoom settles.
        if (shown != null) delay(250)
        vm.render(index, renderWidth)?.let { bitmap = it }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.1f, 10f))
            .background(background),
    ) {
        val image = remember(bitmap) { bitmap?.asImageBitmap() }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                colorFilter = filter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
