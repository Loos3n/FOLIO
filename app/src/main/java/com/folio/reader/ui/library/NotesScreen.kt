package com.folio.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.data.Book
import com.folio.reader.data.Highlight
import com.folio.reader.data.HighlightExport
import com.folio.reader.ui.common.MessageView
import java.text.DateFormat
import java.util.Date

/** Every highlight and note in the library, grouped by book, newest books first. */
@Composable
fun NotesScreen(vm: LibraryViewModel, onBack: () -> Unit, onOpen: (bookId: String, markId: String) -> Unit) {
    val books by vm.allBooks.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    val groups = remember(books, query) {
        val q = query.trim()
        books.asSequence()
            .filter { !it.isAudio && it.highlights.isNotEmpty() }
            .sortedByDescending { b -> b.highlights.maxOf { it.createdAt } }
            .mapNotNull { book ->
                val bookHit = q.isNotEmpty() &&
                    (book.title.contains(q, true) || book.author.orEmpty().contains(q, true))
                val marks = book.highlights
                    .filter { h ->
                        q.isEmpty() || bookHit || h.text.contains(q, true) ||
                            h.note.contains(q, true) || h.chapterTitle.contains(q, true)
                    }
                    .sortedWith(compareBy({ it.chapterIndex }, { it.start }))
                if (marks.isEmpty()) null else book to marks
            }
            .toList()
    }
    val total = books.filter { !it.isAudio }.sumOf { it.highlights.size }
    val notes = books.filter { !it.isAudio }.sumOf { b -> b.highlights.count { it.note.isNotBlank() } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("Notes", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${plural(total, "highlight")} · ${plural(notes, "note")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (total == 0) {
            MessageView(
                title = "No highlights yet",
                message = "Long-press a passage while reading to highlight it or add a note. Everything you mark shows up here.",
                icon = LibraryIcons.Notes,
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            SearchField(query = query, onQuery = { query = it }, placeholder = "Search highlights and notes")
        }
        if (groups.isEmpty()) {
            MessageView(
                title = "Nothing matches",
                message = "No highlight, note, chapter or book matches “${query.trim()}”.",
                icon = null,
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp,
                bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groups.forEach { (book, marks) ->
                item(key = "book:${book.id}") {
                    BookNotesHeader(
                        book = book,
                        count = marks.size,
                        cover = vm.coverFile(book),
                        onShare = { HighlightExport.share(context, book) },
                    )
                }
                items(marks, key = { "mark:${book.id}:${it.id}" }) { mark ->
                    NoteCard(mark, onClick = { onOpen(book.id, mark.id) })
                }
            }
        }
    }
}

@Composable
private fun BookNotesHeader(book: Book, count: Int, cover: java.io.File?, onShare: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book.title, book.author, cover, Modifier.width(36.dp).height(54.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(book.author?.takeIf { it.isNotBlank() }, plural(count, "highlight")).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = "Share highlights from ${book.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoteCard(mark: Highlight, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(Color(mark.color.argb)))
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    mark.text.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                if (mark.note.isNotBlank()) {
                    Text(
                        mark.note.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    listOfNotNull(
                        mark.chapterTitle.takeIf { it.isNotBlank() },
                        "${(mark.progress * 100).toInt()}%",
                        mark.createdAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
