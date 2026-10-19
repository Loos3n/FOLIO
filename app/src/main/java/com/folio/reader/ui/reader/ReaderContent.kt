package com.folio.reader.ui.reader

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import com.folio.reader.data.HighlightColor
import com.folio.reader.data.ReaderSettings
import com.folio.reader.data.ReaderTheme
import com.folio.reader.epub.EpubBook
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Serves a book to the WebView from inside the EPUB archive, under a private origin.
 * Chapters get the reader stylesheet and script injected, which lay the chapter out as
 * side-by-side pages; everything off-book is blocked so books can't phone home.
 */
/**
 * Where a chapter should open, plus what the page script needs to know about it: the
 * book's language (for hyphenation) and the ids where each table-of-contents section in
 * this file starts ("" for the top of the file), in reading order.
 */
data class ChapterStart(
    val offset: Float,
    val fragment: String?,
    val language: String?,
    val sections: List<String>,
    /** A text position to open at instead of [offset] (see [ChapterRequest.charOffset]). */
    val charOffset: Int? = null,
    /** A search pattern and which match of it in this file to open at and mark. */
    val find: String? = null,
    val findIndex: Int = 0,
    /** Lay the chapter out as one long scrolling column instead of pages. */
    val scroll: Boolean = false,
    /** The next chapter's title, shown past the end of this one in scrolling mode (null at the end of the book). */
    val nextTitle: String? = null,
)

object ReaderContent {
    const val HOST = "folio.book"
    const val ORIGIN = "https://$HOST/"
    private const val INTERNAL = "__folio__/"

    fun urlFor(path: String): String =
        ORIGIN + path.split('/').joinToString("/") { Uri.encode(it) }

    /** Maps a WebView URL back to a path inside the archive, or null if it isn't ours. */
    fun pathFor(url: Uri): String? {
        if (url.host != HOST) return null
        return url.path?.removePrefix("/")
    }

    fun serve(
        book: EpubBook,
        assets: AssetManager,
        url: Uri,
        cssFor: () -> String,
        startFor: (path: String) -> ChapterStart,
    ): WebResourceResponse {
        val path = pathFor(url) ?: return blocked()
        if (path == INTERNAL + "reader.js") {
            return WebResourceResponse("text/javascript", "UTF-8", ByteArrayInputStream(SCRIPT.toByteArray()))
        }
        if (path.startsWith(INTERNAL + "fonts/")) {
            val name = path.substringAfterLast('/')
            val stream = runCatching { assets.open("fonts/$name") }.getOrNull() ?: return notFound()
            return WebResourceResponse("font/ttf", null, stream)
        }
        val mediaType = book.mediaTypeFor(path)
        val isDocument = mediaType == "application/xhtml+xml" || mediaType == "text/html"
        if (isDocument) {
            val bytes = book.readBytes(path) ?: return notFound()
            val html = inject(String(bytes, Charsets.UTF_8), cssFor(), startFor(path))
            return WebResourceResponse(mediaType, "UTF-8", ByteArrayInputStream(html.toByteArray()))
        }
        val stream = book.open(path) ?: return notFound()
        val encoding = if (mediaType.startsWith("text/")) "UTF-8" else null
        return WebResourceResponse(mediaType, encoding, stream)
    }

    fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun inject(html: String, css: String, start: ChapterStart): String {
        val query = buildString {
            append("o=").append(String.format(Locale.US, "%.5f", start.offset))
            if (start.fragment != null) append("&amp;f=").append(Uri.encode(start.fragment))
            if (start.language != null) append("&amp;l=").append(Uri.encode(start.language))
            if (start.sections.isNotEmpty()) {
                // Each id is encoded, so the comma separator can't appear inside one.
                append("&amp;s=").append(Uri.encode(start.sections.joinToString(",") { Uri.encode(it) }))
            }
            if (start.charOffset != null) append("&amp;c=").append(start.charOffset)
            if (start.find != null) append("&amp;q=").append(Uri.encode(start.find)).append("&amp;n=").append(start.findIndex)
            if (start.scroll) {
                append("&amp;m=s")
                if (start.nextTitle != null) append("&amp;nt=").append(Uri.encode(start.nextTitle))
            }
        }
        // Valid in both HTML and XHTML documents (no '<' or '&' inside the CSS).
        val tags = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, user-scalable=no\"/>" +
            "<style id=\"folio-style\">$css</style>" +
            "<script src=\"$ORIGIN${INTERNAL}reader.js?$query\"></script>"
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        if (headEnd >= 0) return html.substring(0, headEnd) + tags + html.substring(headEnd)
        val bodyStart = html.indexOf("<body", ignoreCase = true)
        if (bodyStart >= 0) {
            val tagEnd = html.indexOf('>', bodyStart)
            if (tagEnd >= 0) return html.substring(0, tagEnd + 1) + tags + html.substring(tagEnd + 1)
        }
        return tags + html
    }

    private val FONT_FACES = buildString {
        val f = "$ORIGIN${INTERNAL}fonts/"
        append("@font-face{font-family:'Folio Literata';src:url('${f}literata.ttf');font-weight:200 900;font-style:normal;}")
        append("@font-face{font-family:'Folio Literata';src:url('${f}literata-italic.ttf');font-weight:200 900;font-style:italic;}")
        append("@font-face{font-family:'Folio Inter';src:url('${f}inter.ttf');font-weight:100 900;}")
        append("@font-face{font-family:'Folio Atkinson';src:url('${f}atkinson.ttf');font-weight:400;}")
        append("@font-face{font-family:'Folio Atkinson';src:url('${f}atkinson-bold.ttf');font-weight:700;}")
    }

