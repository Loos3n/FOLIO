@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.folio.reader.FolioApp
import com.folio.reader.PageKeys
import com.folio.reader.data.ReaderFont
import com.folio.reader.data.ReaderSettings
import com.folio.reader.data.ReaderTheme
import com.folio.reader.epub.TocEntry
import com.folio.reader.ui.theme.ChromeStyle
import com.folio.reader.ui.theme.FolioFonts
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ReaderViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FolioApp, bookId)
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val request by vm.request.collectAsStateWithLifecycle()

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

    // Show the controls briefly when a book opens so they're discoverable, then get out of the way.
    LaunchedEffect(state.loading) {
        if (!state.loading && state.error == null) {
            delay(2_000)
            if (!showToc && !showSettings) controlsVisible = false
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun turn(forward: Boolean) {
        webView?.evaluateJavascript(if (forward) "window.folio && folio.next()" else "window.folio && folio.prev()", null)
    }

    val onTap: (Int) -> Unit = { zone ->
        when {
            controlsVisible -> controlsVisible = false
            zone < 0 -> turn(false)
            zone > 0 -> turn(true)
            else -> controlsVisible = true
        }
    }

    // Volume buttons turn pages while the book is open.
    val currentSettings by rememberUpdatedState(settings)
    val sheetsOpen by rememberUpdatedState(showToc || showSettings)
    DisposableEffect(Unit) {
        PageKeys.handler = PageKeys.Handler(
            enabled = { currentSettings.volumeKeys && !sheetsOpen },
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
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> BookWebView(
                    vm = vm,
                    request = request,
                    prefs = settings,
                    theme = theme,
                    ready = state.chapterReady,
                    onTap = onTap,
                    onCreated = { webView = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                        .padding(top = 30.dp, bottom = 30.dp),
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
                        pagesLeftLabel(state),
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

            AnimatedVisibility(
                visible = controlsVisible && state.error == null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(title = state.title, onBack = onBack)
            }

            AnimatedVisibility(
                visible = controlsVisible && !state.loading && state.error == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderBottomPanel(
                    state = state,
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
                onSelect = { entry ->
                    vm.openToc(entry)
                    showToc = false
                    controlsVisible = false
                },
                onDismiss = { showToc = false },
            )
        }
        if (showSettings) {
            SettingsSheet(
                settings = settings,
                onChange = vm::updateSettings,
                onDismiss = { showSettings = false },
            )
        }
    }
}

private fun pagesLeftLabel(state: ReaderUiState): String {
    if (!state.chapterReady || state.pageCount == 0) return ""
    return when (val left = state.pageCount - state.page - 1) {
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
    ready: Boolean,
    onTap: (Int) -> Unit,
    onCreated: (WebView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val css = remember(prefs, theme) { ReaderContent.css(prefs, theme) }
    val currentCss = rememberUpdatedState(css)
    val currentOnTap = rememberUpdatedState(onTap)

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(theme.background.toInt())
            alpha = 0f
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
                    pageChanged = { p, c, o -> view.post { vm.onPage(p, c, o) } },
                    loaded = { p, c, o -> view.post { vm.onPage(p, c, o) } },
                    tapped = { zone -> view.post { currentOnTap.value(zone) } },
                    nextRequested = { vm.hasNextChapter().also { if (it) view.post { vm.nextChapter() } } },
                    prevRequested = { vm.hasPrevChapter().also { if (it) view.post { vm.prevChapter() } } },
                ),
                "FolioBridge",
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val scheme = request.url.scheme
                    if (scheme != "http" && scheme != "https") return null
                    val book = vm.book ?: return ReaderContent.blocked()
                    return runCatching {
                        ReaderContent.serve(book, assets, request.url, cssFor = { currentCss.value }, startFor = vm::startFor)
                    }.getOrElse { ReaderContent.blocked() }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val path = ReaderContent.pathFor(url)
                    if (path != null) {
                        vm.openLink(path, url.fragment)
                        return true
                    }
                    if (url.scheme in setOf("http", "https", "mailto")) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    }
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
        val path = vm.book?.spine?.getOrNull(r.index)?.path ?: return@LaunchedEffect
        webView.loadUrl(ReaderContent.urlFor(path))
    }

    // Hide a chapter while it lays out, then fade it in on its first page.
    LaunchedEffect(ready) {
        webView.animate().cancel()
        if (ready) webView.animate().alpha(1f).setDuration(180).start() else webView.alpha = 0f
    }

    LaunchedEffect(css) {
        webView.setBackgroundColor(theme.background.toInt())
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

    AndroidView(factory = { webView }, modifier = modifier)
}

@Composable
private fun ReaderTopBar(title: String, onBack: () -> Unit) {
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
                    .padding(end = 16.dp),
            )
        }
    }
}

@Composable
private fun ReaderBottomPanel(
    state: ReaderUiState,
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
                            "Page ${state.page + 1} of ${state.pageCount} in this chapter",
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onContents, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Contents")
                }
                FilledTonalButton(onClick = onDisplay, modifier = Modifier.weight(1f)) {
                    Text("Aa", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("Display")
                }
            }
        }
    }
}

@Composable
private fun TocSheet(
    state: ReaderUiState,
    onSelect: (TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            "Contents",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
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
}

@Composable
private fun SettingsSheet(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
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
                        selected = settings.font == font,
                        onClick = { onChange { it.copy(font = font) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

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
            SwitchRow("Justify text", settings.justify) { on -> onChange { it.copy(justify = on) } }
            SwitchRow("Turn pages with volume buttons", settings.volumeKeys) { on -> onChange { it.copy(volumeKeys = on) } }
        }
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
private fun ErrorView(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't open this book", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Back to library") }
    }
}

/** Immersive reading: hide the system bars with the controls, and match their icon colour to the page. */
@Composable
private fun SystemBarsEffect(visible: Boolean, darkContent: Boolean) {
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
