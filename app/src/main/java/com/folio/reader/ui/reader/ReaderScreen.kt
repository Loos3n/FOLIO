@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.folio.reader.ui.reader

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.TransformOrigin
import com.folio.reader.data.PageTurn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Add
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.folio.reader.data.UserFonts
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.core.animateDpAsState
import com.folio.reader.ui.common.MessageView
import com.folio.reader.ui.common.LoadingView
import android.annotation.SuppressLint
import com.folio.reader.audio.SpeechPlayer
import android.os.PowerManager
import android.os.SystemClock
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.FolioApp
import com.folio.reader.PageKeys
import com.folio.reader.data.Bookmark
import com.folio.reader.data.Highlight
import com.folio.reader.data.HighlightExport
import com.folio.reader.ui.common.QuoteCardSheet
import com.folio.reader.data.ReaderFont
import com.folio.reader.data.ReaderSettings
import com.folio.reader.data.ReaderTheme
import com.folio.reader.epub.TocEntry
import com.folio.reader.ui.theme.ChromeStyle
import com.folio.reader.ui.theme.FolioFonts
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, onOriginalPages: (() -> Unit)? = null, markId: String? = null) {
    val vm: ReaderViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ReaderViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FolioApp, bookId, markId)
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasOwnLook by vm.hasOwnLook.collectAsStateWithLifecycle()
    val request by vm.request.collectAsStateWithLifecycle()
    val scrollMode by vm.scrollMode.collectAsStateWithLifecycle()

    val theme = settings.resolvedTheme(isSystemInDarkTheme())
    val chrome = when (theme) {
        ReaderTheme.Light -> ChromeStyle.Light
        ReaderTheme.Sepia -> ChromeStyle.Sepia
        ReaderTheme.Dark -> ChromeStyle.Dark
        ReaderTheme.Black -> ChromeStyle.Night
    }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    /** The highlight whose colour and note are being edited, and whether to start typing the note. */
    var editing by remember { mutableStateOf<Highlight?>(null) }
    var quoting by remember { mutableStateOf<Highlight?>(null) }
    var editingNote by remember { mutableStateOf(false) }
    var defineTerm by remember { mutableStateOf<String?>(null) }

    // Show the controls briefly when a book opens so they're discoverable, then get out of the way.
    LaunchedEffect(state.loading) {
        if (!state.loading && state.error == null) {
            delay(2_000)
            if (!showToc && !showSettings) controlsVisible = false
        }
    }
    var webView by remember { mutableStateOf<FolioWebView?>(null) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val bookmarkHere = vm.bookmarkHere(bookmarks, state)
    var tocTab by remember { mutableStateOf(0) }
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    // Draw this chapter's highlights each time it's laid out, and whenever they change.
    LaunchedEffect(highlights, state.chapterIndex, state.chapterReady, webView) {
        if (!state.chapterReady) return@LaunchedEffect
        val list = JSONArray()
        highlights.filter { it.chapterIndex == state.chapterIndex }.forEach {
            list.put(JSONObject().put("id", it.id).put("s", it.start).put("e", it.end).put("c", it.color.key))
        }
        webView?.evaluateJavascript("window.folio && folio.setHighlights($list)", null)
    }

    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // An action picked from the menu over selected text (see SELECTION_ACTIONS).
    fun onSelection(action: Int, json: String) {
        val sel = runCatching { JSONObject(json) }.getOrNull() ?: return
        val start = sel.optInt("s")
        val end = sel.optInt("e")
        val text = sel.optString("text")
        when (action) {
            0 -> vm.addHighlight(start, end, text)
            1 -> vm.addHighlight(start, end, text)?.let {
                editing = it
                editingNote = true
            }
            2 -> defineTerm = Dictionary.termFrom(text).ifEmpty { null }?.also { vm.saveWord(it, sel.optString("ctx")) }
            3 -> clipboard.setPrimaryClip(ClipData.newPlainText("Folio", text))
        }
    }

    fun toggleBookmark() {
        val existing = bookmarkHere
        if (existing != null) {
            vm.removeBookmark(existing)
        } else {
            webView?.evaluateJavascript("window.folio ? folio.snippet() : ''") { json ->
                vm.addBookmark(runCatching { JSONArray("[$json]").getString(0) }.getOrDefault(""))
            }
        }
    }

    // Read-aloud: the phone's voice reads from the top of the page, highlighting as it goes.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Sleep timer: stop at a set time, or when the chapter being read ends.
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    var sleepAtChapterEnd by remember { mutableStateOf(false) }
    var showReadAloudSheet by remember { mutableStateOf(false) }
    /** A footnote being shown: its text and the link to where it lives in the book. */
    var footnote by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** Read-aloud bookkeeping that must work with the screen off, outside Compose state. */
    val speechFlags = remember { SpeechFlags() }
    val readAloud = remember {
        lateinit var self: ReadAloud
        ReadAloud(
            context,
            settings.ttsEngine,
            onSentence = { i -> webView?.evaluateJavascript("window.folio && folio.ttsMark($i,-1,-1)", null) },
            onWord = { i, start, end -> webView?.evaluateJavascript("window.folio && folio.ttsMark($i,$start,$end)", null) },
            onFinished = {
                if (sleepAtChapterEnd) {
                    sleepAtChapterEnd = false
                    self.stop()
                    webView?.evaluateJavascript("window.folio && folio.ttsStop()", null)
                } else if (vm.hasNextChapter()) {
                    // Load the next chapter straight away rather than waiting for the screen to
                    // update: with the screen off it doesn't, and reading would stall.
                    speechFlags.awaitingNext = true
                    vm.nextChapter()
                    vm.request.value?.let { r -> webView?.show(r, vm) }
                } else {
                    self.stop()
                }
            },
        ).also {
            self = it
            it.rate = settings.speechRate
        }
    }
    DisposableEffect(readAloud) { onDispose { readAloud.shutdown() } }
    LaunchedEffect(settings.voice) { readAloud.setVoice(settings.voice) }
    LaunchedEffect(settings.ttsEngine) { readAloud.setEngine(settings.ttsEngine) }
    // Back from the Play Store or a voice download: look again for engines and voices.
    var voiceSetupOpened by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (voiceSetupOpened) {
            voiceSetupOpened = false
            readAloud.refresh()
        }
    }
    LaunchedEffect(readAloud.rate) {
        if (readAloud.rate != settings.speechRate) vm.updateSettings { it.copy(speechRate = readAloud.rate) }
    }
    // Listening counts as reading time too.
    LaunchedEffect(readAloud.active, readAloud.paused) {
        while (readAloud.active && !readAloud.paused) {
            delay(15_000)
            vm.recordActivity()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.resumeActivity() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pauseActivity() }
    // The timer is for this listening session only.
    LaunchedEffect(readAloud.active) {
        if (!readAloud.active) {
            sleepEndsAt = null
            sleepAtChapterEnd = false
        }
    }
    // Auto-scroll (scrolling mode only): the page moves on by itself, paused while the
    // controls or a sheet are open. Read-aloud and auto-scroll each stop the other.
    var autoScrolling by remember { mutableStateOf(false) }
    var autoPaused by remember { mutableStateOf(false) }
    LaunchedEffect(scrollMode) { if (!scrollMode) autoScrolling = false }
    LaunchedEffect(readAloud.active) { if (readAloud.active) autoScrolling = false }
    LaunchedEffect(autoScrolling) { autoPaused = false }

    var readingRequest by remember { mutableStateOf<ChapterRequest?>(null) }
    // Whether the reader went somewhere else while read-aloud was paused (however it was
    // paused: here, the notification or headphones). If not, it carries on mid-page.
    var movedWhilePaused by remember { mutableStateOf(false) }
    LaunchedEffect(readAloud.paused) { if (readAloud.paused) movedWhilePaused = false }
    LaunchedEffect(request) { if (readAloud.paused) movedWhilePaused = true }
    var restartJob by remember { mutableStateOf<Job?>(null) }

    fun startReading() {
        val wv = webView ?: return
        autoScrolling = false
        // From the view model rather than Compose state, which goes stale with the screen off.
        readingRequest = vm.request.value
        speechFlags.awaitingNext = false
        speechFlags.standingBy = false
        readAloud.setLanguage(vm.language)
        wv.evaluateJavascript("window.folio ? folio.ttsStart() : null") { json ->
            if (json == null || json == "null") {
                // The page isn't ready yet; start as soon as it reports in.
                readingRequest = null
                speechFlags.standingBy = true
                readAloud.standBy()
                return@evaluateJavascript
            }
            val texts = runCatching {
                val a = JSONArray(json)
                List(a.length()) { a.getString(it) }
            }.getOrDefault(emptyList())
            readAloud.play(texts)
        }
    }

    fun stopReading() {
        restartJob?.cancel()
        readAloud.stop()
        webView?.evaluateJavascript("window.folio && folio.ttsStop()", null)
    }

    fun togglePause() {
        if (readAloud.paused) {
            // Carry on from the sentence it stopped in, unless the reader has moved elsewhere since.
            if (movedWhilePaused) startReading() else readAloud.resume()
        } else {
            readAloud.pause()
        }
    }

    // Read-aloud with the screen off: a media session (lock screen, notification, headphone
    // buttons), a wake lock so the phone doesn't doze between sentences and chapters, and
    // the next chapter started as soon as it's laid out.
    val app = context.applicationContext as FolioApp
    val wakeLock = remember {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Folio:read-aloud")
            .apply { setReferenceCounted(false) }
    }
    fun syncSpeech() {
        val s = vm.state.value
        val cover = app.library.get(bookId)?.let { app.library.coverFile(it) }?.let { Uri.fromFile(it) }
        val speaking = readAloud.active && !readAloud.paused
        if (speaking) wakeLock.acquire(3 * 60 * 60 * 1000L) else if (wakeLock.isHeld) wakeLock.release()
        if (readAloud.active && !speechFlags.announced) {
            speechFlags.announced = true
            app.audio.speechStarted()
        }
        app.audio.speech.update(readAloud.active, speaking, s.title, s.chapterTitle, cover)
        if (!readAloud.active && speechFlags.announced) {
            speechFlags.announced = false
            app.audio.speechStopped()
        }
    }
    speechFlags.onChapterReady = {
        if (readAloud.active && !readAloud.paused && (speechFlags.awaitingNext || speechFlags.standingBy)) startReading()
        syncSpeech()
    }
    DisposableEffect(readAloud) {
        readAloud.onStateChange = { syncSpeech() }
        app.audio.speech.controls = object : SpeechPlayer.Controls {
            override fun play() {
                if (!readAloud.active) startReading() else if (readAloud.paused) togglePause()
            }

            override fun pause() {
                if (readAloud.active && !readAloud.paused) togglePause()
            }

            override fun stop() = stopReading()
            override fun skip(sentences: Int) = readAloud.skip(sentences)
        }
        onDispose {
            readAloud.onStateChange = {}
            app.audio.speech.controls = null
            app.audio.speech.update(false, false, "", "", null)
            if (speechFlags.announced) app.audio.speechStopped()
            speechFlags.announced = false
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepEndsAt) {
        val end = sleepEndsAt ?: return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            if (now >= end) {
                sleepEndsAt = null
                if (readAloud.active && !readAloud.paused) togglePause()
                break
            }
            delay(1_000)
        }
    }

    // A new chapter (or a jump) while reading: pick up from the top of the new page.
    LaunchedEffect(request, state.chapterReady) {
        if (readAloud.active && !readAloud.paused && state.chapterReady && request != null && request !== readingRequest) {
            startReading()
        }
    }

    val onManualTurn: () -> Unit = {
        if (readAloud.paused) movedWhilePaused = true
        if (readAloud.active && !readAloud.paused) {
            restartJob?.cancel()
            restartJob = scope.launch {
                delay(350) // let the page settle
                startReading()
            }
        }
    }

    fun turn(forward: Boolean) {
        webView?.evaluateJavascript(if (forward) "window.folio && folio.next()" else "window.folio && folio.prev()", null)
    }

    var bottomPanelHeight by remember { mutableIntStateOf(0) }
    // The brightness being set by sliding along the left edge, shown while it changes.
    var lightLevel by remember { mutableStateOf<Float?>(null) }
    var lightHide by remember { mutableStateOf<Job?>(null) }
    // When the menu takes a double tap, the time of the last tap that could have been the first.
    var menuTapAt by remember { mutableLongStateOf(0L) }
    val showMenu = {
        val now = SystemClock.uptimeMillis()
        if (!settings.doubleTapMenu || now - menuTapAt < DOUBLE_TAP_MS) {
            controlsVisible = true
            menuTapAt = 0L
        } else {
            menuTapAt = now
        }
    }
    val onTap: (Int) -> Unit = { zone ->
        when {
            controlsVisible -> controlsVisible = false
            // Pages turn by swiping; by tapping too only when chosen in Display, where
            // the reader also sets what each part of the page does.
            !settings.tapToTurn -> showMenu()
            else -> when (settings.tapAction(zone)) {
                'B' -> { menuTapAt = 0L; turn(false) }
                'F' -> { menuTapAt = 0L; turn(true) }
                else -> showMenu()
            }
        }
    }

    // Volume buttons turn pages while the book is open.
    val currentSettings by rememberUpdatedState(settings)
    val sheetsOpen by rememberUpdatedState(
        showToc || showSettings || showSearch || showReadAloudSheet || editing != null || defineTerm != null,
    )
    // Tell each newly laid-out chapter how fast to move (0 = stand still).
    val autoSpeed = if (autoScrolling && !autoPaused && !controlsVisible && !sheetsOpen && state.chapterReady) settings.autoScrollSpeed else 0f
    LaunchedEffect(autoSpeed, request, state.chapterReady, webView) {
        webView?.evaluateJavascript("window.folio && folio.autoScroll && folio.autoScroll($autoSpeed)", null)
    }
    DisposableEffect(Unit) {
        PageKeys.handler = PageKeys.Handler(
            // Never while reading aloud: then the buttons should set the volume.
            enabled = { currentSettings.volumeKeys && !sheetsOpen && !readAloud.active },
            turn = { forward -> turn(forward) },
        )
        onDispose { PageKeys.handler = null }
    }

    SystemBarsEffect(visible = controlsVisible || state.error != null, darkContent = theme.isDark)

    FolioTheme(style = chrome) {
        val inkColor = Color(theme.text)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(theme.background)),
        ) {
            when {
                state.error != null -> ErrorView(state.error!!, onBack, Modifier.align(Alignment.Center))
                state.loading -> LoadingView(Modifier.align(Alignment.Center))
                else -> BookWebView(
                    vm = vm,
                    request = request,
                    prefs = settings,
                    theme = theme,
                    scroll = scrollMode,
                    onTap = onTap,
                    onManualTurn = onManualTurn,
                    onSelection = ::onSelection,
                    onHighlightTap = { id ->
                        highlights.firstOrNull { it.id == id }?.let {
                            editing = it
                            editingNote = false
                        }
                    },
                    onFootnote = { text, href -> footnote = text to href },
                    onCreated = { webView = it },
                    onChapterReady = { speechFlags.onChapterReady() },
                    onEdgeLight = { delta ->
                        // From Auto, start where the phone's own brightness is.
                        val start = lightLevel ?: settings.brightness.takeIf { it >= 0f } ?: systemBrightness(context)
                        val level = (start + delta * 1.2f).coerceIn(0f, 1f)
                        vm.updateSettings { it.copy(brightness = level) }
                        lightLevel = level
                        lightHide?.cancel()
                    },
                    onEdgeLightEnd = {
                        lightHide?.cancel()
                        lightHide = scope.launch {
                            delay(900)
                            lightLevel = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                        .padding(top = 30.dp, bottom = 30.dp),
                )
            }
            lightLevel?.let { level -> LightBadge(level, Modifier.align(Alignment.CenterStart).padding(start = 20.dp)) }

            // Reading ruler (scrolling mode): a clear band to read in, with the text above and
            // below it softly dimmed. It takes no touches, so the page scrolls underneath.
            if (scrollMode && settings.ruler && !state.loading && state.error == null) {
                ReadingRuler(
                    color = Color(theme.background),
                    accent = Color(theme.link),
                    lineHeightDp = 16f * settings.textZoom / 100f * settings.lineHeight,
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                        .padding(top = 30.dp, bottom = 30.dp),
                )
            }

            // Cover a chapter while it lays out, then fade it away to reveal the first page.
            // (A Compose overlay rather than WebView alpha, which some WebViews never redraw.)
            var layoutTimedOut by remember(request) { mutableStateOf(false) }
            LaunchedEffect(request) {
                delay(1_500)
                layoutTimedOut = true
            }
            AnimatedVisibility(
                visible = !state.loading && state.error == null && !state.chapterReady && !layoutTimedOut,
                enter = EnterTransition.None,
                exit = fadeOut(tween(200)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(theme.background)),
                )
            }

            // Running header and footer, quiet enough to ignore.
            if (!state.loading && state.error == null) {
                Text(
                    state.chapterTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = inkColor.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Top))
                        .padding(top = 8.dp, start = 48.dp, end = 48.dp),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (scrollMode) timeLeftLabel(state, settings.charsPerMinute) else pagesLeftLabel(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = inkColor.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(state.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = inkColor.copy(alpha = 0.5f),
                    )
                }
            }

            // A ribbon on bookmarked pages, dropping in from the top edge.
            AnimatedVisibility(
                visible = bookmarkHere != null && !state.loading && state.error == null,
                enter = slideInVertically(spring(dampingRatio = 0.55f, stiffness = 500f)) { -it * 2 } + fadeIn(),
                exit = slideOutVertically(tween(180)) { -it * 2 } + fadeOut(tween(180)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Top))
                    .padding(end = 20.dp),
            ) {
                Icon(
                    ReaderIcons.Bookmark,
                    contentDescription = "This page is bookmarked",
                    tint = Color(theme.link),
                    modifier = Modifier.size(width = 22.dp, height = 30.dp),
                )
            }

            // Always there while reading aloud, so pausing is one tap; it moves up above the
            // controls when they're showing.
            val barLift by animateDpAsState(
                if (controlsVisible) with(LocalDensity.current) { bottomPanelHeight.toDp() } + 12.dp else 36.dp,
                label = "readAloudBar",
            )
            AnimatedVisibility(
                visible = readAloud.active,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(
                        if (controlsVisible) Modifier
                        else Modifier.windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Bottom)),
                    )
                    .padding(bottom = barLift)
                    .zIndex(1f),
            ) {
                val left = sleepEndsAt?.let { ((it - now) / 1000).coerceAtLeast(0) }
                ReadAloudBar(
                    paused = readAloud.paused,
                    sleepLabel = when {
                        left != null -> "Stops in %d:%02d".format(left / 60, left % 60)
                        sleepAtChapterEnd -> "Stops at chapter end"
                        else -> null
                    },
                    onMore = { showReadAloudSheet = true },
                    rate = readAloud.rate,
                    onPause = ::togglePause,
                    onRate = {
                        val rates = listOf(0.8f, 1f, 1.25f, 1.5f)
                        readAloud.rate = rates[(rates.indexOfFirst { abs(it - readAloud.rate) < 0.01f } + 1) % rates.size]
                    },
                    onStop = ::stopReading,
                )
            }

            AnimatedVisibility(
                visible = autoScrolling && !controlsVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Bottom))
                    .padding(bottom = 36.dp),
            ) {
                AutoScrollBar(
                    paused = autoPaused,
                    speed = settings.autoScrollSpeed,
                    onPause = { autoPaused = !autoPaused },
                    onSpeed = { faster ->
                        vm.updateSettings {
                            val next = if (faster) it.autoScrollSpeed * 1.25f else it.autoScrollSpeed / 1.25f
                            it.copy(autoScrollSpeed = next.coerceIn(AUTO_MIN, AUTO_MAX))
                        }
                    },
                    onStop = { autoScrolling = false },
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && state.error == null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    title = state.title,
                    onBack = onBack,
                    bookmarked = bookmarkHere != null,
                    onBookmark = ::toggleBookmark,
                    onSearch = { showSearch = true },
                    listening = readAloud.active,
                    onListen = {
                        if (readAloud.active) stopReading() else startReading()
                        controlsVisible = false
                    },
                    canListen = readAloud.available,
                    onOriginalPages = onOriginalPages,
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && !state.loading && state.error == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomPanelHeight = it.height },
            ) {
                ReaderBottomPanel(
                    state = state,
                    scrollMode = scrollMode,
                    timeLeft = timeLeftLabel(state, settings.charsPerMinute),
                    minutesLeft = timeLeftLabel(state, settings.charsPerMinute).removeSuffix(" left in chapter").lowercase().let {
                        if (it.isEmpty() || it.startsWith("end")) "" else if (it.startsWith("under")) it else "about $it"
                    },
                    onLayout = { vm.setScrollMode(!scrollMode) },
                    autoScrolling = autoScrolling,
                    onAutoScroll = {
                        if (autoScrolling) {
                            autoScrolling = false
                        } else {
                            if (readAloud.active) stopReading()
                            autoScrolling = true
                            controlsVisible = false
                        }
                    },
                    onSeek = vm::seekTo,
                    onPrev = vm::prevChapter,
                    onNext = vm::nextChapter,
                    onContents = { showToc = true },
                    onDisplay = { showSettings = true },
                )
            }
        }

        if (showToc) {
            TocSheet(
                state = state,
                bookmarks = bookmarks,
                highlights = highlights,
                tab = tocTab,
                onTab = { tocTab = it },
                onSelect = { entry ->
                    vm.openToc(entry)
                    showToc = false
                    controlsVisible = false
                },
                onOpenBookmark = { mark ->
                    vm.openBookmark(mark)
                    showToc = false
                    controlsVisible = false
                },
                onDeleteBookmark = vm::removeBookmark,
                onShareHighlights = { vm.book()?.let { HighlightExport.share(context, it) } },
                onOpenHighlight = { mark ->
                    vm.openHighlight(mark)
                    showToc = false
                    controlsVisible = false
                },
                onEditHighlight = {
                    editing = it
                    editingNote = false
                },
                onDismiss = { showToc = false },
            )
        }
        if (showSearch) {
            SearchSheet(
                state = search,
                onQuery = vm::search,
                onOpen = { hit ->
                    vm.openSearchHit(hit)
                    showSearch = false
                    controlsVisible = false
                },
                onDismiss = { showSearch = false },
            )
        }
        editing?.let { mark ->
            HighlightSheet(
                mark = mark,
                focusNote = editingNote,
                onSave = vm::saveHighlight,
                onDelete = {
                    vm.removeHighlight(mark)
                    editing = null
                },
                onDismiss = { editing = null },
                onQuoteCard = {
                    quoting = it
                    editing = null
                },
            )
        }
        quoting?.let { mark ->
            QuoteCardSheet(
                mark = mark,
                title = state.title,
                author = vm.book()?.author,
                onDismiss = { quoting = null },
            )
        }
        footnote?.let { (text, href) ->
            FootnoteSheet(
                text = text,
                onGoTo = {
                    footnote = null
                    val uri = Uri.parse(href)
                    ReaderContent.pathFor(uri)?.let { path -> vm.openLink(path, uri.fragment) }
                },
                onDismiss = { footnote = null },
            )
        }
        if (showReadAloudSheet) {
            ReadAloudSheet(
                voices = remember(readAloud.voicesVersion) { readAloud.voices() },
                voice = settings.voice,
                engines = remember(readAloud.voicesVersion) { readAloud.engines() },
                engine = settings.ttsEngine,
                engineInUse = readAloud.engineInUse,
                googleMissingVoices = readAloud.googleMissingVoices,
                languageMissing = readAloud.languageMissing,
                onEngine = { name -> vm.updateSettings { it.copy(ttsEngine = name, voice = null) } },
                onGetGoogle = {
                    voiceSetupOpened = true
                    VoiceSetup.getGoogle(context)
                },
                onDownloadVoices = {
                    voiceSetupOpened = true
                    VoiceSetup.downloadVoices(context, if (readAloud.googleMissingVoices) ReadAloud.GOOGLE_TTS else readAloud.engineInUse)
                },
                rate = readAloud.rate,
                sleepMinutes = sleepEndsAt?.let { ((it - now + 59_999) / 60_000).toInt() },
                sleepAtChapterEnd = sleepAtChapterEnd,
                onVoice = { name -> vm.updateSettings { it.copy(voice = name) } },
                onRate = { readAloud.rate = it },
                onSleep = { minutes, chapterEnd ->
                    sleepAtChapterEnd = chapterEnd
                    sleepEndsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
                    now = System.currentTimeMillis()
                },
                onDismiss = { showReadAloudSheet = false },
            )
        }
        defineTerm?.let { term ->
            DictionarySheet(
                term = term,
                onFound = { vm.saveMeaning(term, it) },
                onWebSearch = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("define $term"))))
                    }
                },
                onDismiss = { defineTerm = null },
            )
        }
        if (showSettings) {
            SettingsSheet(
                settings = settings,
                scrollMode = scrollMode,
                onScrollMode = vm::setScrollMode,
                onChange = vm::updateSettings,
                onDismiss = { showSettings = false },
                forBook = true,
                hasOwnLook = hasOwnLook,
                onResetLook = vm::resetLook,
            )
        }
    }
}

