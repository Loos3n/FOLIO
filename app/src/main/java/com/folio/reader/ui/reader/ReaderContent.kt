package com.folio.reader.ui.reader

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
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
        startFor: (path: String) -> Pair<Float, String?>,
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
            val (offset, fragment) = startFor(path)
            val html = inject(String(bytes, Charsets.UTF_8), cssFor(), offset, fragment)
            return WebResourceResponse(mediaType, "UTF-8", ByteArrayInputStream(html.toByteArray()))
        }
        val stream = book.open(path) ?: return notFound()
        val encoding = if (mediaType.startsWith("text/")) "UTF-8" else null
        return WebResourceResponse(mediaType, encoding, stream)
    }

    fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun inject(html: String, css: String, offset: Float, fragment: String?): String {
        val query = buildString {
            append("o=").append(String.format(Locale.US, "%.5f", offset))
            if (fragment != null) append("&amp;f=").append(Uri.encode(fragment))
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

    fun css(settings: ReaderSettings, theme: ReaderTheme): String {
        val bg = hex(theme.background)
        val fg = hex(theme.text)
        val link = hex(theme.link)
        val m = settings.margin
        val lh = String.format(Locale.US, "%.2f", settings.lineHeight)
        return buildString {
            append(FONT_FACES)
            // Paged layout: the body flows into columns exactly one screen wide, and the
            // script moves between them. --folio-w is set by the script to the real viewport width.
            append("html{margin:0 !important;padding:0 ${m}px !important;height:100vh !important;width:auto !important;")
            append("max-width:none !important;box-sizing:border-box !important;overflow:hidden !important;")
            append("background:$bg !important;color:$fg !important;-webkit-text-size-adjust:none;}")
            append("body{margin:0 !important;padding:0 !important;height:100vh !important;width:auto !important;")
            append("max-width:none !important;box-sizing:border-box !important;background:$bg !important;color:$fg !important;")
            append("-webkit-column-width:calc(var(--folio-w,100vw) - ${2 * m}px) !important;column-width:calc(var(--folio-w,100vw) - ${2 * m}px) !important;")
            append("-webkit-column-gap:${2 * m}px !important;column-gap:${2 * m}px !important;column-fill:auto !important;")
            append("line-height:$lh !important;overflow-wrap:break-word;-webkit-hyphens:auto;hyphens:auto;")
            append("-webkit-tap-highlight-color:transparent;will-change:transform;}")
            append("body *{color:inherit !important;background-color:transparent !important;border-color:${fg}33 !important;}")
            append("body p,body li,body blockquote,body div,body span,body dd,body dt,body td{line-height:inherit !important;}")
            append("a,a *{color:$link !important;text-decoration:none !important;}")
            append("img,video,svg{max-width:100% !important;max-height:calc(100vh - 8px) !important;")
            append("object-fit:contain;break-inside:avoid;-webkit-column-break-inside:avoid;}")
            append("img,video{height:auto !important;}")
            append("h1,h2,h3,h4{break-after:avoid;-webkit-column-break-after:avoid;line-height:1.25 !important;}")
            append("pre{white-space:pre-wrap !important;}")
            append("::selection{background:${link}55;}")
            if (settings.justify) append("body p{text-align:justify !important;}")
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
          var bridge = window.FolioBridge;
          var root = document.documentElement;
          var page = 0;
          var ready = false;
          var anim = null;
          var curX = 0;

          function width() { return window.innerWidth || root.clientWidth; }
          function setWidthVar() { root.style.setProperty('--folio-w', width() + 'px'); }
          function pageCount() {
            var body = document.body;
            if (!body) return 1;
            return Math.max(1, Math.round((body.scrollWidth + 2 * parseFloat(getComputedStyle(root).paddingLeft || '0')) / width()));
          }
          function lastPage() { return pageCount() - 1; }
          function offset() {
            var n = pageCount();
            return n <= 1 ? 1 : page / (n - 1);
          }
          var leaving = false; // set once we've asked for another chapter; this page is done reporting
          function report() { if (bridge && ready && !leaving) bridge.onPage(page, pageCount(), offset()); }
          // Pages are moved with a GPU transform rather than scrolling: smoother, and immune
          // to scroll-extent quirks at the last page.
          function setScroll(x) {
            curX = x;
            if (document.body) document.body.style.transform = 'translate3d(' + (-x) + 'px,0,0)';
          }

          function show(p, animate) {
            p = Math.max(0, Math.min(lastPage(), p));
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
            setWidthVar();
            var p = pageOfFragment();
            if (p < 0) p = Math.round(start * lastPage());
            show(p, false);
          }

          document.addEventListener('DOMContentLoaded', restore);
          window.addEventListener('load', function () {
            restore();
            requestAnimationFrame(function () {
              restore();
              fragment = '';
              ready = true;
              if (bridge) bridge.onReady(page, pageCount(), offset());
            });
          });
          window.addEventListener('resize', function () {
            var o = offset();
            setWidthVar();
            requestAnimationFrame(function () { show(Math.round(o * lastPage()), false); });
          });

          // Swipe between pages. The page follows the finger, then settles.
          var sx = 0, sy = 0, sScroll = 0, st = 0, axis = null, moved = false;
          function hasSelection() { return String(window.getSelection() || '').length > 0; }
          document.addEventListener('touchstart', function (e) {
            if (e.touches.length !== 1 || hasSelection()) { axis = 'none'; return; }
            if (anim) { cancelAnimationFrame(anim); anim = null; }
            sx = e.touches[0].clientX; sy = e.touches[0].clientY;
            sScroll = page * width(); st = Date.now(); axis = null; moved = false;
          }, { passive: true });
          document.addEventListener('touchmove', function (e) {
            if (axis === 'none' || e.touches.length !== 1) return;
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
            if (axis !== 'x') { axis = null; return; }
            axis = null;
            var dx = (e.changedTouches[0] ? e.changedTouches[0].clientX : sx) - sx;
            var v = dx / Math.max(1, Date.now() - st);
            var threshold = width() * 0.18;
            if (dx < -threshold || v < -0.35) folio.next();
            else if (dx > threshold || v > 0.35) folio.prev();
            else show(page, true);
          });
          document.addEventListener('touchcancel', function () { if (axis === 'x') show(page, true); axis = null; });

          document.addEventListener('click', function (e) {
            if (moved) { moved = false; return; }
            for (var t = e.target; t && t !== document; t = t.parentNode) {
              if (t.tagName && t.tagName.toLowerCase() === 'a' && t.getAttribute('href')) return;
            }
            if (hasSelection()) return;
            var x = e.clientX / width();
            if (bridge) bridge.onTap(x < 0.3 ? -1 : (x > 0.7 ? 1 : 0));
          });

          var folio = window.folio = {
            next: function () {
              if (leaving) return;
              if (page >= lastPage()) { if (anim) { cancelAnimationFrame(anim); anim = null; } setScroll(page * width()); if (bridge && bridge.nextChapter()) leaving = true; }
              else show(page + 1, true);
            },
            prev: function () {
              if (leaving) return;
              if (page <= 0) { if (anim) { cancelAnimationFrame(anim); anim = null; } setScroll(0); if (bridge && bridge.prevChapter()) leaving = true; }
              else show(page - 1, true);
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
            restoreTo: function (o) {
              setWidthVar();
              requestAnimationFrame(function () {
                show(Math.round(o * lastPage()), false);
                setTimeout(function () { show(Math.round(o * lastPage()), false); }, 80);
              });
            }
          };
        })();
    """.trimIndent()
}

/** Calls from the page's JavaScript; these arrive on a WebView background thread. */
class ReaderBridge(
    private val pageChanged: (page: Int, count: Int, offset: Float) -> Unit,
    private val loaded: (page: Int, count: Int, offset: Float) -> Unit,
    private val tapped: (zone: Int) -> Unit,
    /** Return whether there is a chapter to move to. */
    private val nextRequested: () -> Boolean,
    private val prevRequested: () -> Boolean,
) {
    @JavascriptInterface fun onPage(page: Int, count: Int, offset: Float) = pageChanged(page, count, offset)
    @JavascriptInterface fun onReady(page: Int, count: Int, offset: Float) = loaded(page, count, offset)
    @JavascriptInterface fun onTap(zone: Int) = tapped(zone)
    @JavascriptInterface fun nextChapter(): Boolean = nextRequested()
    @JavascriptInterface fun prevChapter(): Boolean = prevRequested()
}
