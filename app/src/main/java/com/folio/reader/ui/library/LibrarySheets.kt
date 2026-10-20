@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.reader.ui.library

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.data.Book
import com.folio.reader.data.StatsSnapshot
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun duration(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> if (seconds > 0) "<1 min" else "0 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
    }
}

/** Days in a row with some reading, up to today (or yesterday, if today hasn't started yet). */
private fun streak(stats: StatsSnapshot, today: LocalDate): Int {
    fun read(d: LocalDate) = stats.days[d.toString()]?.let { it.seconds >= 60 || it.pages >= 2 } == true
    var day = if (read(today)) today else today.minusDays(1)
    var n = 0
    while (read(day)) {
        n++
        day = day.minusDays(1)
    }
    return n
}

@Composable
internal fun StatsSheet(stats: StatsSnapshot, books: List<Book>, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Reading stats", style = MaterialTheme.typography.headlineSmall)

            val days = streak(stats, today)
            Surface(color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer, shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LibraryIcons.Flame, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            if (days == 1) "1 day" else "$days days",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (days == 0) "Read today to start a streak" else "Reading streak",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            val todayStats = stats.days[today.toString()]
            val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
            val weekSeconds = week.sumOf { stats.days[it.toString()]?.seconds ?: 0L }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Today", duration(todayStats?.seconds ?: 0), Modifier.weight(1f))
                StatTile("Pages today", "${todayStats?.pages ?: 0}", Modifier.weight(1f))
                StatTile("This week", duration(weekSeconds), Modifier.weight(1f))
            }

            Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
            val maxSeconds = week.maxOf { stats.days[it.toString()]?.seconds ?: 0L }.coerceAtLeast(60)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                week.forEach { day ->
                    val s = stats.days[day.toString()]?.seconds ?: 0L
                    val isToday = day == today
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (s >= 60) "${s / 60}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight((s.toFloat() / maxSeconds).coerceIn(0.02f, 1f))
                                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(if (isToday) scheme.primary else scheme.primary.copy(alpha = 0.35f)),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            Text("Minutes read each day", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)

            val total = stats.days.values.sumOf { it.seconds }
            val month = (0 until 30).sumOf { stats.days[today.minusDays(it.toLong()).toString()]?.pages ?: 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("All time", duration(total), Modifier.weight(1f))
                StatTile("Pages a day", "${(month + 15) / 30}", Modifier.weight(1f), caption = "last 30 days")
                StatTile("Finished", "${books.count { it.isFinished() }}", Modifier.weight(1f), caption = "books")
            }

            val top = books.mapNotNull { b -> stats.books[b.id]?.takeIf { it >= 60 }?.let { b to it } }
                .sortedByDescending { it.second }.take(5)
            if (top.isNotEmpty()) {
                Text("Most read", style = MaterialTheme.typography.titleMedium)
                top.forEach { (book, seconds) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(duration(seconds), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Puts a book in (or takes it out of) the reader's own collections, or starts a new one. */
@Composable
internal fun CollectionsDialog(
    book: Book,
    existing: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = remember(book.id) { mutableStateListOf<String>().apply { addAll(book.collections) } }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(LibraryIcons.Collection, contentDescription = null) },
        title = { Text("Collections") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Group “${book.title}” with other books. Collections show up next to the shelves in your library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                (existing + chosen.filter { it !in existing }).forEach { name ->
                    val on = name in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { if (on) chosen.remove(name) else chosen.add(name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { if (it) chosen.add(name) else chosen.remove(name) })
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New collection") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                val n = newName.trim()
                                if (n.isNotEmpty() && n !in chosen) chosen.add(n)
                                newName = ""
                            },
                            enabled = newName.isNotBlank(),
                        ) { Text("Add") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pending = newName.trim()
                onSave(if (pending.isNotEmpty() && pending !in chosen) chosen + pending else chosen.toList())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Changes a book's title, author and cover. A messy PDF name is the usual reason. */
@Composable
internal fun EditDetailsDialog(
    book: Book,
    cover: File?,
    onSave: (title: String, author: String?, cover: Uri?, removeCover: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }
    var picked by remember(book.id) { mutableStateOf<Uri?>(null) }
    var removed by remember(book.id) { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            picked = uri
            removed = false
        }
    }
    val context = LocalContext.current
    val pickedImage by produceState<ImageBitmap?>(null, picked) {
        val uri = picked
        value = if (uri == null) null else withContext(Dispatchers.IO) { decodePreview(context, uri) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        title = { Text("Book details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = title.ifBlank { book.title }
                    Box(
                        Modifier
                            .width(72.dp)
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp)),
                    ) {
                        val image = pickedImage
                        if (image != null) {
                            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            BookCover(shown, author.ifBlank { null }, if (removed) null else cover, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        TextButton(onClick = { pickCover.launch("image/*") }) { Text("Choose a cover") }
                        if ((cover != null && !removed) || picked != null) {
                            TextButton(onClick = {
                                picked = null
                                removed = true
                            }) { Text("Use a plain cover") }
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title, author, picked, removed && picked == null) },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A picked picture, shrunk for the small preview in [EditDetailsDialog]. */
private fun decodePreview(context: Context, uri: Uri): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outHeight / (sample * 2) >= 400) sample *= 2
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }?.asImageBitmap()
}.getOrNull()