    fun css(settings: ReaderSettings, theme: ReaderTheme, scroll: Boolean = false): String {
        val bg = hex(theme.background)
        val fg = hex(theme.text)
        val link = hex(theme.link)
        val m = settings.margin
        val lh = String.format(Locale.US, "%.2f", settings.lineHeight)
        return buildString {
            append(FONT_FACES)
            if (scroll) {
                // Scrolling layout: one long column that the WebView scrolls natively.
                append("html{margin:0 !important;padding:0 ${m}px !important;height:auto !important;width:auto !important;")
                append("max-width:none !important;box-sizing:border-box !important;overflow-x:hidden !important;")
                append("background:$bg !important;color:$fg !important;}")
                append("body{margin:0 !important;padding:5vh 0 0 !important;height:auto !important;width:auto !important;")
                append("max-width:none !important;box-sizing:border-box !important;background:$bg !important;color:$fg !important;")
                append("position:relative !important;-webkit-columns:auto !important;columns:auto !important;transform:none !important;")
                append("line-height:$lh !important;overflow-wrap:break-word;-webkit-hyphens:auto;hyphens:auto;")
                append("-webkit-tap-highlight-color:transparent;}")
            } else {
                // Paged layout: the body flows into columns exactly one screen wide, and the
                // script moves between them. --folio-w is set by the script to the real viewport width.
                append("html{margin:0 !important;padding:0 ${m}px !important;height:100vh !important;width:auto !important;")
                append("max-width:none !important;box-sizing:border-box !important;overflow:hidden !important;")
                append("background:$bg !important;color:$fg !important;}")
                append("body{margin:0 !important;padding:0 !important;height:100vh !important;width:auto !important;")
                append("max-width:none !important;box-sizing:border-box !important;background:$bg !important;color:$fg !important;")
                append("-webkit-column-width:calc(var(--folio-w,100vw) - ${2 * m}px) !important;column-width:calc(var(--folio-w,100vw) - ${2 * m}px) !important;")
                append("-webkit-column-gap:${2 * m}px !important;column-gap:${2 * m}px !important;column-fill:auto !important;")
                append("line-height:$lh !important;overflow-wrap:break-word;-webkit-hyphens:auto;hyphens:auto;")
                append("-webkit-tap-highlight-color:transparent;will-change:transform;}")
            }
            append("body *{color:inherit !important;background-color:transparent !important;border-color:${fg}33 !important;}")
            append("body p,body li,body blockquote,body div,body span,body dd,body dt,body td{line-height:inherit !important;}")
            append("a[href],a[href] *{color:$link !important;text-decoration:none !important;}")
            append("img,video,svg{max-width:100% !important;max-height:calc(100vh - 8px) !important;")
            append("object-fit:contain;break-inside:avoid;-webkit-column-break-inside:avoid;}")
            append("img,video{height:auto !important;}")
            append("h1,h2,h3,h4{break-after:avoid;-webkit-column-break-after:avoid;line-height:1.25 !important;")
            append("-webkit-hyphens:manual;hyphens:manual;text-wrap:balance;}")
            // Book headings get room to breathe and sit centred, like a printed chapter opening.
            append("h1,h2,h3{text-align:center !important;text-indent:0 !important;margin:1.4em 0 1em !important;")
            append("font-weight:600 !important;letter-spacing:0.01em;}")
            append("h1{font-size:1.5em !important;}h2{font-size:1.3em !important;}h3{font-size:1.1em !important;}")
            // Chapter openings start on a fresh page (the script marks which headings qualify).
            append(".folio-break{break-before:column !important;-webkit-column-break-before:always !important;margin-top:18vh !important;}")
            append(".folio-break-first{margin-top:18vh !important;}")
            if (scroll) {
                // No pages to start fresh on; a chapter heading just gets some space above it.
                append(".folio-break{break-before:auto !important;-webkit-column-break-before:auto !important;margin-top:3em !important;}")
                append(".folio-break-first{margin-top:6vh !important;}")
                append("img,video,svg{max-height:80vh !important;}")
                // Past the end of the chapter: a screen of room, labelled with what comes next.
                append("#folio-next{height:100vh;margin:0;padding-top:12vh;box-sizing:border-box;text-align:center;}")
                append("#folio-next::before{display:block;white-space:pre-line;line-height:1.5;opacity:.6;font-size:.9em;letter-spacing:.04em;}")
                append("#folio-next.folio-more::before{content:'NEXT' '\\A' attr(data-label);}")
                append("#folio-next.folio-end::before{content:'The end';}")
                append("#folio-next::after{content:'';display:block;width:40px;margin:1.2em auto 0;border-top:1px solid ${fg}40;}")
            }
            // Books whose paragraphs have neither indent nor spacing run together; give them
            // the usual book treatment of indenting every paragraph after the first.
            append(".folio-indent p{margin-top:0 !important;margin-bottom:0 !important;}")
            append(".folio-indent p+p{text-indent:1.5em !important;}")
            append("hr{margin:1.5em 20% !important;border:0 !important;border-top:1px solid ${fg}40 !important;height:0 !important;}")
            append("pre{white-space:pre-wrap !important;}")
            append("::selection{background:${link}55;}")
            // Read-aloud: the sentence being spoken, and the word within it.
            append("::highlight(folio-sentence){background-color:${link}26;}")
            append("::highlight(folio-word){background-color:${link}66;}")
            // Fallback boxes (see highlight() in the script); box-shadow because backgrounds are cleared above.
            append(".folio-mark{position:absolute;left:0;top:0;z-index:-1;pointer-events:none;}")
            append(".folio-mark div{position:absolute;border-radius:3px;}")
            append(".folio-sentence div{box-shadow:inset 0 0 0 999px ${link}26;}")
            append(".folio-word div{box-shadow:inset 0 0 0 999px ${link}66;}")
            // The reader's own highlights, and a search match being shown.
            for (c in HighlightColor.entries) {
                val tint = hex(c.argb) + if (theme.isDark) "66" else "59"
                append("::highlight(folio-hl-${c.key}){background-color:$tint;}")
                append(".folio-hl-${c.key} div{box-shadow:inset 0 0 0 999px $tint;}")
            }
            append("::highlight(folio-find){background-color:${link}40;text-decoration:underline;}")
            append(".folio-find div{box-shadow:inset 0 0 0 999px ${link}40;}")
            // .folio-p marks the book's ordinary body paragraphs (not centred or right-aligned ones),
            // so the justify setting applies both ways without flattening title pages.
            if (settings.justify) append(".folio-p{text-align:justify !important;text-align-last:start;}")
            else append(".folio-p{text-align:start !important;}")
            settings.font.css?.let { family ->
                append("body,body *:not(pre):not(code):not(kbd):not(samp){font-family:$family !important;}")
            }
        }
    }