/** The phone's own brightness setting as 0..1 (a rough guide: phones map it differently). */
private fun systemBrightness(context: android.content.Context): Float =
    runCatching { android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }
        .getOrDefault(0.5f).coerceIn(0f, 1f)

/** How bright the screen is being set, while sliding along the left edge. */
@Composable
private fun LightBadge(level: Float, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Icon(ReaderIcons.Brightness, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .width(6.dp)
                    .height(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.25f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(level.coerceIn(0.02f, 1f))
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseOnSurface),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("${(level * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Two taps closer together than this make a double tap. */
private const val DOUBLE_TAP_MS = 350L

private const val AUTO_MIN = 8f
private const val AUTO_MAX = 240f

/** Reading speed to assume until the reader's own is learned: about 200 words a minute. */
private const val DEFAULT_CHARS_PER_MINUTE = 1000f

private fun timeLeftLabel(state: ReaderUiState, charsPerMinute: Float): String {
    if (!state.chapterReady) return ""
    val cpm = if (charsPerMinute > 0f) charsPerMinute else DEFAULT_CHARS_PER_MINUTE
    val minutes = (state.charsLeft / cpm).roundToInt()
    return when {
        state.charsLeft <= 0 -> "End of chapter"
        minutes < 1 -> "Under a minute left in chapter"
        minutes < 60 -> "$minutes min left in chapter"
        else -> "${minutes / 60} h ${minutes % 60} min left in chapter"
    }
}

private fun pagesLeftLabel(state: ReaderUiState): String {
    if (!state.chapterReady || state.pageCount == 0) return ""
    return when (val left = state.sectionEnd - state.page) {
        0 -> "Last page in chapter"
        1 -> "1 page left in chapter"
        else -> "$left pages left in chapter"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookWebView(
    vm: ReaderViewModel,
    request: ChapterRequest?,
    prefs: ReaderSettings,
    theme: ReaderTheme,
    scroll: Boolean,
    onTap: (Int) -> Unit,
    onManualTurn: () -> Unit,
    onSelection: (action: Int, json: String) -> Unit,
    onHighlightTap: (id: String) -> Unit,
    onFootnote: (text: String, href: String) -> Unit,
    onCreated: (FolioWebView?) -> Unit,
    onChapterReady: () -> Unit,
    onEdgeLight: (delta: Float) -> Unit,
    onEdgeLightEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnEdgeLight = rememberUpdatedState(onEdgeLight)
    val currentOnEdgeLightEnd = rememberUpdatedState(onEdgeLightEnd)
    val currentEdge = rememberUpdatedState(prefs.edgeBrightness)
    val currentOnChapterReady = rememberUpdatedState(onChapterReady)
    val context = LocalContext.current
    val currentOnManualTurn = rememberUpdatedState(onManualTurn)
    val currentOnSelection = rememberUpdatedState(onSelection)
    val currentOnHighlightTap = rememberUpdatedState(onHighlightTap)
    val currentOnFootnote = rememberUpdatedState(onFootnote)
    val css = remember(prefs, theme, scroll) { ReaderContent.css(prefs, theme, scroll) }
    val currentCss = rememberUpdatedState(css)
    val currentOnTap = rememberUpdatedState(onTap)
    val currentTurn = rememberUpdatedState(prefs.pageTurn)
    val turner = remember { PageTurner() }
    // Which way the finger is turning the page (set from the page script's thread).
    val dragForward = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val turnScope = rememberCoroutineScope()

    val webView = remember {
        FolioWebView(context).apply {
            selectionActions = SELECTION_ACTIONS
            onSelectionAction = { action, json -> currentOnSelection.value(action, json) }
            // Without explicit MATCH_PARENT params Compose adds the view as WRAP_CONTENT, and
            // WebView then sizes its viewport to the content, so 100vh (and the pages) break.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(theme.background.toInt())
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            keepScreenOn = true
            this.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                textZoom = prefs.textZoom
            }
            val view = this
            val assets = context.assets
            addJavascriptInterface(
                ReaderBridge(
                    pageChanged = { info -> view.post { vm.onPage(info) } },
                    loaded = { info ->
                        view.post {
                            view.evaluateJavascript("window.folio && folio.setTurn && folio.setTurn('${currentTurn.value.js}')", null)
                            view.evaluateJavascript("window.folio && folio.setEdgeLight && folio.setEdgeLight(${currentEdge.value})", null)
                            vm.onPage(info)
                            currentOnChapterReady.value()
                            turner.chapterReady()
                        }
                    },
                    tapped = { zone -> view.post { currentOnTap.value(zone) } },
                    nextRequested = { vm.hasNextChapter().also { if (it) view.post { vm.nextChapter() } } },
                    prevRequested = { vm.hasPrevChapter().also { if (it) view.post { vm.prevChapter() } } },
                    manualTurn = { view.post { currentOnManualTurn.value() } },
                    highlightTapped = { id -> view.post { currentOnHighlightTap.value(id) } },
                    footnoteTapped = { text, href -> view.post { currentOnFootnote.value(text, href) } },
                    turnRequested = { forward, edge ->
                        // At the very start or end of the book there's nowhere to turn to.
                        val turns = !edge || (if (forward) vm.hasNextChapter() else vm.hasPrevChapter())
                        view.post {
                            val move = { view.step(forward) }
                            if (turns) turner.turn(turnScope, view, forward, edge, move) else move()
                        }
                    },
                    turnStarted = { forward, edge ->
                        val turns = !edge || (if (forward) vm.hasNextChapter() else vm.hasPrevChapter())
                        if (turns) {
                            dragForward.set(forward)
                            view.post { turner.start(turnScope, view, forward, edge) { view.step(forward) } }
                        }
                        turns
                    },
                    turnDragged = { f -> view.post { turner.drag(f) } },
                    turnEnded = { commit ->
                        val forward = dragForward.get()
                        view.post { turner.end(turnScope, commit, move = { view.step(forward) }, undo = { view.step(!forward) }) }
                    },
                    edgeLight = { delta -> view.post { currentOnEdgeLight.value(delta) } },
                    edgeLightEnded = { view.post { currentOnEdgeLightEnd.value() } },
                ),
                "FolioBridge",
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val scheme = request.url.scheme
                    if (scheme != "http" && scheme != "https") return null
                    val book = vm.book ?: return ReaderContent.blocked()
                    return runCatching {
                        ReaderContent.serve(
                            book, assets, request.url, cssFor = { currentCss.value }, startFor = vm::startFor,
                            userFont = (view.context.applicationContext as FolioApp).fonts::file,
                        )
                    }.getOrElse { ReaderContent.blocked() }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val path = ReaderContent.pathFor(url)
                    if (path != null) {
                        vm.openLink(path, url.fragment)
                        return true
                    }
                    // Only a link the reader tapped leaves the app (not a book redirecting itself).
                    if (request.hasGesture() && url.scheme in setOf("http", "https", "mailto")) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    }
                    return true
                }

                // A book that crashes the page renderer closes the book, not the whole app.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    vm.onRendererGone()
                    return true
                }
            }
        }
    }

    DisposableEffect(webView) {
        onCreated(webView)
        onDispose {
            onCreated(null)
            webView.stopLoading()
            webView.destroy()
        }
    }

    // After a rotation the WebView is new; pick up exactly where the reader was.
    LaunchedEffect(webView) { vm.resume() }

    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        webView.show(r, vm)
    }

    // Restyle the page in place, except when switching between pages and scrolling: then
    // the chapter is reloaded in the new layout instead.
    var styledScroll by remember { mutableStateOf(scroll) }
    LaunchedEffect(css) {
        webView.setBackgroundColor(theme.background.toInt())
        if (styledScroll != scroll) {
            styledScroll = scroll
            return@LaunchedEffect
        }
        webView.evaluateJavascript("window.folio && folio.setStyle(${ReaderContent.jsString(css)})", null)
    }

    LaunchedEffect(prefs.textZoom) {
        if (webView.settings.textZoom != prefs.textZoom) {
            val offset = vm.currentOffset()
            webView.settings.textZoom = prefs.textZoom
            delay(150)
            webView.evaluateJavascript("window.folio && folio.restoreTo($offset)", null)
        }
    }

    LaunchedEffect(prefs.pageTurn) {
        webView.evaluateJavascript("window.folio && folio.setTurn && folio.setTurn('${prefs.pageTurn.js}')", null)
    }

    LaunchedEffect(prefs.edgeBrightness) {
        webView.evaluateJavascript("window.folio && folio.setEdgeLight && folio.setEdgeLight(${prefs.edgeBrightness})", null)
    }

    Box(modifier) {
        PageTurnUnder(turner)
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = turner.bookAngle()
                    cameraDistance = 22f * density
                },
        )
        PageTurnOver(turner)
    }
}

