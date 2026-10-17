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
            append("background:$bg !important;color:$fg !important;}")
            append("body{margin:0 !important;padding:0 !important;height:100vh !important;width:auto !important;")
            append("max-width:none !important;box-sizing:border-box !important;background:$bg !important;color:$fg !important;")
            append("-webkit-column-width:calc(var(--folio-w,100vw) - ${2 * m}px) !important;column-width:calc(var(--folio-w,100vw) - ${2 * m}px) !important;")
            append("-webkit-column-gap:${2 * m}px !important;column-gap:${2 * m}px !important;column-fill:auto !important;")
            append("line-height:$lh !important;overflow-wrap:break-word;-webkit-hyphens:auto;hyphens:auto;")
            append("-webkit-tap-highlight-color:transparent;will-change:transform;}")
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
          var bridge = window.FolioBridge;
          var root = document.documentElement;
          var page = 0;
          var ready = false;
          var anim = null;
          var curX = 0;

          function width() { return window.innerWidth || root.clientWidth || 0; }
          function setWidthVar() { root.style.setProperty('--folio-w', width() + 'px'); }
          function pageCount() {
            var body = document.body;
            if (!body || width() < 10) return 1;
            return Math.max(1, Math.round((body.scrollWidth + 2 * parseFloat(getComputedStyle(root).paddingLeft || '0')) / width()));
          }
          function lastPage() { return pageCount() - 1; }
          function offset() {
            var n = pageCount();
            return n <= 1 ? 1 : page / (n - 1);
          }
          var leaving = false; // set once we've asked for another chapter; this page is done reporting
          // Which table-of-contents section the current page is in, and its last page.
          function pageOfElement(el) {
            return Math.max(0, Math.floor((el.getBoundingClientRect().left + curX + 1) / width()));
          }
          function sectionInfo() {
            var current = -1, end = lastPage();
            for (var i = 0; i < sections.length; i++) {
              var id = sections[i];
              var el = id ? (document.getElementById(id) || document.getElementsByName(id)[0]) : null;
              var p = id ? (el ? pageOfElement(el) : -1) : 0;
              if (p < 0) continue;
              if (p <= page) current = i;
              else { end = Math.min(end, p - 1); break; }
            }
            return [current, end];
          }
          function report() {
            if (!bridge || !ready || leaving) return;
            var s = sectionInfo();
            bridge.onPage(page, pageCount(), offset(), s[0], s[1]);
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
          }
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
            prepare();
            setWidthVar();
            var p = pageOfFragment();
            if (p < 0) p = Math.round(start * lastPage());
            show(p, false);
          }

          // The WebView can load a chapter before it has been given a size; only lay out
          // pages (and tell the app we're ready) once the viewport is real.
          var loaded = false;
          function hasSize() { return width() > 10 && window.innerHeight > 10; }
          function finishLoad() {
            if (ready || !loaded || !hasSize()) return;
            restore();
            requestAnimationFrame(function () {
              restore();
              fragment = '';
              ready = true;
              var s = sectionInfo();
              if (bridge) bridge.onReady(page, pageCount(), offset(), s[0], s[1]);
            });
          }
          document.addEventListener('DOMContentLoaded', function () { if (hasSize()) restore(); });
          window.addEventListener('load', function () { loaded = true; finishLoad(); });
          window.addEventListener('resize', function () {
            if (!ready) { finishLoad(); return; }
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
          function pageOfRect(r) { return Math.floor((r.left + curX + 1) / width()); }
          function charPage(n, o) {
            var rg = document.createRange();
            rg.setStart(n, o); rg.setEnd(n, Math.min(o + 1, n.nodeValue.length));
            var rs = rg.getClientRects();
            return rs.length ? pageOfRect(rs[0]) : -1;
          }
          function textStart() {
            var w = textWalker(null), n, rg = document.createRange();
            while ((n = w.nextNode())) {
              var v = n.nodeValue;
              if (!v.trim()) continue;
              rg.selectNodeContents(n);
              var rects = rg.getClientRects();
              if (!rects.length || pageOfRect(rects[rects.length - 1]) < page) continue;
              var o = 0;
              if (pageOfRect(rects[0]) < page) {
                // This node starts on an earlier page: find its first character on this one.
                var lo = 0, hi = v.length - 1;
                while (lo < hi) {
                  var mid = (lo + hi) >> 1, cp = charPage(n, mid);
                  if (cp >= page) hi = mid; else lo = mid + 1;
                }
                o = lo;
                while (o > 0 && !/\s/.test(v.charAt(o - 1))) o--;
                if (charPage(n, o) < page) { while (o < v.length && !/\s/.test(v.charAt(o))) o++; while (o < v.length && /\s/.test(v.charAt(o))) o++; }
              }
              return { node: n, offset: o };
            }
            return null;
          }
          // Splits the text from a starting point to the end of the file into sentences,
          // remembering which text nodes each one came from so spoken words can be found again.
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
                var m = re.exec(v);
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
          function highlight(name, range) {
            if (!(window.CSS && CSS.highlights && window.Highlight)) return;
            if (range) CSS.highlights.set(name, new Highlight(range)); else CSS.highlights.delete(name);
          }
          function userTurned() { if (tts && bridge && bridge.onManualTurn) bridge.onManualTurn(); }

          var folio = window.folio = {
            next: function () {
              if (leaving) return;
              if (page >= lastPage()) { if (anim) { cancelAnimationFrame(anim); anim = null; } setScroll(page * width()); if (bridge && bridge.nextChapter()) leaving = true; }
              else { show(page + 1, true); userTurned(); }
            },
            prev: function () {
              if (leaving) return;
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
                var p = Math.floor((rects[0].left + curX + 1) / width());
                if (p !== page) show(p, true);
              }
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
    /** [section] indexes the chapter's section ids (-1 before the first); [sectionEnd] is its last page. */
    private val pageChanged: (page: Int, count: Int, offset: Float, section: Int, sectionEnd: Int) -> Unit,
    private val loaded: (page: Int, count: Int, offset: Float, section: Int, sectionEnd: Int) -> Unit,
    private val tapped: (zone: Int) -> Unit,
    /** Return whether there is a chapter to move to. */
    private val nextRequested: () -> Boolean,
    private val prevRequested: () -> Boolean,
    /** The reader turned the page themselves (only reported while reading aloud). */
    private val manualTurn: () -> Unit = {},
) {
    @JavascriptInterface fun onPage(page: Int, count: Int, offset: Float, section: Int, sectionEnd: Int) =
        pageChanged(page, count, offset, section, sectionEnd)
    @JavascriptInterface fun onReady(page: Int, count: Int, offset: Float, section: Int, sectionEnd: Int) =
        loaded(page, count, offset, section, sectionEnd)
    @JavascriptInterface fun onTap(zone: Int) = tapped(zone)
    @JavascriptInterface fun nextChapter(): Boolean = nextRequested()
    @JavascriptInterface fun prevChapter(): Boolean = prevRequested()
    @JavascriptInterface fun onManualTurn() = manualTurn()
}
