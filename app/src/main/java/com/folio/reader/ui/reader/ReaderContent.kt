package com.folio.reader.ui.reader

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
 * Chapters get the reader stylesheet and script injected; everything off-book is blocked
 * so books can't phone home.
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
        url: Uri,
        cssFor: () -> String,
        startFor: (path: String) -> Pair<Float, String?>,
    ): WebResourceResponse {
        val path = pathFor(url) ?: return blocked()
        if (path == INTERNAL + "reader.js") {
            return WebResourceResponse("text/javascript", "UTF-8", ByteArrayInputStream(SCRIPT.toByteArray()))
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
        val tags = "<style id=\"folio-style\">$css</style>" +
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

    fun css(settings: ReaderSettings, theme: ReaderTheme): String {
        val bg = hex(theme.background)
        val fg = hex(theme.text)
        val link = hex(theme.link)
        val m = settings.margin
        val lh = String.format(Locale.US, "%.2f", settings.lineHeight)
        return buildString {
            append("html,body{background:$bg !important;color:$fg !important;}")
            append("body{margin:0 !important;padding:28px ${m}px 120px ${m}px !important;max-width:none !important;")
            append("line-height:$lh !important;overflow-wrap:break-word;-webkit-hyphens:auto;hyphens:auto;}")
            append("body *{color:inherit !important;background-color:transparent !important;border-color:${fg}33 !important;}")
            append("body p,body li,body blockquote,body div,body span,body dd,body dt,body td{line-height:inherit !important;}")
            append("a,a *{color:$link !important;text-decoration:none !important;}")
            append("img,video{max-width:100% !important;height:auto !important;}")
            append("svg{max-width:100% !important;max-height:94vh !important;}")
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
          var holding = true;
          var bridge = window.FolioBridge;

          function maxScroll() {
            return Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
          }
          function offset() {
            var m = maxScroll();
            return m <= 0 ? 1 : Math.min(1, window.scrollY / m);
          }
          function scrollToOffset(o) { window.scrollTo(0, Math.round(o * maxScroll())); }
          function restore() {
            if (fragment) {
              var el = document.getElementById(fragment) || document.getElementsByName(fragment)[0];
              if (el) { el.scrollIntoView(); return; }
            }
            scrollToOffset(start);
          }

          var pending = null;
          window.addEventListener('scroll', function () {
            if (holding || pending) return;
            pending = setTimeout(function () {
              pending = null;
              if (bridge) bridge.onScroll(offset());
            }, 120);
          }, { passive: true });

          document.addEventListener('DOMContentLoaded', restore);
          window.addEventListener('load', function () {
            restore();
            requestAnimationFrame(function () {
              restore();
              holding = false;
              fragment = '';
              if (bridge) bridge.onReady(offset());
            });
          });

          document.addEventListener('click', function (e) {
            for (var t = e.target; t && t !== document; t = t.parentNode) {
              if (t.tagName && t.tagName.toLowerCase() === 'a' && t.getAttribute('href')) return;
            }
            if (String(window.getSelection() || '').length > 0) return;
            var x = e.clientX / window.innerWidth;
            if (x < 0.28) folio.pagePrev();
            else if (x > 0.72) folio.pageNext();
            else if (bridge) bridge.onTap();
          });

          var folio = window.folio = {
            pageNext: function () {
              if (window.scrollY >= maxScroll() - 4) { if (bridge) bridge.nextChapter(); }
              else window.scrollBy({ top: window.innerHeight * 0.88, behavior: 'smooth' });
            },
            pagePrev: function () {
              if (window.scrollY <= 4) { if (bridge) bridge.prevChapter(); }
              else window.scrollBy({ top: -window.innerHeight * 0.88, behavior: 'smooth' });
            },
            setStyle: function (css) {
              var o = offset();
              var el = document.getElementById('folio-style');
              if (!el) {
                el = document.createElement('style');
                el.id = 'folio-style';
                (document.head || document.documentElement).appendChild(el);
              }
              el.textContent = css;
              folio.restoreTo(o);
            },
            restoreTo: function (o) {
              holding = true;
              requestAnimationFrame(function () {
                scrollToOffset(o);
                setTimeout(function () { scrollToOffset(o); holding = false; }, 60);
              });
            },
            offset: offset
          };
        })();
    """.trimIndent()
}

/** Calls from the page's JavaScript; these arrive on a WebView background thread. */
class ReaderBridge(
    private val scroll: (Float) -> Unit,
    private val ready: (Float) -> Unit,
    private val tap: () -> Unit,
    private val next: () -> Unit,
    private val prev: () -> Unit,
) {
    @JavascriptInterface fun onScroll(offset: Float) = scroll(offset)
    @JavascriptInterface fun onReady(offset: Float) = ready(offset)
    @JavascriptInterface fun onTap() = tap()
    @JavascriptInterface fun nextChapter() = next()
    @JavascriptInterface fun prevChapter() = prev()
}