/** Moves one page without the page script's own animation (the app is drawing the turn). */
private fun WebView.step(forward: Boolean) =
    evaluateJavascript(if (forward) "window.folio && folio.next(true)" else "window.folio && folio.prev(true)", null)

/** Loads the chapter a request asks for, once (read-aloud may already have loaded it). */
private fun FolioWebView.show(r: ChapterRequest, vm: ReaderViewModel) {
    if (loadedToken == r.token) return
    val path = vm.book?.spine?.getOrNull(r.index)?.path ?: return
    loadedToken = r.token
    loadUrl(ReaderContent.urlFor(path))
}

/** Read-aloud state kept outside Compose, so it stays current with the screen off. */
private class SpeechFlags {
    /** Reading reached the end of a chapter; carry on once the next one is laid out. */
    var awaitingNext = false
    /** Asked to read before the page was ready. */
    var standingBy = false
    /** The media session has been told read-aloud is on. */
    var announced = false
    var onChapterReady: () -> Unit = {}
}

/** The menu over selected text; [onSelection] in ReaderScreen handles them by position. */
private val SELECTION_ACTIONS = listOf("Highlight", "Note", "Define", "Copy")

@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    bookmarked: Boolean,
    onBookmark: () -> Unit,
    onSearch: () -> Unit,
    listening: Boolean,
    onListen: () -> Unit,
    canListen: Boolean,
    onOriginalPages: (() -> Unit)? = null,
) {
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
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            )
            if (onOriginalPages != null) {
                IconButton(onClick = onOriginalPages) {
                    Icon(ReaderIcons.Pages, contentDescription = "Show the PDF's original pages")
                }
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search in book")
            }
            if (canListen) {
                IconButton(onClick = onListen) {
                    Icon(
                        if (listening) Icons.Filled.Close else ReaderIcons.Headphones,
                        contentDescription = if (listening) "Stop reading aloud" else "Read aloud",
                    )
                }
            }
            // A little pop when a page is bookmarked.
            val pop = remember { Animatable(1f) }
            var wasBookmarked by remember { mutableStateOf(bookmarked) }
            LaunchedEffect(bookmarked) {
                if (bookmarked && !wasBookmarked) {
                    pop.snapTo(0.55f)
                    pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 600f))
                }
                wasBookmarked = bookmarked
            }
            IconButton(onClick = onBookmark) {
                Icon(
                    if (bookmarked) ReaderIcons.Bookmark else ReaderIcons.BookmarkBorder,
                    contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark this page",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadAloudBar(
    paused: Boolean,
    sleepLabel: String?,
    onMore: () -> Unit,
    rate: Float,
    onPause: () -> Unit,
    onRate: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onPause) {
                Icon(
                    if (paused) ReaderIcons.Play else ReaderIcons.Pause,
                    contentDescription = if (paused) "Resume reading" else "Pause reading",
                )
            }
            Text(
                if (paused) "Paused" else sleepLabel ?: "Reading aloud",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Surface(
                onClick = onRate,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    (if (rate % 1f == 0f) rate.toInt().toString() else rate.toString()) + "\u00D7",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Voice and sleep timer")
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop reading aloud")
            }
        }
    }
}

