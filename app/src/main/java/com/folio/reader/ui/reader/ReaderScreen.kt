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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.folio.reader.data.ReaderFont
import com.folio.reader.data.ReaderSettings
import com.folio.reader.data.ReaderTheme
import com.folio.reader.ui.theme.ChromeStyle
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.delay
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
        ReaderTheme.Dark, ReaderTheme.Black -> ChromeStyle.Dark
    }

    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Get out of the way shortly after the book opens; a tap in the middle brings controls back.
    LaunchedEffect(state.loading) {
        if (!state.loading && state.error == null) {
            delay(2_500)
            if (!showToc && !showSettings) controlsVisible = false
        }
    }

    SystemBarsEffect(visible = controlsVisible || state.error != null, darkContent = theme.isDark)

    FolioTheme(style = chrome) {
        val textColor = Color(theme.text)
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
                    onTap = { controlsVisible = !controlsVisible },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)),
                )
            }

            // A quiet progress readout while reading full screen.
            AnimatedVisibility(
                visible = !controlsVisible && !state.loading && state.error == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility),
            ) {
                Text(
                    "${(state.progress * 100).roundToInt()}%",
                    color = textColor.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && state.error == null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (state.chapterTitle.isNotEmpty()) {
                                Text(
                                    state.chapterTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to library")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showToc = true }, enabled = !state.loading) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Text("Aa", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }

            AnimatedVisibility(
                visible = controlsVisible && !state.loading && state.error == null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ReaderBottomBar(state = state, onSeek = vm::seekTo, onPrev = vm::prevChapter, onNext = vm::nextChapter)
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BookWebView(
    vm: ReaderViewModel,
    request: ChapterRequest?,
    prefs: ReaderSettings,
    theme: ReaderTheme,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val css = remember(prefs, theme) { ReaderContent.css(prefs, theme) }
    val currentCss = rememberUpdatedState(css)
    val currentOnTap = rememberUpdatedState(onTap)

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(theme.background.toInt())
            isVerticalScrollBarEnabled = false
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
            addJavascriptInterface(
                ReaderBridge(
                    scroll = { o -> view.post { vm.onScroll(o) } },
                    ready = { o -> view.post { vm.onScroll(o) } },
                    tap = { view.post { currentOnTap.value() } },
                    next = { view.post { vm.nextChapter() } },
                    prev = { view.post { vm.prevChapter() } },
                ),
                "FolioBridge",
            )
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val scheme = request.url.scheme
                    if (scheme != "http" && scheme != "https") return null
                    val book = vm.book ?: return ReaderContent.blocked()
                    return runCatching {
                        ReaderContent.serve(book, request.url, cssFor = { currentCss.value }, startFor = vm::startFor)
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
        onDispose {
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
private fun ReaderBottomBar(
    state: ReaderUiState,
    onSeek: (Float) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else state.progress

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(shown * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNext, enabled = state.chapterIndex < state.chapterCount - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next chapter")
                }
            }
        }
    }
}

@Composable
private fun TocSheet(
    state: ReaderUiState,
    onSelect: (com.folio.reader.epub.TocEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentTocIndex - 2).coerceAtLeast(0))
        LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 12.dp)) {
            itemsIndexed(state.toc) { index, entry ->
                val selected = index == state.currentTocIndex
                Surface(
                    onClick = { onSelect(entry) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        entry.title,
                        style = if (entry.level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            start = (16 + entry.level.coerceAtMost(4) * 16).dp,
                            end = 16.dp,
                            top = 14.dp,
                            bottom = 14.dp,
                        ),
                    )
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Theme
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ThemeSwatch("Auto", null, settings.theme == null) { onChange { it.copy(theme = null) } }
                ReaderTheme.entries.forEach { t ->
                    ThemeSwatch(t.label, t, settings.theme == t) { onChange { it.copy(theme = t) } }
                }
            }

            // Text size
            var zoom by remember(settings.textZoom) { mutableFloatStateOf(settings.textZoom.toFloat()) }
            SettingLabel("Text size", "${zoom.roundToInt()}%")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onChange { it.copy(textZoom = (it.textZoom - 10).coerceAtLeast(70)) } }) {
                    Text("A", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it },
                    onValueChangeFinished = { onChange { s -> s.copy(textZoom = zoom.roundToInt()) } },
                    valueRange = 70f..200f,
                    steps = 12,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                FilledTonalIconButton(onClick = { onChange { it.copy(textZoom = (it.textZoom + 10).coerceAtMost(200)) } }) {
                    Text("A", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Font
            SettingLabel("Font", null)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReaderFont.entries.forEachIndexed { i, font ->
                    SegmentedButton(
                        selected = settings.font == font,
                        onClick = { onChange { it.copy(font = font) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ReaderFont.entries.size),
                    ) {
                        Text(
                            font.label,
                            fontFamily = when (font) {
                                ReaderFont.Serif -> FontFamily.Serif
                                ReaderFont.Sans -> FontFamily.SansSerif
                                ReaderFont.Original -> FontFamily.Default
                            },
                        )
                    }
                }
            }

            // Line spacing
            var spacing by remember(settings.lineHeight) { mutableFloatStateOf(settings.lineHeight) }
            SettingLabel("Line spacing", "%.1f".format(spacing))
            Slider(
                value = spacing,
                onValueChange = { spacing = it },
                onValueChangeFinished = { onChange { s -> s.copy(lineHeight = spacing) } },
                valueRange = 1.2f..2.2f,
                steps = 9,
            )

            // Margins
            SettingLabel("Margins", null)
            val margins = listOf("Narrow" to 8, "Normal" to 20, "Wide" to 36)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                margins.forEachIndexed { i, (label, value) ->
                    SegmentedButton(
                        selected = settings.margin == value,
                        onClick = { onChange { it.copy(margin = value) } },
                        shape = SegmentedButtonDefaults.itemShape(i, margins.size),
                    ) { Text(label) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Justify text", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = settings.justify, onCheckedChange = { on -> onChange { it.copy(justify = on) } })
            }
        }
    }
}

@Composable
private fun SettingLabel(title: String, value: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                .size(52.dp)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
                .padding(4.dp)
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
        Text("Couldn't open this book", style = MaterialTheme.typography.titleLarge)
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