    fun hex(argb: Long) = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** Escapes a Kotlin string into a JavaScript string literal. */
    fun jsString(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '<' -> append("\\u003C")
            else -> append(c)
        }
        append('"')
    }

    private val SCRIPT = """
        (function () {
          var src = (document.currentScript && document.currentScript.src) || '';
          var params = {};
          (src.split('?')[1] || '').split('&').forEach(function (kv) {
            var i = kv.indexOf('=');
            if (i > 0) params[kv.slice(0, i)] = decodeURIComponent(kv.slice(i + 1));
          });
          var start = parseFloat(params.o || '0') || 0;
          var fragment = params.f || '';
          var sections = params.s ? params.s.split(',').map(decodeURIComponent) : [];
          var target = null; // a text range to open at (search match or highlight), until shown
          // Scrolling mode: the chapter is one long column read top to bottom, and a "page"
          // is a screen's height of it. Otherwise it's laid out as side-by-side pages.
          var scrollMode = params.m === 's';
          var bridge = window.FolioBridge;
          var root = document.documentElement;
          var page = 0;
          var ready = false;
          var anim = null;
          var curX = 0;

          function width() { return window.innerWidth || root.clientWidth || 0; }
          function height() { return window.innerHeight || root.clientHeight || 0; }
          function setWidthVar() { root.style.setProperty('--folio-w', width() + 'px'); }
          // Scrolling mode: where the chapter's text ends (the "next chapter" spacer starts),
          // and the furthest it's read to: its last line near the bottom of the screen.
          function textEnd() {
            var spacer = document.getElementById('folio-next');
            return spacer ? spacer.offsetTop : (document.body ? document.body.scrollHeight : 0);
          }
          function readMax() { return Math.max(0, textEnd() - height() * 0.9); }
          function pageCount() {
            var body = document.body;
            if (!body || width() < 10) return 1;
            if (scrollMode) return Math.max(1, Math.ceil(readMax() / Math.max(1, height())) + 1);
            return Math.max(1, Math.round((body.scrollWidth + 2 * parseFloat(getComputedStyle(root).paddingLeft || '0')) / width()));
          }
          function lastPage() { return pageCount() - 1; }
          function offset() {
            if (scrollMode) { var m = readMax(); return m <= 0 ? 1 : Math.max(0, Math.min(1, window.scrollY / m)); }
            var n = pageCount();
            return n <= 1 ? 1 : page / (n - 1);
          }
          function scrollPage() { return Math.max(0, Math.min(lastPage(), Math.round(window.scrollY / Math.max(1, height())))); }
          var leaving = false; // set once we've asked for another chapter; this page is done reporting
          // Which table-of-contents section the current page is in, and its last page.
          function pageOfElement(el) {
            var r = el.getBoundingClientRect();
            if (scrollMode) return Math.max(0, Math.floor((r.top + window.scrollY + height() * 0.3) / Math.max(1, height())));
            return Math.max(0, Math.floor((r.left + curX + 1) / width()));
          }
          function sectionInfo() {
            var current = -1, end = lastPage(), next = null;
            for (var i = 0; i < sections.length; i++) {
              var id = sections[i];
              var el = id ? (document.getElementById(id) || document.getElementsByName(id)[0]) : null;
              var p = id ? (el ? pageOfElement(el) : -1) : 0;
              if (p < 0) continue;
              if (p <= page) current = i;
              else { end = Math.min(end, p - 1); next = el; break; }
            }
            return [current, end, next];
          }
          // How many characters of the current section are left from the top of this page,
          // and where the page starts; the app turns these into "minutes left".
          function progressChars(next) {
            var st = textStart();
            if (!st) return [0, buildIndex().total];
            var at = offsetOf(st.node, st.offset);
            var end = next ? offsetOf(next, 0) : buildIndex().total;
            return [Math.max(0, end - at), at];
          }
          function report() {
            if (!bridge || !ready || leaving) return;
            var s = sectionInfo(), c = progressChars(s[2]);
            bridge.onPage(page, pageCount(), offset(), s[0], s[1], c[0], c[1]);
          }

          // One-time tidy-up of the book's own formatting, before the first layout.
          var prepared = false;
          function prepare() {
            if (prepared || !document.body) return;
            prepared = true;
            if (params.l && !root.getAttribute('lang')) root.setAttribute('lang', params.l);
            // Paragraphs with no first-line indent and no space between them read as one block.
            var ps = document.body.getElementsByTagName('p'), plain = 0, sampled = 0;
            // Read each paragraph's own alignment with our stylesheet switched off.
            var own = document.getElementById('folio-style');
            if (own) own.disabled = true;
            // Paragraph-like blocks: every <p>, plus <div>s that hold running text directly.
            var blocks = Array.prototype.slice.call(ps);
            var divs = document.body.getElementsByTagName('div');
            for (var d = 0; d < divs.length; d++) {
              for (var n = divs[d].firstChild; n; n = n.nextSibling) {
                if (n.nodeType === 3 && n.nodeValue.trim().length > 40) { blocks.push(divs[d]); break; }
              }
            }
            var body = [];
            for (var k = 0; k < blocks.length; k++) {
              var a = getComputedStyle(blocks[k]).textAlign;
              if (a !== 'center' && a !== 'right' && a !== '-webkit-center' && a !== 'end') body.push(blocks[k]);
            }
            if (own) own.disabled = false;
            for (var b = 0; b < body.length; b++) body[b].classList.add('folio-p');
            for (var i = 0; i < ps.length && sampled < 40; i++) {
              if ((ps[i].textContent || '').length < 60) continue;
              sampled++;
              var cs = getComputedStyle(ps[i]);
              if (parseFloat(cs.textIndent) < 1 && parseFloat(cs.marginTop) + parseFloat(cs.marginBottom) < 4) plain++;
            }
            if (sampled > 0 && plain / sampled > 0.6) root.classList.add('folio-indent');
            // Start each chapter heading on a new page, unless it directly follows another
            // heading (e.g. "Part I" then "Chapter I") or nothing comes before it.
            var heads = document.body.querySelectorAll('h1,h2');
            var prev = null;
            for (var j = 0; j < heads.length; j++) {
              var h = heads[j];
              var range = document.createRange();
              if (prev) range.setStartAfter(prev); else range.setStart(document.body, 0);
              range.setEndBefore(h);
              var between = range.toString().replace(/\s+/g, '');
              var media = range.cloneContents().querySelector && range.cloneContents().querySelector('img,svg,image');
              if (between.length > 0 || media) h.classList.add('folio-break');
              else if (!prev) h.classList.add('folio-break-first');
              prev = h;
            }
            if (scrollMode) {
              // Room past the end of the text: scrolling into it carries on to the next chapter.
              // (Its label is drawn by CSS, so it adds no text to the chapter.)
              var spacer = document.createElement('div');
              spacer.id = 'folio-next';
              spacer.setAttribute('data-label', params.nt || '');
              spacer.className = params.nt ? 'folio-more' : 'folio-end';
              document.body.appendChild(spacer);
            }
          }
          // Pages are moved with a GPU transform rather than scrolling: smoother, and immune
          // to scroll-extent quirks at the last page.
          function setScroll(x) {
            curX = x;
            if (document.body) document.body.style.transform = 'translate3d(' + (-x) + 'px,0,0)';
          }

          function scrollToY(y, animate) {
            y = Math.max(0, y);
            window.scrollTo({ top: y, behavior: animate ? 'smooth' : 'auto' });
            if (!animate) { page = scrollPage(); report(); }
          }
          function show(p, animate) {
            p = Math.max(0, Math.min(lastPage(), p));
            if (scrollMode) { scrollToY(p * height(), animate); return; }
            page = p;
            var target = p * width();
            if (anim) { cancelAnimationFrame(anim); anim = null; }
            if (!animate) { setScroll(target); report(); return; }
            var from = curX;
            var t0 = performance.now();
            var dur = 260;
            function step(now) {
              var t = Math.min(1, (now - t0) / dur);
              var e = 1 - Math.pow(1 - t, 3);
              setScroll(from + (target - from) * e);
              if (t < 1) anim = requestAnimationFrame(step); else { anim = null; report(); }
            }
            anim = requestAnimationFrame(step);
          }

          function pageOfFragment() {
            if (!fragment) return -1;
            var el = document.getElementById(fragment) || document.getElementsByName(fragment)[0];
            if (!el) return -1;
            var left = el.getBoundingClientRect().left + curX;
            return Math.floor(left / width());
          }
          function restore() {
            prepare();
            setWidthVar();
            if (scrollMode) {
              var y = -1, el = fragment && (document.getElementById(fragment) || document.getElementsByName(fragment)[0]);
              if (el) y = el.getBoundingClientRect().top + window.scrollY - height() * 0.05;
              if (y < 0 && pageOfTarget() >= 0) y = target.getClientRects()[0].top + window.scrollY - height() * 0.3;
              if (y < 0) y = start * readMax();
              scrollToY(Math.min(y, readMax()), false);
              return;
            }
            var p = pageOfFragment();
            if (p < 0) p = pageOfTarget();
            if (p < 0) p = Math.round(start * lastPage());
            show(p, false);
          }

          // The WebView can load a chapter before it has been given a size; only lay out
          // pages (and tell the app we're ready) once the viewport is real.
          var loaded = false;
          // After the next frame is drawn, or shortly if none is: with the screen off the
          // WebView draws nothing, and read-aloud still needs the next chapter laid out.
          function nextFrame(fn) {
            var done = false;
            function once() { if (!done) { done = true; fn(); } }
            requestAnimationFrame(once);
            setTimeout(once, 100);
          }
          function hasSize() { return width() > 10 && window.innerHeight > 10; }
          function finishLoad() {
            if (ready || !loaded || !hasSize()) return;
            restore();
            nextFrame(function () {
              restore();
              fragment = '';
              target = null;
              ready = true;
              drawHighlights();
              var s = sectionInfo(), c = progressChars(s[2]);
              if (bridge) bridge.onReady(page, pageCount(), offset(), s[0], s[1], c[0], c[1]);
              if (auto.speed) startAuto();
            });
          }
          document.addEventListener('DOMContentLoaded', function () { if (hasSize()) restore(); });
          window.addEventListener('load', function () { loaded = true; finishLoad(); });
          window.addEventListener('resize', function () {
            if (!ready) { finishLoad(); return; }
            var o = offset();
            setWidthVar();
            nextFrame(function () {
              if (scrollMode) scrollToY(o * readMax(), false); else show(Math.round(o * lastPage()), false);
              drawHighlights();
            });
          });

          // Scrolling mode: keep the app posted, and carry on into the next chapter once the
          // reader scrolls past the end of this one.
          var reportTimer = null, atEnd = false;
          window.addEventListener('scroll', function () {
            if (!scrollMode || !ready || leaving) return;
            page = scrollPage();
            var end = textEnd();
            if (window.scrollY >= end - height() * 0.15 && end > 0) {
              if (!atEnd) {
                atEnd = true;
                if (bridge && bridge.nextChapter()) { leaving = true; return; }
              }
            } else {
              atEnd = false;
            }
            clearTimeout(reportTimer);
            reportTimer = setTimeout(report, 150);
          }, { passive: true });

          // Scrolling mode: auto-scroll at a steady speed, held while a finger is down.
          var auto = { speed: 0, raf: null, last: 0, acc: 0, held: false, resumeAt: 0 };
          function autoStep(t) {
            if (!auto.speed) { auto.raf = null; return; }
            var dt = auto.last ? Math.min(100, t - auto.last) : 0;
            auto.last = t;
            if (!auto.held && t >= auto.resumeAt && ready && !leaving) {
              auto.acc += auto.speed * dt / 1000;
              var whole = Math.floor(auto.acc);
              if (whole > 0) { window.scrollBy(0, whole); auto.acc -= whole; }
            }
            auto.raf = requestAnimationFrame(autoStep);
          }
          function startAuto() {
            auto.last = 0;
            if (auto.speed && !auto.raf) auto.raf = requestAnimationFrame(autoStep);
          }

          // Swipe between pages. The page follows the finger, then settles.
          var sx = 0, sy = 0, sScroll = 0, st = 0, axis = null, moved = false;
          var startY = 0, pulled = false;
          function hasSelection() { return String(window.getSelection() || '').length > 0; }
          // Scrolling mode uses the WebView's own scrolling; these only watch for a pull
          // down at the very top (back to the previous chapter) and for auto-scroll holds.
          document.addEventListener('touchstart', function (e) {
            if (!scrollMode) return;
            auto.held = true;
            sy = e.touches[0] ? e.touches[0].clientY : 0;
            startY = window.scrollY;
            pulled = false;
          }, { passive: true });
          document.addEventListener('touchmove', function (e) {
            if (!scrollMode || pulled || !e.touches[0]) return;
            if (window.scrollY <= 0 && startY <= 0 && e.touches[0].clientY - sy > height() * 0.12) {
              pulled = true;
              folio.prev();
            }
          }, { passive: true });
          document.addEventListener('touchend', function () {
            if (!scrollMode) return;
            auto.held = false;
            auto.resumeAt = performance.now() + 1200;
            if (Math.abs(window.scrollY - startY) > height() / 3) userTurned();
          });
          document.addEventListener('touchstart', function (e) {
            if (scrollMode) return;
            if (e.touches.length !== 1 || hasSelection()) { axis = 'none'; return; }
            if (anim) { cancelAnimationFrame(anim); anim = null; }
            sx = e.touches[0].clientX; sy = e.touches[0].clientY;
            sScroll = page * width(); st = Date.now(); axis = null; moved = false;
          }, { passive: true });
          document.addEventListener('touchmove', function (e) {
            if (scrollMode || axis === 'none' || e.touches.length !== 1) return;
            var dx = e.touches[0].clientX - sx, dy = e.touches[0].clientY - sy;
            if (axis === null) {
              if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
              axis = Math.abs(dx) >= Math.abs(dy) ? 'x' : 'y';
            }
            if (axis !== 'x') return;
            moved = true;
            e.preventDefault();
            var max = lastPage() * width();
            var x = sScroll - dx;
            // Resist a little past the first and last page of the chapter.
            if (x < 0) x = x / 3; else if (x > max) x = max + (x - max) / 3;
            setScroll(x);
          }, { passive: false });
          document.addEventListener('touchend', function (e) {
            if (scrollMode) return;
            if (axis !== 'x') { axis = null; return; }
            axis = null;
            var dx = (e.changedTouches[0] ? e.changedTouches[0].clientX : sx) - sx;
            var v = dx / Math.max(1, Date.now() - st);
            var threshold = width() * 0.18;
            if (dx < -threshold || v < -0.35) folio.next();
            else if (dx > threshold || v > 0.35) folio.prev();
            else show(page, true);
          });
          document.addEventListener('touchcancel', function () {
            if (scrollMode) { auto.held = false; return; }
            if (axis === 'x') show(page, true);
            axis = null;
          });

          // ---- Footnotes: show the note in a pop-up instead of jumping to it.
          var INLINE = /^(a|span|sup|sub|em|i|b|strong|small|cite|abbr)$/;
          function typeOf(el) {
            return el && el.getAttribute ? ((el.getAttribute('epub:type') || '') + ' ' + (el.getAttribute('role') || '')).toLowerCase() : '';
          }
          function looksLikeNoteRef(a, target) {
            if (/noteref/.test(typeOf(a))) return true;
            if (/footnote|endnote|rearnote|doc-note|\bnote\b/.test(typeOf(target))) return true;
            var txt = a.textContent.replace(/\s+/g, '');
            if (!txt || txt.length > 6) return false;
            if (a.closest('sup') || a.querySelector('sup')) return true;
            return /^[\[\(]?(\d{1,3}|[*\u2020\u2021\u00a7\u00b6]+|[a-z]|[ivx]{1,5})[\]\)]?$/i.test(txt);
          }
          function noteText(el) {
            // A note's target is often an empty anchor at its start: take the block around it.
            var n = el;
            while (n && n.parentNode && n.nodeName && INLINE.test(n.nodeName.toLowerCase()) &&
                   n.parentNode.nodeName.toLowerCase() !== 'body') n = n.parentNode;
            var text = (n.textContent || '').replace(/\s+/g, ' ').trim();
            return text.length > 3000 ? text.slice(0, 3000) + '\u2026' : text;
          }
          function findIn(doc, id) {
            return id ? (doc.getElementById(id) || doc.getElementsByName(id)[0] || null) : null;
          }
          function openFootnote(a, e) {
            if (!bridge || !bridge.onFootnote) return false;
            var url;
            try { url = new URL(a.href); } catch (err) { return false; }
            if (url.host !== location.host || !url.hash) return false;
            var id = decodeURIComponent(url.hash.slice(1));
            if (url.pathname === location.pathname) {
              var target = findIn(document, id);
              if (!target || !looksLikeNoteRef(a, target)) return false;
              var text = noteText(target);
              if (!text) return false;
              e.preventDefault();
              bridge.onFootnote(text, a.href);
              return true;
            }
            // In another file of the book: only for links that look like note markers.
            if (!looksLikeNoteRef(a, null)) return false;
            e.preventDefault();
            fetch(url.origin + url.pathname).then(function (r) { return r.text(); }).then(function (html) {
              var doc = new DOMParser().parseFromString(html, 'text/html');
              var target = findIn(doc, id), text = target && noteText(target);
              if (text) bridge.onFootnote(text, a.href); else location.href = a.href;
            }).catch(function () { location.href = a.href; });
            return true;
          }

          document.addEventListener('click', function (e) {
            if (moved) { moved = false; return; }
            for (var t = e.target; t && t !== document; t = t.parentNode) {
              if (t.tagName && t.tagName.toLowerCase() === 'a' && t.getAttribute('href')) {
                openFootnote(t, e);
                return;
              }
            }
            if (hasSelection()) return;
            if (found) { found = null; paint('folio-find', []); }
            var hit = markAt(e.clientX, e.clientY);
            if (hit && bridge && bridge.onHighlightTap) { bridge.onHighlightTap(hit); return; }
            var x = e.clientX / width();
            if (bridge) bridge.onTap(x < 0.3 ? -1 : (x > 0.7 ? 1 : 0));
          });

          // ---- Text on the page, for bookmarks and read-aloud.
          var blockCache = new WeakMap();
          function isBlock(el) {
            var v = blockCache.get(el);
            if (v === undefined) {
              var d = getComputedStyle(el).display;
              v = !(d === 'inline' || d === 'contents' || d.indexOf('inline') === 0 || d === 'ruby' || d.indexOf('ruby') === 0);
              blockCache.set(el, v);
            }
            return v;
          }
          function blockOf(n) {
            for (var e = n.parentNode; e && e !== document.body; e = e.parentNode) if (e.nodeType === 1 && isBlock(e)) return e;
            return document.body;
          }
          function textWalker(from) {
            var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
              acceptNode: function (n) {
                var t = n.parentNode ? n.parentNode.nodeName.toLowerCase() : '';
                return (t === 'script' || t === 'style' || t === 'rt' || t === 'title') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
              }
            });
            if (from) w.currentNode = from;
            return w;
          }
          // The first word of text on the page being shown. (Found from layout boxes rather
          // than hit-testing, which ignores the page transform in some WebViews.)
          function pageOfRect(r) {
            if (scrollMode) return Math.floor((r.top + window.scrollY + 1) / Math.max(1, height()));
            return Math.floor((r.left + curX + 1) / width());
          }
          // Whether a box is on an earlier page (paged) or above the screen (scrolling).
          function before(r) { return scrollMode ? r.bottom <= 1 : pageOfRect(r) < page; }
          function charBefore(n, o) {
            var rg = document.createRange();
            rg.setStart(n, o); rg.setEnd(n, Math.min(o + 1, n.nodeValue.length));
            var rs = rg.getClientRects();
            return rs.length ? before(rs[0]) : true;
          }
          function nodeRects(n) {
            var rg = document.createRange();
            rg.selectNodeContents(n);
            return rg.getClientRects();
          }
          function textStart() {
            // Text runs in reading order, so binary-search for the first node that reaches
            // this page, then look at nodes one by one from there.
            var list = buildIndex().list, lo = 0, hi = list.length;
            while (lo < hi) {
              var mid = (lo + hi) >> 1, k = mid, rs = null;
              for (; k < hi; k++) {
                if (!list[k].node.nodeValue.trim()) continue;
                rs = nodeRects(list[k].node);
                if (rs.length) break;
              }
              if (k >= hi) { hi = mid; continue; }
              if (before(rs[rs.length - 1])) lo = k + 1; else hi = mid;
            }
            for (var i = lo; i < list.length; i++) {
              var n = list[i].node, v = n.nodeValue;
              if (!v.trim()) continue;
              var rects = nodeRects(n);
              if (!rects.length || before(rects[rects.length - 1])) continue;
              var o = 0;
              if (before(rects[0])) {
                // This node starts on an earlier page: find its first character on this one.
                var a = 0, b = v.length - 1;
                while (a < b) {
                  var m = (a + b) >> 1;
                  if (!charBefore(n, m)) b = m; else a = m + 1;
                }
                o = a;
                while (o > 0 && !/\s/.test(v.charAt(o - 1))) o--;
                if (charBefore(n, o)) { while (o < v.length && !/\s/.test(v.charAt(o))) o++; while (o < v.length && /\s/.test(v.charAt(o))) o++; }
              }
              return { node: n, offset: o };
            }
            return null;
          }
          // Splits the text from a starting point to the end of the file into sentences,
          // remembering which text nodes each one came from so spoken words can be found again.
          var ABBREV = /(^|[\s(])([A-Z]|Mr|Mrs|Ms|Dr|St|Mt|Jr|Sr|Prof|Rev|Capt|Col|Gen|Lt|Sgt|No|vs|etc|viz|cf)$/;
          function buildChunks(start) {
            var chunks = [], cur = null, block = null, n = start.node, first = true;
            var w = textWalker(start.node);
            function flush() { if (cur && cur.text.trim()) chunks.push(cur); cur = null; }
            while (n) {
              var b = blockOf(n);
              if (b !== block) { flush(); block = b; }
              else if (n.previousSibling && n.previousSibling.nodeName.toLowerCase() === 'br') flush();
              var v = n.nodeValue, i = first ? start.offset : 0;
              first = false;
              while (i < v.length) {
                if (!cur) cur = { text: '', segs: [] };
                var re = /[.!?\u2026]+["'\u201D\u2019)\]]*(\s+|$)/g;
                re.lastIndex = i;
                var m;
                // Not a sentence end: a full stop after an initial or a title ("S. Place",
                // "Mr. Smith"), or one followed by a lower-case word. Splitting there would
                // make the voice pause and drop its pitch mid-sentence.
                while ((m = re.exec(v)) && m[0].charAt(0) === '.' && m[0].length > 1 &&
                       (ABBREV.test(v.slice(Math.max(0, m.index - 6), m.index)) || /^[a-z]/.test(v.slice(m.index + m[0].length)))) {}
                var end = m ? m.index + m[0].length : v.length;
                cur.segs.push({ node: n, start: i, end: end, at: cur.text.length });
                cur.text += v.slice(i, end);
                i = end;
                if (m || cur.text.length > 600) flush();
              }
              n = w.nextNode();
            }
            flush();
            return chunks;
          }
          function rangeIn(chunk, s, e) {
            var r = document.createRange(), started = false;
            for (var k = 0; k < chunk.segs.length; k++) {
              var g = chunk.segs[k], len = g.end - g.start;
              if (!started && s < g.at + len) { r.setStart(g.node, g.start + Math.max(0, s - g.at)); started = true; }
              if (started && e <= g.at + len) { r.setEnd(g.node, g.start + Math.max(0, e - g.at)); return r; }
            }
            if (!started) return null;
            var last = chunk.segs[chunk.segs.length - 1];
            r.setEnd(last.node, last.end);
            return r;
          }
          var tts = null;
          var marks = {};
          function highlight(name, range) { paint(name, range ? [range] : []); }
          // The boxes of the text in a range (a range's own rects also include whole elements
          // inside it, which would double up).
          function textRects(range) {
            var out = [], root = range.commonAncestorContainer;
            if (root.nodeType === 3) return Array.prototype.slice.call(range.getClientRects());
            var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), n, r = document.createRange();
            while ((n = w.nextNode())) {
              if (!range.intersectsNode(n)) continue;
              r.selectNodeContents(n);
              if (n === range.startContainer) r.setStart(n, range.startOffset);
              if (n === range.endContainer) r.setEnd(n, range.endOffset);
              var rs = r.getClientRects();
              for (var k = 0; k < rs.length; k++) out.push(rs[k]);
            }
            return out;
          }
          function paint(name, ranges) {
            if (window.CSS && CSS.highlights && window.Highlight) {
              if (!ranges.length) { CSS.highlights.delete(name); return; }
              var h = new Highlight();
              ranges.forEach(function (r) { h.add(r); });
              CSS.highlights.set(name, h);
              return;
            }
            // Older WebViews (before Chrome 105) lack CSS highlights: draw boxes behind the text instead.
            var box = marks[name];
            if (!box) {
              box = document.createElement('div');
              box.className = 'folio-mark ' + name;
              document.body.appendChild(box);
              marks[name] = box;
            }
            box.innerHTML = '';
            var b = document.body.getBoundingClientRect();
            ranges.forEach(function (range) {
              var rs = textRects(range);
              for (var k = 0; k < rs.length; k++) {
                var d = document.createElement('div');
                d.style.left = (rs[k].left - b.left) + 'px';
                d.style.top = (rs[k].top - b.top) + 'px';
                d.style.width = rs[k].width + 'px';
                d.style.height = rs[k].height + 'px';
                box.appendChild(d);
              }
            });
          }

          // ---- Positions in the chapter's text, as character offsets into all its text
          // nodes in order. Highlights are stored this way, and search results opened by them.
          var textIndex = null;
          function buildIndex() {
            if (textIndex) return textIndex;
            var w = textWalker(null), n, at = 0, list = [], atOf = new Map();
            while ((n = w.nextNode())) { list.push({ node: n, at: at }); atOf.set(n, at); at += n.nodeValue.length; }
            textIndex = { list: list, atOf: atOf, total: at };
            return textIndex;
          }
          function offsetOf(c, o) {
            var idx = buildIndex();
            if (c.nodeType === 3 && idx.atOf.has(c)) return idx.atOf.get(c) + Math.min(o, c.nodeValue.length);
            var r = document.createRange();
            r.setStart(c, o);
            for (var i = 0; i < idx.list.length; i++) if (r.comparePoint(idx.list[i].node, 0) >= 0) return idx.list[i].at;
            return idx.total;
          }
          function locate(x) {
            var list = buildIndex().list, lo = 0, hi = list.length - 1;
            if (!list.length) return null;
            while (lo < hi) { var mid = (lo + hi + 1) >> 1; if (list[mid].at <= x) lo = mid; else hi = mid - 1; }
            var it = list[lo];
            return { node: it.node, offset: Math.max(0, Math.min(x - it.at, it.node.nodeValue.length)) };
          }
          function rangeAt(s, e) {
            var a = locate(s), b = locate(e);
            if (!a || !b) return null;
            var r = document.createRange();
            r.setStart(a.node, a.offset);
            r.setEnd(b.node, b.offset);
            return r;
          }
          function fullText() {
            return buildIndex().list.map(function (it) { return it.node.nodeValue; }).join('');
          }
          // The n-th match of a search pattern (built by the app, so it counts matches the same way).
          function findNth(pattern, n) {
            var re;
            try { re = new RegExp(pattern, 'gi'); } catch (err) { return null; }
            var text = fullText(), m, i = 0;
            while ((m = re.exec(text))) {
              if (!m[0].length) { re.lastIndex++; continue; }
              if (i === n) return rangeAt(m.index, m.index + m[0].length);
              i++;
            }
            return null;
          }
          var found = null;
          function pageOfTarget() {
            if (target === null) {
              target = false;
              if (params.q) { found = findNth(params.q, parseInt(params.n || '0', 10) || 0); target = found || false; }
              else if (params.c) { var at = parseInt(params.c, 10); if (at >= 0) target = rangeAt(at, at + 1) || false; }
            }
            if (!target) return -1;
            var rs = target.getClientRects();
            return rs.length ? pageOfRect(rs[0]) : -1;
          }

          // ---- The reader's highlights.
          var COLORS = ['yellow', 'green', 'blue', 'pink'];
          var userMarks = [];
          function drawHighlights() {
            var by = {};
            COLORS.forEach(function (c) { by[c] = []; });
            userMarks.forEach(function (h) {
              if (h.r === undefined) h.r = rangeAt(h.s, h.e);
              if (h.r) (by[h.c] || by.yellow).push(h.r);
            });
            COLORS.forEach(function (c) { paint('folio-hl-' + c, by[c]); });
            paint('folio-find', found ? [found] : []);
          }
          function markAt(x, y) {
            for (var i = userMarks.length - 1; i >= 0; i--) {
              var r = userMarks[i].r;
              if (!r) continue;
              var rs = r.getClientRects();
              for (var k = 0; k < rs.length; k++) {
                if (x >= rs[k].left && x <= rs[k].right && y >= rs[k].top - 2 && y <= rs[k].bottom + 2) return userMarks[i].id;
              }
            }
            return null;
          }
          function userTurned() { if (tts && bridge && bridge.onManualTurn) bridge.onManualTurn(); }

          var folio = window.folio = {
            next: function () {
              if (leaving) return;
              if (scrollMode) {
                if (window.scrollY >= readMax() - 2) { if (bridge && bridge.nextChapter()) leaving = true; }
                else { scrollToY(window.scrollY + height() * 0.9, true); userTurned(); }
                return;
              }
              if (page >= lastPage()) { if (anim) { cancelAnimationFrame(anim); anim = null; } setScroll(page * width()); if (bridge && bridge.nextChapter()) leaving = true; }
              else { show(page + 1, true); userTurned(); }
            },
            prev: function () {
              if (leaving) return;
              if (scrollMode) {
                if (window.scrollY <= 2) { if (bridge && bridge.prevChapter()) leaving = true; }
                else { scrollToY(window.scrollY - height() * 0.9, true); userTurned(); }
                return;
              }
              if (page <= 0) { if (anim) { cancelAnimationFrame(anim); anim = null; } setScroll(0); if (bridge && bridge.prevChapter()) leaving = true; }
              else { show(page - 1, true); userTurned(); }
            },
            // A short excerpt from the top of the page, to label a bookmark.
            snippet: function () {
              var st = textStart();
              if (!st) return '';
              var out = '', w = textWalker(st.node), n = st.node, o = st.offset;
              while (n && out.length < 200) { out += n.nodeValue.slice(o) + ' '; o = 0; n = w.nextNode(); }
              out = out.replace(/\s+/g, ' ').trim();
              return out.length > 140 ? out.slice(0, 140).replace(/\s+\S*$/, '') + '\u2026' : out;
            },
            // Read-aloud: prepares sentences from the top of this page and returns their text.
            ttsStart: function () {
              if (!ready || leaving) return null; // not laid out yet; the app retries once it is
              var st = textStart();
              tts = st ? buildChunks(st) : [];
              return tts.map(function (c) { return c.text; });
            },
            // Highlights sentence i (and the word from s to e in it), turning the page to follow.
            ttsMark: function (i, s, e) {
              var c = tts && tts[i];
              if (!c) return;
              var sentence = rangeIn(c, 0, c.text.length);
              var word = s >= 0 && e > s ? rangeIn(c, s, e) : null;
              highlight('folio-sentence', sentence);
              highlight('folio-word', word);
              var target = word || sentence;
              var rects = target ? target.getClientRects() : [];
              if (rects.length && !anim && !leaving) {
                if (scrollMode) {
                  // Keep the spoken line in the upper part of the screen, gliding rather than jumping.
                  var r = rects[0], h = height();
                  if (r.top < h * 0.12 || r.bottom > h * 0.7) window.scrollTo({ top: Math.max(0, window.scrollY + r.top - h * 0.3), behavior: 'smooth' });
                  return;
                }
                var p = Math.floor((rects[0].left + curX + 1) / width());
                if (p !== page) show(p, true);
              }
            },
            // The selected text and where it is, for highlighting; null if nothing is selected.
            selection: function () {
              var sel = window.getSelection();
              if (!sel || !sel.rangeCount || sel.isCollapsed) return null;
              var r = sel.getRangeAt(0);
              var s = offsetOf(r.startContainer, r.startOffset), e = offsetOf(r.endContainer, r.endOffset);
              var text = String(sel).replace(/\s+/g, ' ').trim();
              if (e <= s || !text) return null;
              return { s: s, e: e, text: text.length > 1000 ? text.slice(0, 1000) + '\u2026' : text };
            },
            clearSelection: function () {
              var sel = window.getSelection();
              if (sel) sel.removeAllRanges();
            },
            // [{id, s, e, c}] for this chapter; replaces whatever was drawn before.
            setHighlights: function (list) {
              userMarks = (list || []).map(function (h) { return { id: h.id, s: h.s, e: h.e, c: h.c }; });
              if (ready) drawHighlights();
            },
            ttsStop: function () {
              tts = null;
              highlight('folio-sentence', null);
              highlight('folio-word', null);
            },
            setStyle: function (css) {
              var o = offset();
              var el = document.getElementById('folio-style');
              if (!el) {
                el = document.createElement('style');
                el.id = 'folio-style';
                (document.head || root).appendChild(el);
              }
              el.textContent = css;
              folio.restoreTo(o);
            },
            // Scrolling mode: scroll on by itself at this many pixels a second (0 stops).
            autoScroll: function (pxPerSec) {
              auto.speed = scrollMode ? Math.max(0, pxPerSec || 0) : 0;
              auto.acc = 0;
              if (auto.speed) startAuto();
            },
            restoreTo: function (o) {
              setWidthVar();
              if (scrollMode) {
                nextFrame(function () {
                  scrollToY(o * readMax(), false);
                  setTimeout(function () { scrollToY(o * readMax(), false); drawHighlights(); }, 80);
                });
                return;
              }
              nextFrame(function () {
                show(Math.round(o * lastPage()), false);
                setTimeout(function () { show(Math.round(o * lastPage()), false); drawHighlights(); }, 80);
              });
            }
          };
        })();
    """.trimIndent()
}