@Composable
private fun AutoScrollBar(
    paused: Boolean,
    speed: Float,
    onPause: () -> Unit,
    onSpeed: (faster: Boolean) -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onPause) {
                Icon(
                    if (paused) ReaderIcons.Play else ReaderIcons.Pause,
                    contentDescription = if (paused) "Resume auto-scroll" else "Pause auto-scroll",
                )
            }
            Text(
                if (paused) "Paused" else "Auto-scroll",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            IconButton(onClick = { onSpeed(false) }, enabled = speed > AUTO_MIN + 0.01f) {
                Text("\u2212", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                // Shown as a 1-to-10ish scale rather than pixels.
                (speed / 4f).roundToInt().let { if (it % 10 == 0) "${it / 10}" else "${it / 10}.${it % 10}" } + "\u00D7",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(44.dp),
            )
            IconButton(onClick = { onSpeed(true) }, enabled = speed < AUTO_MAX - 0.01f) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stop auto-scroll")
            }
        }
    }
}

/** A band to read in: the text above and below it is dimmed with the page colour. */
@Composable
private fun ReadingRuler(color: Color, accent: Color, lineHeightDp: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val band = (lineHeightDp * 2.2f).dp.toPx()
        val top = size.height * 0.3f
        val dim = color.copy(alpha = 0.6f)
        drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(dim, topLeft = Offset(0f, top + band), size = Size(size.width, size.height - top - band))
        drawRect(accent.copy(alpha = 0.07f), topLeft = Offset(0f, top), size = Size(size.width, band))
        val edge = 1.dp.toPx()
        drawRect(accent.copy(alpha = 0.35f), topLeft = Offset(0f, top - edge), size = Size(size.width, edge))
        drawRect(accent.copy(alpha = 0.35f), topLeft = Offset(0f, top + band), size = Size(size.width, edge))
    }
}

