@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.folio.reader.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.State
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import com.folio.reader.ui.reader.SettingsSheet
import com.folio.reader.ui.reader.ReaderIcons
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.folio.reader.data.HighlightExport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.folio.reader.FolioApp
import com.folio.reader.audio.ListenState
import com.folio.reader.ui.listen.ListenIcons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.data.AutoBackup
import com.folio.reader.data.Backup
import com.folio.reader.data.Book
import kotlin.math.roundToInt

/** EPUBs, PDFs, and audio files for audiobooks (M4B files often come through as octet-stream). */
private val BOOK_TYPES = arrayOf("application/epub+zip", "application/pdf", "application/octet-stream", "audio/*")
internal val CoverShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpen: (String) -> Unit,
    onFindFree: () -> Unit,
) {
    val allBooks by vm.allBooks.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val continueReading by vm.continueReading.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val collection by vm.collection.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val display by vm.display.collectAsStateWithLifecycle()
    val stacks by vm.stacks.collectAsStateWithLifecycle()
    val openSeries by vm.openSeries.collectAsStateWithLifecycle()
    BackHandler(enabled = openSeries != null) { vm.closeSeries() }
    // In an open series, the book to read next (first not finished) is marked.
    val upNext = if (openSeries != null) books.firstOrNull { !it.isFinished() } else null
    val shelfView = display.shelfView && openSeries == null
    val shelfWidth = LocalConfiguration.current.screenWidthDp - 52
    val shelves = remember(books, shelfView, shelfWidth) { if (shelfView) shelfRows(books, shelfWidth) else emptyList() }
    var showStats by remember { mutableStateOf(false) }
    // Search opens from the header; it stays open while there's something typed.
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var showDisplay by remember { mutableStateOf(false) }
    var showWords by remember { mutableStateOf(false) }
    var showAutoBackup by remember { mutableStateOf(false) }
    val autoBackupTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri: Uri? ->
        uri?.let(vm::turnOnAutoBackup)
    }
    var editingCollections by remember { mutableStateOf<Book?>(null) }
    var editingDetails by remember { mutableStateOf<Book?>(null) }
    val preparing by vm.preparing.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.import(uris)
    }
    val pickBooks = { picker.launch(BOOK_TYPES) }
    val backupTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri: Uri? ->
        uri?.let(vm::backUp)
    }
    val backupSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(vm::restore)
    }
    val backUp = { backupTarget.launch(vm.backupName()) }
    // Some file pickers label .zip as octet-stream, so allow both.
    val restore = { backupSource.launch(arrayOf(Backup.MIME, "application/x-zip-compressed", "application/octet-stream")) }
    var notice by remember { mutableStateOf<LibraryEvent.Notice?>(null) }
    val app = LocalContext.current.applicationContext as FolioApp
    val listening by app.audio.state.collectAsStateWithLifecycle()
    val playingBook = allBooks.firstOrNull { it.id == listening.bookId }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbar.showSnackbar(event.text)
                is LibraryEvent.Open -> onOpen(event.bookId)
                is LibraryEvent.Notice -> notice = event
            }
        }
    }

    val gridState = rememberLazyGridState()
    // Books arrive one after another when the library opens; later changes just move them.
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1_500)
        intro = false
    }
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex < 2 } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            if (allBooks.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = pickBooks,
                    // Spelled out only while the library is new; after that a round + is enough.
                    expanded = fabExpanded && allBooks.size < 3,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add books") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = if (playingBook == null) Modifier.padding(WindowInsets.navigationBars.asPaddingValues()) else Modifier,
                )
            }
        },
        bottomBar = {
            if (playingBook != null) {
                MiniPlayer(
                    book = playingBook,
                    state = listening,
                    cover = vm.coverFile(playingBook),
                    onOpen = { onOpen(playingBook.id) },
                    onToggle = app.audio::toggle,
                    onClose = app.audio::stop,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (allBooks.isEmpty()) {
            EmptyLibrary(importing = importing, onAdd = pickBooks, onFindFree = onFindFree, onRestore = restore, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 104.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Header(
                        count = allBooks.size,
                        importing = importing,
                        searching = searching,
                        onSearch = {
                            if (searching) vm.query.value = ""
                            searching = !searching
                        },
                        onStats = { showStats = true },
                        onDisplay = { showDisplay = true },
                        onWords = { showWords = true },
                        onAutoBackup = { showAutoBackup = true },
                        onFindFree = onFindFree,
                        onBackUp = backUp,
                        onRestore = restore,
                    )
                }
                if (searching) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                        val focus = remember { FocusRequester() }
                        SearchField(query = query, onQuery = { vm.query.value = it }, modifier = Modifier.focusRequester(focus))
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    }
                }
                if (openSeries != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "series") {
                        SeriesHeader(name = books.firstOrNull()?.series ?: openSeries!!, books = books, onBack = vm::closeSeries)
                    }
                } else if (continueReading.isNotEmpty() && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        ContinueReadingRow(continueReading, vm, onOpen)
                    }
                }
                if (openSeries == null) item(span = { GridItemSpan(maxLineSpan) }, key = "shelves") {
                    ShelfChips(
                        shelf = shelf,
                        collection = collection,
                        collections = collections,
                        onShelf = vm::showShelf,
                        onCollection = vm::showCollection,
                        sort = sort,
                        onSort = vm::setSort,
                        shelfView = shelfView,
                        onShelfView = { on -> vm.updateDisplay { it.copy(shelfView = on) } },
                        groupSeries = display.groupSeries,
                        onGroupSeries = { on -> vm.updateDisplay { it.copy(groupSeries = on) } },
                    )
                }
                if (books.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "nothing") {
                        Text(
                            if (query.isNotBlank()) "No books match “${query.trim()}”." else "Nothing on this shelf yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                        )
                    }
                }
                if (shelfView) {
                    shelves.forEachIndexed { index, row ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "shelf-" + row.first().id) {
                            ShelfRow(
                                books = row,
                                vm = vm,
                                onOpen = { onOpen(it.id) },
                                onDelete = { pendingDelete = it },
                                onCollections = { editingCollections = it },
                                onEdit = { editingDetails = it },
                                modifier = Modifier.animateItem().entrance(index, intro),
                            )
                        }
                    }
                } else items(books, key = { it.id }) { book ->
                    val index = books.indexOf(book)
                    val stack = stacks[book.id]
                    BookTile(
                        book = book,
                        vm = vm,
                        stack = stack,
                        upNext = book == upNext && book.lastOpenedAt == 0L,
                        onOpen = { if (stack != null) vm.openSeries(stack.name) else onOpen(book.id) },
                        onDelete = { pendingDelete = book },
                        onCollections = { editingCollections = book },
                        onEdit = { editingDetails = book },
                        preparing = preparing[book.id],
                        modifier = Modifier.animateItem().entrance(index, intro),
                    )
                }
                if (query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "free") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Looking for something new?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onFindFree) { Text("Find free books") }
                        }
                    }
                }
            }
            // Keeps scrolled content from running under the status bar icons.
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f)),
            )
        }
    }

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(n.title) },
            text = { Text(n.text) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("OK") } },
        )
    }
    if (showDisplay) {
        SettingsSheet(
            settings = display,
            scrollMode = display.scrollDefault,
            onScrollMode = { on -> vm.updateDisplay { it.copy(scrollDefault = on) } },
            onChange = vm::updateDisplay,
            onDismiss = { showDisplay = false },
        )
    }
    if (showStats) {
        val stats by vm.stats.collectAsStateWithLifecycle()
        val goal by vm.goalMinutes.collectAsStateWithLifecycle()
        val yearGoal by vm.yearGoal.collectAsStateWithLifecycle()
        StatsSheet(
            stats = stats,
            books = allBooks,
            goalMinutes = goal,
            onSetGoal = vm::setGoal,
            yearGoal = yearGoal,
            onSetYearGoal = vm::setYearGoal,
            onDismiss = { showStats = false },
        )
    }
    if (showAutoBackup) {
        val state by vm.autoBackup.collectAsStateWithLifecycle()
        AutoBackupDialog(
            state = state,
            onChoose = { autoBackupTarget.launch(AutoBackup.FILE_NAME) },
            onNow = vm::autoBackupNow,
            onOff = vm::turnOffAutoBackup,
            onDismiss = { showAutoBackup = false },
        )
    }
    if (showWords) {
        val words by vm.words.collectAsStateWithLifecycle()
        WordListSheet(words = words, onRemove = vm::removeWord, onDismiss = { showWords = false })
    }
    editingCollections?.let { book ->
        CollectionsDialog(
            book = book,
            existing = collections,
            onSave = { names ->
                vm.setCollections(book, names)
                editingCollections = null
            },
            onDismiss = { editingCollections = null },
        )
    }

    editingDetails?.let { book ->
        EditDetailsDialog(
            book = book,
            cover = vm.coverFile(book),
            seriesNames = vm.seriesNames.collectAsStateWithLifecycle().value,
            onSave = { details ->
                vm.editDetails(book, details)
                editingDetails = null
            },
            onDismiss = { editingDetails = null },
        )
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Remove book?") },
            text = { Text("“${book.title}” and your place in it will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(book)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

/** A progress value that fills from empty when first shown, then follows changes. */
@Composable
internal fun rememberGrow(target: Float): State<Float> {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) { value.animateTo(target, tween(700, easing = FastOutSlowInEasing)) }
    return value.asState()
}

/** Fades and lifts an item in when the library first appears, one after another. */
@Composable
private fun Modifier.entrance(index: Int, play: Boolean): Modifier {
    if (!play) return this
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(45L * index.coerceAtMost(12))
        shown.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 28.dp.toPx()
    }
}

@Composable
private fun Header(
    count: Int,
    importing: Boolean,
    searching: Boolean,
    onSearch: () -> Unit,
    onStats: () -> Unit,
    onDisplay: () -> Unit,
    onWords: () -> Unit,
    onAutoBackup: () -> Unit,
    onFindFree: () -> Unit,
    onBackUp: () -> Unit,
    onRestore: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library", style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (count == 1) "1 book" else "$count books",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSearch) {
                Icon(
                    if (searching) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searching) "Close search" else "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onStats) {
                Icon(LibraryIcons.Stats, contentDescription = "Reading stats", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Display and light") },
                        leadingIcon = { Icon(ReaderIcons.Brightness, contentDescription = null) },
                        onClick = {
                            moreOpen = false
                            onDisplay()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Word list") },
                        onClick = {
                            moreOpen = false
                            onWords()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Find free books") },
                        onClick = {
                            moreOpen = false
                            onFindFree()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Back up library") },
                        enabled = !importing,
                        onClick = {
                            moreOpen = false
                            onBackUp()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Automatic backup…") },
                        onClick = {
                            moreOpen = false
                            onAutoBackup()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Restore from backup") },
                        enabled = !importing,
                        onClick = {
                            moreOpen = false
                            onRestore()
                        },
                    )
                }
            }
        }
        AnimatedVisibility(importing) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
internal fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    placeholder: String = "Search titles and authors",
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
            ) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingRow(books: List<Book>, vm: LibraryViewModel, onOpen: (String) -> Unit) {
    Column {
        Text("Continue reading", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // Let cards scroll edge to edge past the grid's side padding.
            contentPadding = PaddingValues(horizontal = 20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .layoutBleed(20.dp),
        ) {
            items(books, key = { it.id }) { book -> ContinueCard(book, vm, onOpen, single = books.size == 1) }
        }
    }
}

/** Widens a full-span grid item by [bleed] on each side so a carousel can reach the screen edges. */
private fun Modifier.layoutBleed(bleed: Dp) = this.then(
    Modifier.layout { measurable, constraints ->
        val extra = (bleed * 2).roundToPx()
        val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra, minWidth = constraints.minWidth + extra))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    },
)

@Composable
private fun ContinueCard(book: Book, vm: LibraryViewModel, onOpen: (String) -> Unit, single: Boolean) {
    val grow = rememberGrow(book.progress)
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { onOpen(book.id) },
        shape = RoundedCornerShape(20.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = Modifier.width(if (single) (LocalConfiguration.current.screenWidthDp - 40).dp else 280.dp),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer.copy(alpha = 0.6f))),
            ),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    title = book.title,
                    author = book.author,
                    file = vm.coverFile(book),
                    modifier = Modifier
                        .width(52.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(6.dp, CoverShape)
                        .clip(CoverShape),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.author != null) {
                        Text(
                            book.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // A suggested next book in a series hasn't been started, so it says why it's here.
                    if (book.lastOpenedAt == 0L) {
                        Text(
                            "Next in " + (book.series ?: "series") + (book.seriesIndex?.let { " · Book " + seriesNumber(it) } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { grow.value },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(CircleShape),
                            color = scheme.primary,
                            trackColor = scheme.onPrimaryContainer.copy(alpha = 0.12f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${(book.progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

/** Which books show (shelves and collections), how they're ordered, and covers or a bookshelf. */
@Composable
private fun ShelfChips(
    shelf: Shelf,
    collection: String?,
    collections: List<String>,
    onShelf: (Shelf) -> Unit,
    onCollection: (String) -> Unit,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    shelfView: Boolean,
    onShelfView: (Boolean) -> Unit,
    groupSeries: Boolean,
    onGroupSeries: (Boolean) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                .horizontalScroll(rememberScrollState()),
        ) {
            Shelf.entries.forEach { option ->
                FilterChip(
                    selected = collection == null && shelf == option,
                    onClick = { onShelf(option) },
                    label = { Text(option.label) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
            collections.forEach { name ->
                FilterChip(
                    selected = collection == name,
                    onClick = { onCollection(name) },
                    label = { Text(name) },
                    leadingIcon = { Icon(LibraryIcons.Collection, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                )
            }
        }
        Box {
            IconButton(onClick = { sortOpen = true }) {
                Icon(LibraryIcons.Sort, contentDescription = "Order: ${sort.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                SortOrder.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = {
                            if (option == sort) Icon(Icons.Filled.Check, contentDescription = null)
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            onSort(option)
                            sortOpen = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Stack series") },
                    leadingIcon = {
                        if (groupSeries) Icon(Icons.Filled.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        onGroupSeries(!groupSeries)
                        sortOpen = false
                    },
                )
            }
        }
        IconButton(onClick = { onShelfView(!shelfView) }, modifier = Modifier.offset(x = 8.dp)) {
            Icon(
                if (shelfView) LibraryIcons.Grid else LibraryIcons.Shelf,
                contentDescription = if (shelfView) "Show covers" else "Show as a bookshelf",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The top of an opened series: its name, how far through it the reader is, and a way back. */
@Composable
private fun SeriesHeader(name: String, books: List<Book>, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val done = books.count { it.isFinished() }
            Text(
                "${books.size} books · $done read",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookTile(
    book: Book,
    vm: LibraryViewModel,
    stack: SeriesStack?,
    upNext: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onCollections: () -> Unit,
    onEdit: () -> Unit,
    preparing: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val grow = rememberGrow(book.progress)
    Column(modifier) {
        Box {
            // A series shows as a little pile: the next book on top, the edges of the rest behind it.
            if (stack != null) {
                for (layer in 2 downTo 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .graphicsLayer {
                                translationX = (layer * 5).dp.toPx()
                                translationY = (-layer * 5).dp.toPx()
                                scaleX = 1f - layer * 0.04f
                                scaleY = 1f - layer * 0.04f
                            }
                            .shadow(3.dp, CoverShape)
                            .clip(CoverShape)
                            .background(
                                if (layer == 1) MaterialTheme.colorScheme.surfaceContainerHighest
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(8.dp, CoverShape, ambientColor = MaterialTheme.colorScheme.onSurface)
                    .clip(CoverShape)
                    .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
            ) {
                BookCover(book.title, book.author, vm.coverFile(book), Modifier.fillMaxSize())
                if (book.isAudio) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    ) {
                        Icon(
                            ListenIcons.Headphones,
                            contentDescription = "Audiobook",
                            modifier = Modifier.padding(5.dp).size(14.dp),
                        )
                    }
                }
                when {
                    stack != null -> Badge("${stack.books.size} books", Modifier.align(Alignment.TopEnd))
                    book.isFinished() -> Badge("Finished", Modifier.align(Alignment.TopEnd), icon = true)
                    upNext -> Badge("Up next", Modifier.align(Alignment.TopEnd))
                    book.lastOpenedAt == 0L -> Badge("New", Modifier.align(Alignment.TopEnd))
                }
            }
        }
        if (preparing != null && !book.isReading()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                val (done, total) = preparing
                Text(
                    if (total > 0) "Getting ready · ${done * 100 / total}%" else "Getting ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        } else if (book.isReading()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { grow.value },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(book.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        } else {
            Spacer(Modifier.height(10.dp))
        }
        Row {
            if (stack != null) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stack.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val done = stack.books.count { it.isFinished() }
                    Text(
                        if (done == stack.books.size) "All ${stack.books.size} read"
                        else "Next: " + (book.seriesIndex?.let { "Book " + seriesNumber(it) } ?: book.title),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author != null) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.series != null) {
                    Text(
                        book.series + (book.seriesIndex?.let { " · " + seriesNumber(it) } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.rating > 0) Stars(book.rating, Modifier.padding(top = 2.dp))
            }
            // The same menu a long-press on the cover opens.
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp).offset(x = 6.dp, y = (-4).dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More for ${book.title}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                BookMenu(
                    book = book,
                    vm = vm,
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onDelete = onDelete,
                    onCollections = onCollections,
                    onEdit = onEdit,
                    offset = DpOffset((-8).dp, 0.dp),
                )
            }
        }
    }
}

/** A book's menu: from the ⋮ under its cover, a long-press on the cover, or a long-press on its spine. */
@Composable
internal fun BookMenu(
    book: Book,
    vm: LibraryViewModel,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onCollections: () -> Unit,
    onEdit: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        if (book.lastOpenedAt > 0) {
            DropdownMenuItem(
                text = { Text("Mark as unread") },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = {
                    onDismiss()
                    vm.markUnread(book)
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Details and rating…") },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            onClick = {
                onDismiss()
                onEdit()
            },
        )
        if (book.highlights.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text("Share highlights") },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    onDismiss()
                    HighlightExport.share(context, book)
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Collections…") },
            leadingIcon = { Icon(LibraryIcons.Collection, contentDescription = null) },
            onClick = {
                onDismiss()
                onCollections()
            },
        )
        DropdownMenuItem(
            text = { Text("Remove from library") },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = {
                onDismiss()
                onDelete()
            },
        )
    }
}

/** The audiobook that's loaded, along the bottom of the library: tap to open, or play and pause from here. */
@Composable
private fun MiniPlayer(
    book: Book,
    state: ListenState,
    cover: java.io.File?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit,
) {
    val audio = book.audio ?: return
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (state.totalMs > 0) state.positionMs.toFloat() / state.totalMs else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = Color.Transparent,
            )
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(book.title, book.author, cover, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        audio.chapters.getOrNull(state.chapter)?.title.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (state.playing) ListenIcons.Pause else ListenIcons.Play,
                        contentDescription = if (state.playing) "Pause" else "Play",
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close player")
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, icon: Boolean = false) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
        modifier = modifier.padding(6.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyLibrary(importing: Boolean, onAdd: () -> Unit, onFindFree: () -> Unit, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A little fanned stack of books.
        Box(Modifier.size(width = 180.dp, height = 140.dp), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .offset(x = (-44).dp, y = (-2).dp)
                    .size(68.dp, 102.dp)
                    .shadow(4.dp, CoverShape)
                    .clip(CoverShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
            Box(
                Modifier
                    .offset(x = 44.dp, y = (-2).dp)
                    .size(68.dp, 102.dp)
                    .shadow(4.dp, CoverShape)
                    .clip(CoverShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
            )
            Box(
                Modifier
                    .size(78.dp, 117.dp)
                    .shadow(12.dp, CoverShape)
                    .clip(CoverShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Start your library", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add EPUB or PDF books, or audiobooks, from your phone and they'll appear here, ready to read or listen to.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        FilledTonalButton(onClick = onAdd, enabled = !importing, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (importing) "Adding…" else "Add books")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onFindFree, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
            Text("Find free books")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRestore, enabled = !importing) { Text("Restore from a backup") }
    }
}
