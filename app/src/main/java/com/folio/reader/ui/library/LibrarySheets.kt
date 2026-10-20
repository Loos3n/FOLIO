@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SuggestionChip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.folio.reader.data.AutoBackupState
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

private val GOAL_CHOICES = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90)

@Composable
internal fun StatsSheet(
    stats: StatsSnapshot,
    books: List<Book>,
    goalMinutes: Int,
    onSetGoal: (Int) -> Unit,
    yearGoal: Int,
    onSetYearGoal: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val scheme = MaterialTheme.colorScheme
    var choosingGoal by remember { mutableStateOf(false) }
    if (choosingGoal) {
        GoalDialog(goalMinutes, onPick = { onSetGoal(it); choosingGoal = false }, onDismiss = { choosingGoal = false })
    }
    var choosingYearGoal by remember { mutableStateOf(false) }
    if (choosingYearGoal) {
        YearGoalDialog(yearGoal, onPick = { onSetYearGoal(it); choosingYearGoal = false }, onDismiss = { choosingYearGoal = false })
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Reading stats", style = MaterialTheme.typography.headlineSmall)

            val days = stats.streak(today, goalMinutes)
            val todaySeconds = stats.secondsOn(today)
            val met = goalMinutes > 0 && todaySeconds >= goalMinutes * 60L
            Surface(color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                            if (goalMinutes > 0) {
                                CircularProgressIndicator(
                                    progress = { (todaySeconds / (goalMinutes * 60f)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxSize(),
                                    strokeWidth = 5.dp,
                                    trackColor = scheme.primary.copy(alpha = 0.18f),
                                    strokeCap = StrokeCap.Round,
                                )
                            }
                            Icon(
                                if (met) Icons.Filled.Check else LibraryIcons.Flame,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(if (goalMinutes > 0) 28.dp else 40.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                if (days == 1) "1 day" else "$days days",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                when {
                                    days > 0 -> "Reading streak"
                                    goalMinutes > 0 -> "Meet today's goal to start a streak"
                                    else -> "Read today to start a streak"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                goalMinutes == 0 -> "No daily goal yet"
                                met -> "Today's goal is done: ${todaySeconds / 60} of $goalMinutes min"
                                else -> "${todaySeconds / 60} of $goalMinutes min today"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { choosingGoal = true }) {
                            Text(if (goalMinutes == 0) "Set a goal" else "Change")
                        }
                    }
                }
            }

            val zone = remember { ZoneId.systemDefault() }
            val finishedThisYear = books.filter { it.finishedAt > 0 && Instant.ofEpochMilli(it.finishedAt).atZone(zone).year == today.year }
                .sortedByDescending { it.finishedAt }
            Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                    val n = finishedThisYear.size
                    Text(
                        when {
                            yearGoal > 0 -> "$n of $yearGoal books"
                            n == 1 -> "1 book"
                            else -> "$n books"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (yearGoal > 0 && n >= yearGoal) "${today.year} goal reached" else "Finished in ${today.year}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    if (yearGoal > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (n / yearGoal.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                            trackColor = scheme.primary.copy(alpha = 0.15f),
                            strokeCap = StrokeCap.Round,
                        )
                        // Where a steady pace would have you by today.
                        val due = yearGoal * today.dayOfYear / today.lengthOfYear()
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                n >= yearGoal -> "Every book from here is a bonus."
                                n > due -> "${n - due} ahead of schedule"
                                n == due -> "Right on schedule"
                                else -> "${due - n} behind schedule"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (finishedThisYear.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        finishedThisYear.take(6).forEach { b ->
                            Text(
                                b.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (finishedThisYear.size > 6) {
                            Text(
                                "and ${finishedThisYear.size - 6} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { choosingYearGoal = true }) {
                            Text(if (yearGoal == 0) "Set a yearly goal" else "Change goal")
                        }
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

/** What [EditDetailsDialog] saves. */
internal data class BookDetails(
    val title: String,
    val author: String?,
    val cover: Uri?,
    val removeCover: Boolean,
    val series: String?,
    val seriesIndex: Float?,
    val rating: Int,
)

/** "2" rather than "2.0", but "2.5" stays. */
internal fun seriesNumber(index: Float): String =
    if (index == index.toInt().toFloat()) index.toInt().toString() else index.toString()

/** Changes a book's title, author, cover, series and rating. A messy PDF name is the usual reason. */
@Composable
internal fun EditDetailsDialog(
    book: Book,
    cover: File?,
    seriesNames: List<String>,
    onSave: (BookDetails) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }
    var series by remember(book.id) { mutableStateOf(book.series.orEmpty()) }
    var number by remember(book.id) { mutableStateOf(book.seriesIndex?.let(::seriesNumber).orEmpty()) }
    var rating by remember(book.id) { mutableStateOf(book.rating) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = series,
                        onValueChange = { series = it },
                        label = { Text("Series") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = number,
                        onValueChange = { v -> number = v.filter { it.isDigit() || it == '.' }.take(5) },
                        label = { Text("No.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(72.dp),
                    )
                }
                // Series already in the library, to pick rather than retype.
                val suggestions = seriesNames.filter { it != series.trim() && (series.isBlank() || it.contains(series.trim(), ignoreCase = true)) }.take(4)
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { name ->
                            SuggestionChip(onClick = { series = name }, label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
                Column {
                    Text("Your rating", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StarPicker(rating, onPick = { rating = if (it == rating) 0 else it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        BookDetails(
                            title = title,
                            author = author,
                            cover = picked,
                            removeCover = removed && picked == null,
                            series = series.trim().ifEmpty { null },
                            seriesIndex = number.toFloatOrNull(),
                            rating = rating,
                        ),
                    )
                },
                enabled = title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Five stars to tap; tapping the current rating again clears it. */
@Composable
private fun StarPicker(rating: Int, onPick: (Int) -> Unit) {
    Row {
        for (n in 1..5) {
            IconButton(onClick = { onPick(n) }, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (n <= rating) Icons.Filled.Star else LibraryIcons.StarOutline,
                    contentDescription = if (n == 1) "1 star" else "$n stars",
                    tint = if (n <= rating) Color(0xFFE5A50A) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

/** A row of small stars showing a rating. */
@Composable
internal fun Stars(rating: Int, modifier: Modifier = Modifier) {
    Row(modifier) {
        for (n in 1..5) {
            Icon(
                if (n <= rating) Icons.Filled.Star else LibraryIcons.StarOutline,
                contentDescription = null,
                tint = if (n <= rating) Color(0xFFE5A50A) else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(12.dp),
            )
        }
    }
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

private val YEAR_GOAL_CHOICES = listOf(0, 6, 12, 18, 24, 30, 40, 52, 75, 100)

@Composable
private fun YearGoalDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(LibraryIcons.Stats, contentDescription = null) },
        title = { Text("Books this year") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Books and audiobooks count once you reach the end.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                (YEAR_GOAL_CHOICES + current).distinct().sorted().forEach { books ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(books) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = books == current, onClick = { onPick(books) })
                        Text(if (books == 0) "No goal" else "$books books", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GoalDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(LibraryIcons.Flame, contentDescription = null) },
        title = { Text("Daily reading goal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Reading and listening both count. Meet your goal each day to grow your streak.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                GOAL_CHOICES.forEach { minutes ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(minutes) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = minutes == current, onClick = { onPick(minutes) })
                        Text(if (minutes == 0) "No goal" else "$minutes minutes a day", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Turns the nightly backup on or off, and shows how the last one went. */
@Composable
internal fun AutoBackupDialog(
    state: AutoBackupState,
    onChoose: () -> Unit,
    onNow: () -> Unit,
    onOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val on = state.uri != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automatic backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Once a day Folio saves your places, bookmarks, highlights, notes, word list, stats and settings to one file, " +
                        "replacing the copy from before, so it never piles up. Pick Google Drive when choosing where to save " +
                        "to keep it safe if you lose your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The book files aren't included, which keeps it small. To restore on a new phone, add your books first, then use Restore from backup.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (on) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Saving to ${state.place ?: "your chosen file"}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                state.lastError ?: if (state.lastSavedAt > 0) "Last saved ${savedWhen(state.lastSavedAt)}" else "Saving the first copy…",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = onNow) { Text("Back up now") }
                        TextButton(onClick = onChoose) { Text("Change where") }
                        TextButton(onClick = onOff) { Text("Turn off") }
                    }
                }
            }
        },
        confirmButton = {
            if (on) TextButton(onClick = onDismiss) { Text("Done") }
            else TextButton(onClick = onChoose) { Text("Choose where to save") }
        },
        dismissButton = { if (!on) TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

private fun savedWhen(at: Long): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val clock = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return when (time.toLocalDate()) {
        LocalDate.now() -> "today at $clock"
        LocalDate.now().minusDays(1) -> "yesterday at $clock"
        else -> time.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}