@Composable
private fun ReaderBottomPanel(
    state: ReaderUiState,
    scrollMode: Boolean,
    timeLeft: String,
    minutesLeft: String,
    onLayout: () -> Unit,
    autoScrolling: Boolean,
    onAutoScroll: () -> Unit,
    onSeek: (Float) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onContents: () -> Unit,
    onDisplay: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else state.progress

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.chapterTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.pageCount > 0) {
                        Text(
                            if (scrollMode) timeLeft else pagesLeftLabel(state) + minutesLeft.let { if (it.isEmpty()) "" else " \u00B7 $it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(shown * 100).roundToInt()}%",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev, enabled = state.chapterIndex > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous chapter")
                }
                Slider(
                    value = shown,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        onSeek(dragValue)
                        dragging = false
                    },
                    colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext, enabled = state.chapterIndex < state.chapterCount - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next chapter")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // With the auto-scroll button there too, the labels drop their glyphs to fit.
                val roomy = !scrollMode
                val padding = PaddingValues(horizontal = if (roomy) 24.dp else 12.dp)
                FilledTonalButton(onClick = onContents, contentPadding = padding, modifier = Modifier.weight(1f)) {
                    if (roomy) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Contents", maxLines = 1)
                }
                FilledTonalButton(onClick = onDisplay, contentPadding = padding, modifier = Modifier.weight(1f)) {
                    if (roomy) {
                        Text("Aa", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Display", maxLines = 1)
                }
                // One tap between turning pages and scrolling; remembered for this book.
                FilledTonalIconButton(onClick = onLayout) {
                    Icon(
                        if (scrollMode) ReaderIcons.Pages else ReaderIcons.Scroll,
                        contentDescription = if (scrollMode) "Switch to pages" else "Switch to scrolling",
                    )
                }
                if (scrollMode) {
                    FilledTonalIconButton(
                        onClick = onAutoScroll,
                        colors = if (autoScrolling) {
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            IconButtonDefaults.filledTonalIconButtonColors()
                        },
                    ) {
                        Icon(ReaderIcons.AutoScroll, contentDescription = if (autoScrolling) "Stop auto-scroll" else "Auto-scroll")
                    }
                }
            }
        }
    }
}

