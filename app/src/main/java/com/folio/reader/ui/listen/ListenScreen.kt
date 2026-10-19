package com.folio.reader.ui.listen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.folio.reader.data.AudioBookmark
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.FolioApp
import com.folio.reader.audio.ListenState
import com.folio.reader.data.AudioInfo
import com.folio.reader.data.Book
import com.folio.reader.ui.library.BookCover
import kotlinx.coroutines.delay

/** Where to show the listener: live from the player if this book is loaded, else from where they left it. */
private fun shownState(book: Book, live: ListenState): ListenState {
    if (live.bookId == book.id) return live
    val a = book.audio ?: return ListenState()
    return ListenState(
        chapter = a.chapterAt(a.track, a.positionMs),
        positionMs = a.absolute(a.track, a.positionMs),
        totalMs = a.totalMs,
        speed = a.speed,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(bookId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as FolioApp
    val player = app.audio
    val books by app.library.books.collectAsStateWithLifecycle()
    val live by player.state.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId }
    val audio = book?.audio
    if (book == null || audio == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val state = shownState(book, live)
    val loaded = live.bookId == book.id
    // Nothing else playing: get this book ready so the first tap starts at once.
    LaunchedEffect(book.id) {
        if (player.state.value.bookId == null) player.open(book)
    }
    fun ensureLoaded() {
        if (player.state.value.bookId != book.id) player.open(book)
    }

    var showChapters by remember { mutableStateOf(false) }
    /** Which tab the chapters sheet opens on: 0 chapters, 1 bookmarks. */
    var sheetTab by remember { mutableIntStateOf(0) }
    /** The bookmark just added, offered for a note for a few seconds. */
    var justMarked by remember { mutableStateOf<AudioBookmark?>(null) }
    var editingMark by remember { mutableStateOf<AudioBookmark?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(justMarked) {
        if (justMarked != null) {
            delay(5_000)
            justMarked = null
        }
    }
    fun addBookmark() {
        val pos = state.positionMs
        val mark = AudioBookmark(
            id = UUID.randomUUID().toString(),
            positionMs = pos,
            chapterTitle = audio.chapters.getOrNull(chapterOf(audio, pos))?.title.orEmpty(),
            note = "",
            createdAt = System.currentTimeMillis(),
        )
        scope.launch { app.library.saveAudioBookmark(book.id, mark) }
        justMarked = mark
    }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    val chapter = audio.chapters.getOrNull(state.chapter)
    val chapterStart = chapter?.let { audio.absoluteOf(it) } ?: 0L
    val chapterEnd = audio.chapterEnd(state.chapter)
    val chapterLength = (chapterEnd - chapterStart).coerceAtLeast(1)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val inChapter = if (dragging) dragValue else ((state.positionMs - chapterStart).toFloat() / chapterLength).coerceIn(0f, 1f)
    val shownPos = chapterStart + (inChapter * chapterLength).toLong()

    // The sleep timer counts down on screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.sleepEndsAt) {
        while (state.sleepEndsAt != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.surface),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                }
                Text(
                    "Audiobook",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { sheetTab = 0; showChapters = true }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapters and bookmarks")
                }
            }
            Spacer(Modifier.height(12.dp))
            BookCover(
                book.title,
                book.author,
                app.library.coverFile(book),
                Modifier
                    .widthIn(max = 300.dp)
                    .fillMaxWidth(0.78f)
                    .aspectRatio(1f)
                    .shadow(16.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                book.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author != null) {
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                onClick = { sheetTab = 0; showChapters = true },
                shape = CircleShape,
                color = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            ) {
                Text(
                    chapter?.title ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Text(
                "Chapter ${state.chapter + 1} of ${audio.chapters.size}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(14.dp))
            Slider(
                value = inChapter,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    ensureLoaded()
                    player.seekTo(chapterStart + (dragValue * chapterLength).toLong())
                    dragging = false
                },
                colors = SliderDefaults.colors(inactiveTrackColor = scheme.surfaceContainerHighest),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(clock(shownPos - chapterStart), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("-" + clock(chapterEnd - shownPos), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            Text(
                leftInBook(state, audio),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { ensureLoaded(); player.previousChapter() }) {
                    Icon(ListenIcons.SkipPrevious, contentDescription = "Previous chapter", modifier = Modifier.size(30.dp))
                }
                SkipButton(seconds = 15, forward = false) { ensureLoaded(); player.skip(-15_000) }
                FilledIconButton(
                    onClick = {
                        if (loaded) player.toggle() else { ensureLoaded(); player.play() }
                    },
                    modifier = Modifier.size(80.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary),
                ) {
                    Icon(
                        if (loaded && state.playing) ListenIcons.Pause else ListenIcons.Play,
                        contentDescription = if (loaded && state.playing) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                    )
                }
                SkipButton(seconds = 30, forward = true) { ensureLoaded(); player.skip(30_000) }
                IconButton(onClick = { ensureLoaded(); player.nextChapter() }, enabled = state.chapter < audio.chapters.lastIndex) {
                    Icon(ListenIcons.SkipNext, contentDescription = "Next chapter", modifier = Modifier.size(30.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
            val compact = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { showSpeed = true }, modifier = Modifier.weight(1f), contentPadding = compact) {
                    Text(speedLabel(state.speed), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text("Speed", maxLines = 1)
                }
                FilledTonalButton(onClick = { addBookmark() }, modifier = Modifier.weight(1f), contentPadding = compact) {
                    Icon(ListenIcons.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Bookmark", maxLines = 1)
                }
                FilledTonalButton(onClick = { showSleep = true }, modifier = Modifier.weight(1f), contentPadding = compact) {
                    Icon(ListenIcons.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    val ends = state.sleepEndsAt
                    Text(
                        when {
                            ends != null -> clock((ends - now).coerceAtLeast(0))
                            state.sleepAtChapterEnd -> "Chapter end"
                            else -> "Sleep"
                        },
                        maxLines = 1,
                    )
                }
            }
            AnimatedVisibility(justMarked != null) {
                val mark = justMarked
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Bookmarked at ${clock(intoChapter(audio, mark?.positionMs ?: 0))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editingMark = mark; justMarked = null }) { Text("Add note") }
                    TextButton(onClick = { justMarked = null; sheetTab = 1; showChapters = true }) { Text("View all") }
                }
            }
            if (state.error != null && loaded) {
                Spacer(Modifier.height(16.dp))
                Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = RoundedCornerShape(16.dp)) {
                    Text(state.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }, sheetState = rememberModalBottomSheetState()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                listOf("Chapters", "Bookmarks").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = sheetTab == i,
                        onClick = { sheetTab = i },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = {},
                    ) { Text(label) }
                }
            }
            if (sheetTab == 1) {
                AudioBookmarkList(
                    audio = audio,
                    marks = audio.bookmarks,
                    onOpen = { m ->
                        ensureLoaded()
                        player.seekTo(m.positionMs)
                        showChapters = false
                    },
                    onEdit = { editingMark = it },
                    onDelete = { m -> scope.launch { app.library.removeAudioBookmark(book.id, m.id) } },
                )
                Spacer(Modifier.height(24.dp))
                return@ModalBottomSheet
            }
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (state.chapter - 2).coerceAtLeast(0))
            LazyColumn(state = list, modifier = Modifier.padding(horizontal = 12.dp)) {
                itemsIndexed(audio.chapters) { i, c ->
                    val current = i == state.chapter
                    Surface(
                        onClick = {
                            ensureLoaded()
                            player.openChapter(i)
                            showChapters = false
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (current) scheme.primaryContainer else Color.Transparent,
                        contentColor = if (current) scheme.onPrimaryContainer else scheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                clock(audio.chapterEnd(i) - audio.absoluteOf(c)),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (current) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    editingMark?.let { mark ->
        var note by remember(mark.id) { mutableStateOf(mark.note) }
        AlertDialog(
            onDismissRequest = { editingMark = null },
            title = { Text("Note at ${clock(intoChapter(audio, mark.positionMs))}") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("What happens here?") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.library.saveAudioBookmark(book.id, mark.copy(note = note.trim())) }
                    editingMark = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingMark = null }) { Text("Cancel") } },
        )
    }
    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("Playback speed", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                ChipGrid(
                    options = SPEEDS.map { speedLabel(it) },
                    selected = SPEEDS.indexOfFirst { kotlin.math.abs(it - state.speed) < 0.01f },
                ) { i ->
                    ensureLoaded()
                    player.setSpeed(SPEEDS[i])
                    showSpeed = false
                }
            }
        }
    }
    if (showSleep) {
        ModalBottomSheet(onDismissRequest = { showSleep = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("Sleep timer", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Pauses the book for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val options = listOf("Off", "15 min", "30 min", "45 min", "1 hour", "End of chapter")
                val minutes = listOf(null, 15, 30, 45, 60, null)
                val selected = when {
                    state.sleepAtChapterEnd -> 5
                    state.sleepEndsAt == null -> 0
                    else -> -1
                }
                ChipGrid(options, selected) { i ->
                    ensureLoaded()
                    player.setSleep(minutes[i], chapterEnd = i == 5)
                    showSleep = false
                }
            }
        }
    }
}

@Composable
private fun AudioBookmarkList(
    audio: AudioInfo,
    marks: List<AudioBookmark>,
    onOpen: (AudioBookmark) -> Unit,
    onEdit: (AudioBookmark) -> Unit,
    onDelete: (AudioBookmark) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (marks.isEmpty()) {
        Text(
            "No bookmarks yet. Tap Bookmark while listening to save a moment you want to come back to.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        return
    }
    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
        items(marks, key = { it.id }) { m ->
            Surface(
                onClick = { onOpen(m) },
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(clock(intoChapter(audio, m.positionMs)), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                        if (m.chapterTitle.isNotBlank()) {
                            Text(m.chapterTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (m.note.isNotBlank()) {
                            Text(m.note, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { onEdit(m) }) { Icon(Icons.Filled.Edit, contentDescription = "Edit note") }
                    IconButton(onClick = { onDelete(m) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark") }
                }
            }
        }
    }
}

private fun chapterOf(audio: AudioInfo, absoluteMs: Long): Int {
    val (track, pos) = audio.locate(absoluteMs)
    return audio.chapterAt(track, pos)
}

/** How far into its chapter a point in the book is, as the player's chapter clock shows it. */
private fun intoChapter(audio: AudioInfo, absoluteMs: Long): Long {
    val c = audio.chapters.getOrNull(chapterOf(audio, absoluteMs)) ?: return absoluteMs
    return (absoluteMs - audio.absoluteOf(c)).coerceAtLeast(0)
}

private val SPEEDS = listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipGrid(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(selected = i == selected, onClick = { onPick(i) }, label = { Text(label) })
        }
    }
}

/** A round "skip" arrow with the number of seconds inside it. */
@Composable
private fun SkipButton(seconds: Int, forward: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                ListenIcons.Replay,
                contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { scaleX = if (forward) -1f else 1f },
            )
            Text("$seconds", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

fun speedLabel(speed: Float): String {
    val tenths = Math.round(speed * 100)
    val text = when {
        tenths % 100 == 0 -> "${tenths / 100}"
        tenths % 10 == 0 -> "${tenths / 100}.${(tenths % 100) / 10}"
        else -> "${tenths / 100}.${(tenths % 100).toString().padStart(2, '0')}"
    }
    return "$text×"
}

/** 1:05:09 or 4:07. */
fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 500) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun leftInBook(state: ListenState, audio: AudioInfo): String {
    val left = ((audio.totalMs - state.positionMs) / state.speed.coerceAtLeast(0.1f)).toLong()
    val minutes = left / 60_000
    val text = when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes >= 1 -> "$minutes min"
        else -> "Under a minute"
    }
    return "$text left in the book" + if (kotlin.math.abs(state.speed - 1f) > 0.01f) " at ${speedLabel(state.speed)}" else ""
}

/** Player icons beyond material-icons-core, from their standard 24dp paths. */
internal object ListenIcons {
    val Play = icon("Play", "M8,5v14l11,-7z")
    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val SkipPrevious = icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val SkipNext = icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Replay = icon(
        "Replay",
        "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z",
    )
    val BookmarkAdd = icon(
        "BookmarkAdd",
        "M17,11v6.97l-5,-2.14l-5,2.14V5h6V3H7C5.9,3 5,3.9 5,5v16l7,-3l7,3V11H17zM21,7h-2v2h-2V7h-2V5h2V3h2v2h2V7z",
    )
    val Bedtime = icon(
        "Bedtime",
        "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z",
    )
    val Headphones = icon(
        "Headphones",
        "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(
                androidx.compose.ui.graphics.vector.PathParser().parsePathString(path).toNodes(),
                fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
            )
            .build()
}
