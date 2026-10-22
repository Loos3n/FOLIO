package com.folio.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.data.Book
import com.folio.reader.ui.listen.ListenIcons
import kotlin.math.absoluteValue

/** The tallest a spine stands, and the gap between spines. */
private val SpineMax = 172.dp
private const val SPINE_GAP = 3

/** How wide a book's spine is, in dp: steady for each book, so the shelf doesn't reshuffle. */
private fun spineWidth(book: Book): Int = 30 + (book.id.hashCode().absoluteValue % 17)

/** How tall a book stands, as a share of the tallest. */
private fun spineHeight(book: Book): Float = 0.78f + ((book.id.hashCode().absoluteValue / 17) % 23) / 100f

/** Splits [books] into shelves that each fit in [widthDp]. */
internal fun shelfRows(books: List<Book>, widthDp: Int): List<List<Book>> {
    val rows = mutableListOf<List<Book>>()
    var row = mutableListOf<Book>()
    var used = 0
    for (book in books) {
        val w = spineWidth(book) + if (row.isEmpty()) 0 else SPINE_GAP
        if (row.isNotEmpty() && used + w > widthDp) {
            rows += row
            row = mutableListOf()
            used = 0
        }
        used += spineWidth(book) + if (row.isEmpty()) 0 else SPINE_GAP
        row += book
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

/** One wooden shelf of book spines. Tap a spine to open the book; long-press for its menu. */
@Composable
internal fun ShelfRow(
    books: List<Book>,
    vm: LibraryViewModel,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onCollections: (Book) -> Unit,
    onEdit: (Book) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SPINE_GAP.dp),
            verticalAlignment = Alignment.Bottom,
            // Room above the tallest spine for a bookmark ribbon.
            modifier = Modifier.padding(horizontal = 6.dp).height(SpineMax + 10.dp),
        ) {
            books.forEach { book ->
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    Spine(book, vm, onOpen = { onOpen(book) }, onLongPress = { menuOpen = true })
                    BookMenu(
                        book = book,
                        vm = vm,
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        onDelete = { onDelete(book) },
                        onCollections = { onCollections(book) },
                        onEdit = { onEdit(book) },
                    )
                }
            }
        }
        Plank()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Spine(book: Book, vm: LibraryViewModel, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val file = vm.coverFile(book)
    val tone by produceState(initialValue = placeholderTone(book.title), book.title, file) {
        value = coverTone(book.title, file)
    }
    // A little deeper than the cover, like cloth or leather.
    val base = lerp(tone, Color.Black, 0.18f)
    val light = base.luminance() > 0.45f
    val ink = if (light) Color(0xFF2B2118) else Color(0xFFF7EFE3)
    val gilt = if (light) Color(0xFF8A6A2F) else Color(0xFFD9B872)
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp)
    Box(
        Modifier
            .width(spineWidth(book).dp)
            .height(SpineMax * spineHeight(book)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .shadow(3.dp, shape)
                .clip(shape)
                .background(base)
                .drawBehind {
                    // Gilt bands near the top and bottom.
                    val band = 1.5.dp.toPx()
                    for (y in listOf(12.dp.toPx(), 16.dp.toPx(), size.height - 16.dp.toPx() - band, size.height - 12.dp.toPx() - band)) {
                        drawRect(gilt.copy(alpha = 0.85f), topLeft = Offset(0f, y), size = Size(size.width, band))
                    }
                }
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(book.title) }
                    book.author?.substringAfterLast(' ')?.takeIf { it.isNotBlank() }?.let {
                        withStyle(SpanStyle(fontSize = 10.sp, color = ink.copy(alpha = 0.75f))) { append("   " + it.uppercase()) }
                    }
                },
                color = ink,
                fontSize = 12.sp,
                fontFamily = MaterialTheme.typography.headlineSmall.fontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(vertical = 24.dp)
                    .vertical()
                    .rotate(90f),
            )
            if (book.isAudio) {
                Icon(
                    ListenIcons.Headphones,
                    contentDescription = "Audiobook",
                    tint = ink.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(9.dp),
                )
            }
            // The round of the spine: shade at both edges, a sheen just in from the left.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.28f),
                            0.18f to Color.White.copy(alpha = 0.12f),
                            0.45f to Color.Transparent,
                            0.8f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.32f),
                        ),
                    ),
            )
        }
        // A ribbon marks a book being read.
        if (book.isReading()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = (-8).dp)
                    .width(5.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(bottomStart = 1.dp, bottomEnd = 1.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** The wooden shelf the books stand on, with its shadow on the wall below. */
@Composable
private fun Plank() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(13.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFFC08A57),
                    0.25f to Color(0xFF9C6A3E),
                    1f to Color(0xFF6B4426),
                ),
            )
            .drawBehind {
                // A few streaks of grain.
                val grain = Color(0xFF5A3820).copy(alpha = 0.35f)
                val line = 1.dp.toPx()
                listOf(0.42f to 0.08f, 0.6f to 0.35f, 0.78f to 0.6f).forEach { (y, start) ->
                    drawRect(grain, topLeft = Offset(size.width * start, size.height * y), size = Size(size.width * 0.38f, line))
                }
            },
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.22f), 1f to Color.Transparent)),
    )
}

/** Lays text out sideways: measure it as if the box were turned, then [rotate] it into place. */
private fun Modifier.vertical() = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth),
    )
    layout(placeable.height, placeable.width) {
        placeable.place(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = -(placeable.height / 2 - placeable.width / 2),
        )
    }
}