@Composable
private fun TocSheet(
    state: ReaderUiState,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    tab: Int,
    onTab: (Int) -> Unit,
    onSelect: (TocEntry) -> Unit,
    onOpenBookmark: (Bookmark) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onOpenHighlight: (Highlight) -> Unit,
    onEditHighlight: (Highlight) -> Unit,
    onShareHighlights: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        ) {
            listOf("Contents", "Bookmarks", "Highlights").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i,
                    onClick = { onTab(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                    icon = {},
                ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
        when (tab) {
            0 -> {
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentTocIndex - 2).coerceAtLeast(0))
                LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 12.dp)) {
                    itemsIndexed(state.toc) { index, entry ->
                        val selected = index == state.currentTocIndex
                        Surface(
                            onClick = { onSelect(entry) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                Modifier.padding(
                                    start = (16 + entry.level.coerceAtMost(4) * 16).dp,
                                    end = 16.dp,
                                    top = 14.dp,
                                    bottom = 14.dp,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (selected) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }
                                Text(
                                    entry.title,
                                    style = if (entry.level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
            1 -> BookmarkList(bookmarks, onOpenBookmark, onDeleteBookmark)
            else -> HighlightList(highlights, onOpenHighlight, onEditHighlight, onShareAll = onShareHighlights)
        }
    }
}

@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    onOpen: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                ReaderIcons.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("No bookmarks yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap the middle of a page, then the bookmark at the top to save your place.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
        itemsIndexed(bookmarks, key = { _, b -> b.id }) { _, mark ->
            Surface(
                onClick = { onOpen(mark) },
                shape = RoundedCornerShape(16.dp),
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        ReaderIcons.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${mark.chapterTitle.ifBlank { "Bookmark" }} \u00B7 ${(mark.progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (mark.snippet.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                mark.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onDelete(mark) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete bookmark",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun SettingsSheet(
    settings: ReaderSettings,
    scrollMode: Boolean,
    onScrollMode: (Boolean) -> Unit,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    /** Opened from inside a book (rather than the library), so a book's own look applies. */
    forBook: Boolean = false,
    hasOwnLook: Boolean = false,
    onResetLook: () -> Unit = {},
) {
    val assets = LocalContext.current.assets
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Display", style = MaterialTheme.typography.headlineSmall)

            SectionLabel("Light")
            val followPhone = settings.brightness < 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ReaderIcons.Brightness, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = if (followPhone) 0.5f else settings.brightness,
                    onValueChange = { v -> onChange { it.copy(brightness = v) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = followPhone,
                    onClick = { onChange { it.copy(brightness = if (followPhone) 0.5f else -1f) } },
                    label = { Text("Auto") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ReaderIcons.Warmth, contentDescription = null, tint = Color(0xFFE08A3C))
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = settings.warmth,
                    onValueChange = { v -> onChange { it.copy(warmth = if (v < 0.04f) 0f else v) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (settings.warmth <= 0f) "Off" else "Warm",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.Center,
                )
            }

            SectionLabel("Layout")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Pages" to false, "Scrolling" to true).forEachIndexed { i, (label, value) ->
                    SegmentedButton(
                        selected = scrollMode == value,
                        onClick = { onScrollMode(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            SwitchRow("Open PDFs as pages", settings.pdfOpenAsPages) { on -> onChange { it.copy(pdfOpenAsPages = on) } }
            if (scrollMode) {
                SwitchRow("Reading ruler", settings.ruler) { on -> onChange { it.copy(ruler = on) } }
            } else {
                SectionLabel("Page turn")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PageTurn.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = settings.pageTurn == t,
                            onClick = { onChange { it.copy(pageTurn = t) } },
                            shape = SegmentedButtonDefaults.itemShape(i, PageTurn.entries.size),
                        ) { Text(t.label) }
                    }
                }
            }

            SectionLabel("Look")
            SwitchRow("Each book keeps its own look", settings.perBookLook) { on -> onChange { it.copy(perBookLook = on) } }
            if (settings.perBookLook) {
                Text(
                    if (forBook) "The theme, font, size, spacing and margins you pick here are for this book only."
                    else "These are used for books you haven't given a look of their own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (forBook && hasOwnLook) {
                    TextButton(onClick = onResetLook, contentPadding = PaddingValues(0.dp)) { Text("Use the shared look for this book") }
                }
            }

            SectionLabel("Theme")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ThemeSwatch("Auto", null, settings.theme == null) { onChange { it.copy(theme = null) } }
                ReaderTheme.entries.forEach { t ->
                    ThemeSwatch(t.label, t, settings.theme == t) { onChange { it.copy(theme = t) } }
                }
            }

            SectionLabel("Font")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { font ->
                    val family = remember(font) {
                        when (font) {
                            ReaderFont.Original -> FontFamily.Serif
                            ReaderFont.Literata -> FolioFonts.literata(assets)
                            ReaderFont.Inter -> FolioFonts.inter(assets)
                            ReaderFont.Atkinson -> FolioFonts.atkinson(assets)
                        }
                    }
                    FontCard(
                        label = font.label,
                        family = family,
                        selected = settings.customFont == null && settings.font == font,
                        onClick = { onChange { it.copy(font = font, customFont = null) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            UserFontRow(settings, onChange)

            SectionLabel("Text size")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onChange { it.copy(textZoom = (it.textZoom - 10).coerceAtLeast(70)) } },
                    enabled = settings.textZoom > 70,
                    modifier = Modifier.size(52.dp),
                ) { Text("A", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Text(
                    "${settings.textZoom}%",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { onChange { it.copy(textZoom = (it.textZoom + 10).coerceAtMost(200)) } },
                    enabled = settings.textZoom < 200,
                    modifier = Modifier.size(52.dp),
                ) { Text("A", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            }

            SectionLabel("Line spacing")
            val spacings = listOf("Tight" to 1.4f, "Normal" to 1.6f, "Loose" to 1.85f)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                spacings.forEachIndexed { i, (label, value) ->
                    SegmentedButton(
                        selected = abs(settings.lineHeight - value) < 0.05f,
                        onClick = { onChange { it.copy(lineHeight = value) } },
                        shape = SegmentedButtonDefaults.itemShape(i, spacings.size),
                    ) { Text(label) }
                }
            }

            SectionLabel("Margins")
            val margins = listOf("Narrow" to 16, "Normal" to 24, "Wide" to 36)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                margins.forEachIndexed { i, (label, value) ->
                    SegmentedButton(
                        selected = settings.margin == value,
                        onClick = { onChange { it.copy(margin = value) } },
                        shape = SegmentedButtonDefaults.itemShape(i, margins.size),
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(2.dp))
            SwitchRow("Justify and hyphenate", settings.justify) { on -> onChange { it.copy(justify = on) } }
            SwitchRow("Turn pages by tapping", settings.tapToTurn) { on -> onChange { it.copy(tapToTurn = on) } }
            if (settings.tapToTurn) TapZones(settings.tapZones) { zones -> onChange { it.copy(tapZones = zones) } }
            SwitchRow("Double-tap to show the menu", settings.doubleTapMenu) { on -> onChange { it.copy(doubleTapMenu = on) } }
            SwitchRow("Slide along the left edge for brightness", settings.edgeBrightness) { on -> onChange { it.copy(edgeBrightness = on) } }
            SwitchRow("Two pages side by side when sideways", settings.twoPages) { on -> onChange { it.copy(twoPages = on) } }
            SwitchRow("Turn pages with volume buttons", settings.volumeKeys) { on -> onChange { it.copy(volumeKeys = on) } }
        }
    }
}

/** Fonts the reader added from their phone, and a card to add another. */
@Composable
private fun UserFontRow(settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as FolioApp
    val fonts by app.fonts.fonts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            app.fonts.add(uri)
                .onSuccess { f -> onChange { it.copy(customFont = f.fileName) } }
                .onFailure { Toast.makeText(context, it.message ?: "Couldn't add that font.", Toast.LENGTH_LONG).show() }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fonts.forEach { font ->
            val family = remember(font.fileName) {
                app.fonts.file(font.fileName)
                    ?.let { f -> runCatching { android.graphics.Typeface.createFromFile(f) }.getOrNull() }
                    ?.let { FontFamily(androidx.compose.ui.text.font.Typeface(it)) }
                    ?: FontFamily.Serif
            }
            FontCard(
                label = font.label,
                family = family,
                selected = settings.customFont == font.fileName,
                onClick = { onChange { it.copy(customFont = font.fileName) } },
                modifier = Modifier.width(84.dp),
            )
        }
        Surface(
            onClick = { pick.launch(UserFonts.MIME_TYPES) },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.width(84.dp),
        ) {
            Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(2.dp))
                Text("Add font", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
    val chosen = fonts.firstOrNull { it.fileName == settings.customFont }
    if (chosen != null) {
        TextButton(onClick = {
            onChange { it.copy(customFont = null) }
            app.fonts.remove(chosen)
        }) { Text("Remove “${chosen.label}”") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * A small page split into nine parts; tapping one changes what a tap there does while
 * reading: back, menu or forward. At least one part always opens the menu.
 */
@Composable
private fun TapZones(zones: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Tap a part of the page to change what it does. One part always opens the menu.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .width(168.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)),
        ) {
            for (row in 0..2) {
                Row {
                    for (col in 0..2) {
                        val zone = row * 3 + col
                        val action = zones.getOrNull(zone) ?: ReaderSettings.DEFAULT_TAP_ZONES[zone]
                        val (label, tint) = when (action) {
                            'B' -> "Back" to scheme.secondaryContainer
                            'F' -> "Next" to scheme.primaryContainer
                            else -> "Menu" to scheme.surfaceContainerHigh
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(76.dp)
                                .background(tint)
                                .border(0.5.dp, scheme.outlineVariant)
                                .clickable {
                                    val order = "BMF"
                                    val next = order[(order.indexOf(action) + 1) % 3]
                                    val changed = zones.padEnd(9, 'M').replaceRange(zone, zone + 1, next.toString())
                                    // The last part that opens the menu stays that way.
                                    if ('M' in changed) onChange(changed)
                                },
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
                        }
                    }
                }
            }
        }
        if (zones != ReaderSettings.DEFAULT_TAP_ZONES) {
            TextButton(onClick = { onChange(ReaderSettings.DEFAULT_TAP_ZONES) }) { Text("Reset") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FontCard(label: String, family: FontFamily, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Aa", fontFamily = family, fontSize = 24.sp)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ThemeSwatch(label: String, theme: ReaderTheme?, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        Box(
            Modifier
                .size(54.dp)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
                .padding(5.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (theme == null) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(ReaderTheme.Light.background)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(ReaderTheme.Dark.background)))
                }
                Text("Aa", color = Color(0xFF808080), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
            } else {
                Box(Modifier.fillMaxSize().background(Color(theme.background)))
                Text("Aa", color = Color(theme.text), fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ErrorView(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    MessageView("Couldn't open this book", message, modifier, action = "Back to library", onAction = onBack)
}

/** Immersive reading: hide the system bars with the controls, and match their icon colour to the page. */
@Composable
internal fun SystemBarsEffect(visible: Boolean, darkContent: Boolean) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }

    DisposableEffect(visible, darkContent) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.isAppearanceLightStatusBars = !darkContent
        controller.isAppearanceLightNavigationBars = !darkContent
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(controller) {
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !systemDark
            controller.isAppearanceLightNavigationBars = !systemDark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** A footnote, shown over the page so the reader doesn't lose their place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FootnoteSheet(text: String, onGoTo: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                "Note",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onGoTo) { Text("Go to note") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