/** Calls from the page's JavaScript; these arrive on a WebView background thread. */
class ReaderBridge(
    /**
     * [section] indexes the chapter's section ids (-1 before the first); [sectionEnd] is its last page.
     * [charsLeft] counts the characters from the top of the screen to the end of the section, and
     * [charAt] is where the screen starts, as a character offset into the chapter.
     */
    private val pageChanged: (PageInfo) -> Unit,
    private val loaded: (PageInfo) -> Unit,
    private val tapped: (zone: Int) -> Unit,
    /** Return whether there is a chapter to move to. */
    private val nextRequested: () -> Boolean,
    private val prevRequested: () -> Boolean,
    /** The reader turned the page themselves (only reported while reading aloud). */
    private val manualTurn: () -> Unit = {},
    /** The reader tapped one of their highlights. */
    private val highlightTapped: (id: String) -> Unit = {},
    /** The reader tapped a footnote marker: the note's text, and the link to it. */
    private val footnoteTapped: (text: String, href: String) -> Unit = { _, _ -> },
) {
    @JavascriptInterface fun onPage(page: Int, count: Int, offset: Float, section: Int, sectionEnd: Int, charsLeft: Int, charAt: Int) =
        pageChanged(PageInfo(page, count, offset, section, sectionEnd, charsLeft, charAt))
    @JavascriptInterface fun onReady(page: Int, count: Int, offset: Float, section: Int, sectionEnd: Int, charsLeft: Int, charAt: Int) =
        loaded(PageInfo(page, count, offset, section, sectionEnd, charsLeft, charAt))
    @JavascriptInterface fun onTap(zone: Int) = tapped(zone)
    @JavascriptInterface fun nextChapter(): Boolean = nextRequested()
    @JavascriptInterface fun prevChapter(): Boolean = prevRequested()
    @JavascriptInterface fun onManualTurn() = manualTurn()
    @JavascriptInterface fun onHighlightTap(id: String) = highlightTapped(id)
    @JavascriptInterface fun onFootnote(text: String, href: String) = footnoteTapped(text, href)
}

/** Where the reader is in a chapter, as the page script reports it. */
data class PageInfo(
    val page: Int,
    val count: Int,
    val offset: Float,
    val section: Int,
    val sectionEnd: Int,
    val charsLeft: Int,
    val charAt: Int,
)
