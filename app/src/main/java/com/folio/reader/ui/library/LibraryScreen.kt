@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.folio.reader.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.data.Book
import kotlin.math.roundToInt

private val EPUB_TYPES = arrayOf("application/epub+zip", "application/octet-stream")

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpen: (String) -> Unit,
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        vm.import(uris)
    }
    val pickBooks = { picker.launch(EPUB_TYPES) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is LibraryEvent.Message -> snackbar.showSnackbar(event.text)
                is LibraryEvent.Open -> onOpen(event.bookId)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
    val current = books.firstOrNull { it.lastOpenedAt > 0 && it.progress < 0.995f }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text("Library") },
                    scrollBehavior = scrollBehavior,
                )
                AnimatedVisibility(importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            if (books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = pickBooks,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add books") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (books.isEmpty()) {
            EmptyLibrary(onAdd = pickBooks, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (current != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                    ContinueReadingCard(current, vm, onOpen)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Text(
                    "All books · ${books.size}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(books, key = { it.id }) { book ->
                BookTile(
                    book = book,
                    vm = vm,
                    onOpen = { onOpen(book.id) },
                    onDelete = { pendingDelete = book },
                    modifier = Modifier.animateItem(),
                )
            }
        }
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

@Composable
private fun ContinueReadingCard(book: Book, vm: LibraryViewModel, onOpen: (String) -> Unit) {
    Card(
        onClick = { onOpen(book.id) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(
                title = book.title,
                author = book.author,
                file = vm.coverFile(book),
                modifier = Modifier
                    .width(84.dp)
                    .aspectRatio(2f / 3f)
                    .shadow(6.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Continue reading",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author != null) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${(book.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun BookTile(
    book: Book,
    vm: LibraryViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .shadow(4.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
            ) {
                BookCover(book.title, book.author, vm.coverFile(book), Modifier.fillMaxSize())
                when {
                    book.progress >= 0.995f -> Badge("Finished", Modifier.align(Alignment.TopEnd))
                    book.lastOpenedAt == 0L -> Badge("New", Modifier.align(Alignment.TopEnd))
                    else -> Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.35f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(book.progress.coerceIn(0.02f, 1f))
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = androidx.compose.ui.unit.DpOffset(8.dp, 0.dp)) {
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
                    text = { Text("Remove from library") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
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

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.padding(6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A little fanned stack of books.
        Box(Modifier.size(width = 160.dp, height = 130.dp), contentAlignment = Alignment.BottomCenter) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .offset(x = (-38).dp, y = (-4).dp)
                    .size(64.dp, 96.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
            )
            Box(
                Modifier
                    .offset(x = 38.dp, y = (-4).dp)
                    .size(64.dp, 96.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
            Box(
                Modifier
                    .size(72.dp, 108.dp)
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("Your library is empty", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add EPUB books from your phone and they'll show up here, ready to read.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add books")
        }
    }
}
