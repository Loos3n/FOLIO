@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.folio.reader.ui.library

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.platform.LocalContext
import com.folio.reader.FolioApp
import com.folio.reader.audio.ListenState
import com.folio.reader.ui.listen.ListenIcons
import androidx.compose.material.icons.filled.Close
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
import com.folio.reader.data.Book
import java.util.Calendar
import kotlin.math.roundToInt

/** EPUBs, and audio files for audiobooks (M4B files often come through as octet-stream). */
private val BOOK_TYPES = arrayOf("application/epub+zip", "application/octet-stream", "audio/*")
private val CoverShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpen: (String) -> Unit,
) {
    val allBooks by vm.allBooks.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val continueReading by vm.continueReading.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val collection by vm.collection.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    var showStats by remember { mutableStateOf(false) }
    var editingCollections by remember { mutableStateOf<Book?>(null) }
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.import(uris)
    }
    val pickBooks = { picker.launch(BOOK_TYPES) }
    val app = LocalContext.current.applicationContext as FolioApp
    val listening by app.audio.state.collectAsStateWithLifecycle()
    val playingBook = allBooks.firstOrNull { it.id == listening.bookId }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbar.showSnackbar(event.text)
                is LibraryEvent.Open -> onOpen(event.bookId)
            }
        }
    }

    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex < 2 } }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            if (allBooks.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = pickBooks,
                    expanded = fabExpanded,
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
            EmptyLibrary(importing = importing, onAdd = pickBooks, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 108.dp),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 104.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Header(
                        count = allBooks.size,
                        importing = importing,
                        sort = sort,
                        onSort = vm::setSort,
                        onStats = { showStats = true },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                    SearchField(query = query, onQuery = { vm.query.value = it })
                }
                if (continueReading.isNotEmpty() && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        ContinueReadingRow(continueReading, vm, onOpen)
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "shelves") {
                    ShelfChips(
                        shelf = shelf,
                        collection = collection,
                        collections = collections,
                        onShelf = vm::showShelf,
                        onCollection = vm::showCollection,
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
                items(books, key = { it.id }) { book ->
                    BookTile(
                        book = book,
                        vm = vm,
                        onOpen = { onOpen(book.id) },
                        onDelete = { pendingDelete = book },
                        onCollections = { editingCollections = book },
                        modifier = Modifier.animateItem(),
                    )
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

    if (showStats) {
        val stats by vm.stats.collectAsStateWithLifecycle()
        StatsSheet(stats = stats, books = allBooks, onDismiss = { showStats = false })
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

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Reading late?"
}

@Composable
private fun Header(count: Int, importing: Boolean, sort: SortOrder, onSort: (SortOrder) -> Unit, onStats: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    greeting(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Your library", style = MaterialTheme.typography.displaySmall)
                Text(
                    if (count == 1) "1 book" else "$count books",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onStats) {
                Icon(LibraryIcons.Stats, contentDescription = "Reading stats", tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(sort.label) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SortOrder.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = {
                                if (option == sort) Icon(Icons.Filled.Check, contentDescription = null)
                                else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                onSort(option)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(importing) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
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
                        "Search titles and authors",
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
                    modifier = Modifier.fillMaxWidth(),
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
        Text("Continue reading", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
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
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { onOpen(book.id) },
        shape = RoundedCornerShape(28.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        modifier = Modifier.width(if (single) 340.dp else 300.dp),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(listOf(scheme.primaryContainer, scheme.secondaryContainer.copy(alpha = 0.6f))),
            ),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    title = book.title,
                    author = book.author,
                    file = vm.coverFile(book),
                    modifier = Modifier
                        .width(86.dp)
                        .aspectRatio(2f / 3f)
                        .shadow(10.dp, CoverShape)
                        .clip(CoverShape),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
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
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = scheme.primary,
                        trackColor = scheme.onPrimaryContainer.copy(alpha = 0.12f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(book.progress * 100).roundToInt()}% " + if (book.isAudio) "listened" else "read",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfChips(
    shelf: Shelf,
    collection: String?,
    collections: List<String>,
    onShelf: (Shelf) -> Unit,
    onCollection: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .layoutBleed(20.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 4.dp),
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
}

@Composable
private fun BookTile(
    book: Book,
    vm: LibraryViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onCollections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
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
                    book.isFinished() -> Badge("Finished", Modifier.align(Alignment.TopEnd), icon = true)
                    book.lastOpenedAt == 0L -> Badge("New", Modifier.align(Alignment.TopEnd))
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = DpOffset(8.dp, 0.dp)) {
                if (book.lastOpenedAt > 0) {
                    DropdownMenuItem(
                        text = { Text("Mark as unread") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            vm.markUnread(book)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Collections…") },
                    leadingIcon = { Icon(LibraryIcons.Collection, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCollections()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove from library") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
        if (book.isReading()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { book.progress },
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
private fun EmptyLibrary(importing: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
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
            "Add EPUB books or audiobooks from your phone and they'll appear here, ready to read or listen to.",
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
    }
}
