@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.folio.reader.audio.AiVoices
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.folio.reader.data.Highlight
import com.folio.reader.data.HighlightColor
import kotlin.math.roundToInt

/** Find a word or name anywhere in the book; tapping a match opens the book there. */
@Composable
internal fun SearchSheet(
    state: SearchState,
    onQuery: (String) -> Unit,
    onOpen: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.imePadding()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                placeholder = { Text("Search in this book") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .focusRequester(focus),
            )
            LaunchedEffect(Unit) { if (state.query.isEmpty()) focus.requestFocus() }

            val q = state.query.trim()
            val status = when {
                q.length < 2 -> null
                state.running && state.hits.isEmpty() -> "Searching…"
                state.hits.isEmpty() -> "No matches"
                state.truncated -> "Showing the first ${state.hits.size} matches"
                state.hits.size == 1 -> "1 match"
                else -> "${state.hits.size} matches" + if (state.running) " so far" else ""
            }
            Box(Modifier.height(4.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
            val accent = MaterialTheme.colorScheme.primary
            LazyColumn(Modifier.heightIn(min = 240.dp).padding(horizontal = 12.dp)) {
                items(state.hits, key = { "${it.chapterIndex}:${it.occurrence}" }) { hit ->
                    Surface(
                        onClick = {
                            keyboard?.hide()
                            onOpen(hit)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                "${hit.chapterTitle} · ${(hit.progress * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildAnnotatedString {
                                    append(hit.before)
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = accent.copy(alpha = 0.18f))) { append(hit.match) }
                                    append(hit.after)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** A highlight's colour and note, opened by tapping it on the page or in the list. */
@Composable
internal fun HighlightSheet(
    mark: Highlight,
    focusNote: Boolean,
    onSave: (Highlight) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onQuoteCard: ((Highlight) -> Unit)? = null,
) {
    var note by remember(mark.id) { mutableStateOf(mark.note) }
    var color by remember(mark.id) { mutableStateOf(mark.color) }
    val current by rememberUpdatedState(mark.copy(note = note.trim(), color = color))
    fun close() {
        if (current.note != mark.note || current.color != mark.color) onSave(current)
        onDismiss()
    }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(color.argb)),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    mark.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                HighlightColor.entries.forEach { c ->
                    val selected = c == color
                    Surface(
                        onClick = { color = c },
                        shape = CircleShape,
                        color = Color(c.argb),
                        modifier = Modifier
                            .size(40.dp)
                            .border(
                                if (selected) 3.dp else 0.dp,
                                if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                CircleShape,
                            ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF1C1B1A), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                placeholder = { Text("Add a note") },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
            LaunchedEffect(Unit) { if (focusNote) focus.requestFocus() }
            if (onQuoteCard != null) {
                OutlinedButton(
                    onClick = {
                        if (current.note != mark.note || current.color != mark.color) onSave(current)
                        onQuoteCard(current)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Make a quote card") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete) {
                    Text("Delete highlight", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::close) { Text("Done") }
            }
        }
    }
}

@Composable
internal fun HighlightList(
    highlights: List<Highlight>,
    onOpen: (Highlight) -> Unit,
    onEdit: (Highlight) -> Unit,
    onShareAll: (() -> Unit)? = null,
) {
    if (highlights.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text("No highlights yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Press and hold on a word, drag to select a passage, then pick Highlight or Note.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
        if (onShareAll != null) {
            item(key = "share") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val notes = highlights.count { it.note.isNotBlank() }
                    Text(
                        (if (highlights.size == 1) "1 highlight" else "${highlights.size} highlights") +
                            if (notes > 0) " · $notes with notes" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onShareAll) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share all")
                    }
                }
            }
        }
        items(highlights, key = { it.id }) { mark ->
            Surface(
                onClick = { onOpen(mark) },
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .width(4.dp)
                            .height(40.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(mark.color.argb)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${mark.chapterTitle.ifBlank { "Highlight" }} · ${(mark.progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            mark.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (mark.note.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                mark.note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onEdit(mark) }) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edit highlight",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The meaning of a word picked on the page. */
@Composable
internal fun DictionarySheet(term: String, onWebSearch: () -> Unit, onDismiss: () -> Unit, onFound: (Definition) -> Unit = {}) {
    var result by remember(term) { mutableStateOf<Lookup>(Lookup.Loading) }
    LaunchedEffect(term) {
        result = Dictionary.lookUp(term)
        (result as? Lookup.Found)?.let { onFound(it.definition) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            when (val r = result) {
                Lookup.Loading -> {
                    Text(term, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(20.dp))
                }
                is Lookup.Found -> {
                    val d = r.definition
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(d.word, style = MaterialTheme.typography.headlineSmall)
                        if (d.phonetic != null) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                d.phonetic,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                    }
                    d.meanings.forEach { m ->
                        Spacer(Modifier.height(14.dp))
                        Text(
                            m.partOfSpeech,
                            style = MaterialTheme.typography.labelLarge,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        m.definitions.forEachIndexed { i, (text, example) ->
                            Spacer(Modifier.height(6.dp))
                            Row {
                                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
                                Column {
                                    Text(text, style = MaterialTheme.typography.bodyMedium)
                                    if (example != null) {
                                        Text(
                                            "“$example”",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontStyle = FontStyle.Italic,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    TextButton(onClick = onWebSearch) { Text("Search the web") }
                }
                Lookup.NotFound, Lookup.Offline -> {
                    Text(term, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (r == Lookup.NotFound) "No dictionary entry for this word."
                        else "Couldn't reach the dictionary. Check your internet connection.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onWebSearch) { Text("Search the web") }
                }
            }
        }
    }
}

/** Read-aloud options: sleep timer, speed and voice. The voice opens its own page to choose from. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadAloudSheet(
    voices: List<ReadAloud.VoiceOption>,
    voice: String?,
    engines: List<ReadAloud.EngineOption>,
    /** The engine chosen in settings; null = automatic. */
    engine: String?,
    engineInUse: String?,
    googleMissingVoices: Boolean,
    languageMissing: Boolean,
    /** Why the chosen AI voice couldn't be used, if it couldn't. */
    aiError: String?,
    /** Chooses an engine and voice together; [hear] plays a sample once it's ready. */
    onPick: (engine: String?, voice: String?, hear: Boolean) -> Unit,
    onGetGoogle: () -> Unit,
    onDownloadVoices: () -> Unit,
    rate: Float,
    /** Minutes left on the timer, if one is set. */
    sleepMinutes: Int?,
    sleepAtChapterEnd: Boolean,
    /** Plays a short sample of the current voice. */
    onPreview: () -> Unit,
    onRate: (Float) -> Unit,
    onSleep: (minutes: Int?, chapterEnd: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (picking) {
                BackHandler { picking = false }
                VoicePicker(
                    voices, voice, engines, engine, engineInUse, googleMissingVoices, languageMissing, aiError,
                    onPick, onGetGoogle, onDownloadVoices, onBack = { picking = false },
                )
                return@Column
            }
            Text("Read aloud", style = MaterialTheme.typography.headlineSmall)

            Text("Voice", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val ai = AiVoices.byEngine(engineInUse)
            val (title, kind) = if (ai != null) {
                splitSpeaker(ai.speakers.getOrNull(voice?.toIntOrNull() ?: 0) ?: "Voice").first to "AI voice \u00b7 ${ai.name}"
            } else {
                (voices.firstOrNull { it.name == voice }?.label ?: "Best available voice") to
                    "Phone voice \u00b7 " + (engines.firstOrNull { it.name == engineInUse }?.let(::engineName) ?: "your phone")
            }
            Surface(
                onClick = { picking = true },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onPreview) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Hear this voice", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (ai == null && AiVoices.supported) {
                Text(
                    "Want it to sound more like a person? Tap Change for AI voices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Speed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = rate,
                    onValueChange = { onRate((it * 20).roundToInt() / 20f) },
                    valueRange = 0.5f..2f,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    String.format(java.util.Locale.US, "%.2f", rate).trimEnd('0').trimEnd('.') + "\u00d7",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(56.dp),
                    textAlign = TextAlign.End,
                )
            }

            Text("Sleep timer", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sleepMinutes != null) {
                Text(
                    if (sleepMinutes <= 1) "Stops in under a minute" else "Stops in $sleepMinutes minutes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val off = sleepMinutes == null && !sleepAtChapterEnd
                FilterChip(selected = off, onClick = { onSleep(null, false) }, label = { Text("Off") })
                listOf(15, 30, 45, 60).forEach { m ->
                    FilterChip(
                        selected = false,
                        onClick = { onSleep(m, false) },
                        label = { Text(if (m == 60) "1 hour" else "$m min") },
                    )
                }
                FilterChip(selected = sleepAtChapterEnd, onClick = { onSleep(null, true) }, label = { Text("End of chapter") })
            }
        }
    }
}

/** A voice engine's everyday name: "Google" rather than "Speech Recognition and Synthesis from Google". */
private fun engineName(e: ReadAloud.EngineOption): String = when {
    e.name == ReadAloud.GOOGLE_TTS -> "Google"
    e.label.contains("Samsung", ignoreCase = true) -> "Samsung"
    else -> e.label
}

/** Every voice in one list: AI voices first, then the phone's own. */
@Composable
private fun VoicePicker(
    voices: List<ReadAloud.VoiceOption>,
    voice: String?,
    engines: List<ReadAloud.EngineOption>,
    engine: String?,
    engineInUse: String?,
    googleMissingVoices: Boolean,
    languageMissing: Boolean,
    aiError: String?,
    onPick: (engine: String?, voice: String?, hear: Boolean) -> Unit,
    onGetGoogle: () -> Unit,
    onDownloadVoices: () -> Unit,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Spacer(Modifier.width(4.dp))
        Text("Choose a voice", style = MaterialTheme.typography.headlineSmall)
    }
    Text(
        "Tap a voice to use it and hear how it sounds.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    AiVoicePicker(engine = engine, voice = voice, aiError = aiError, onPick = onPick)

    Spacer(Modifier.height(8.dp))
    SectionTitle("Phone voices", "Built into your phone. They work in every language and take no extra space.")
    val usingAi = AiVoices.byEngine(engineInUse) != null
    val google = engines.firstOrNull { it.name == ReadAloud.GOOGLE_TTS }
    if (google == null) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Get Google's voices", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Speech Services by Google is free and sounds more natural than most phones' built-in voice.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(onClick = onGetGoogle) { Text("Get it on Google Play") }
            }
        }
    }
    Column {
        engines.forEach { e ->
            val selected = !usingAi && engineInUse == e.name
            PickRow(engineName(e), if (e.name == ReadAloud.GOOGLE_TTS) "Usually the best phone voice" else null, selected) {
                onPick(e.name, null, true)
            }
            if (selected) {
                Column(Modifier.padding(start = 16.dp)) {
                    PickRow("Best available", "Picked for you", voice == null) { onPick(e.name, null, true) }
                    voices.forEach { v -> PickRow(v.label, v.detail, voice == v.name) { onPick(e.name, v.name, true) } }
                    if (googleMissingVoices || languageMissing) {
                        Text(
                            if (googleMissingVoices) {
                                "Google's voice for this book's language isn't downloaded yet, so your phone's own voice is reading."
                            } else {
                                "This engine has no voice downloaded for this book's language yet."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    TextButton(onClick = onDownloadVoices) { Text("Download more ${engineName(e)} voices") }
                }
            }
        }
    }
}
