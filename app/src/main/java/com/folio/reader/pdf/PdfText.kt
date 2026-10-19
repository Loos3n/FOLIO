package com.folio.reader.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A PDF's text, laid out again as chapters of paragraphs (see assets/pdfjs/convert.js). */
class PdfText(
    val title: String,
    val author: String,
    /** True when the PDF has (almost) no text: a scan, or pages that are all pictures. */
    val scanned: Boolean,
    val chapters: List<Chapter>,
) {
    class Chapter(val title: String, val html: String)
}

/**
 * Pulls the text out of a PDF with pdf.js in a hidden WebView (Android's own PDF renderer
 * can only draw pages), then writes it as an EPUB so the normal reader can show it with
 * the reader's fonts, themes, search, highlights and read-aloud.
 */
object PdfTextConverter {
    private const val HOST = "folio.pdf"
    private const val ORIGIN = "https://$HOST/"

    suspend fun extract(context: Context, pdf: File, onProgress: (done: Int, total: Int) -> Unit): PdfText =
        withTimeout(10 * 60_000L) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val webView = createWebView(context.applicationContext, pdf, onProgress) { result ->
                        if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                    }
                    cont.invokeOnCancellation { webView.post { webView.destroy() } }
                }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        context: Context,
        pdf: File,
        onProgress: (Int, Int) -> Unit,
        finished: (Result<PdfText>) -> Unit,
    ): WebView {
        val webView = WebView(context)
        var done = false
        fun finish(result: Result<PdfText>) {
            webView.post {
                if (done) return@post
                done = true
                finished(result)
                webView.destroy()
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun progress(done: Int, total: Int) = onProgress(done, total)

            @JavascriptInterface
            fun done(json: String) {
                finish(runCatching { parse(json) })
            }

            @JavascriptInterface
            fun failed(message: String) {
                finish(Result.failure(IOException(message.ifBlank { "Couldn't read the text in this PDF." })))
            }
        }, "FolioConvert")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                serve(context, pdf, request.url)

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                finish(Result.failure(IOException("This PDF couldn't be read.")))
                return true
            }
        }
        webView.loadUrl(ORIGIN + "convert.html")
        return webView
    }

    private fun serve(context: Context, pdf: File, url: Uri): WebResourceResponse {
        if (url.host != HOST) return blocked()
        val path = url.path?.removePrefix("/").orEmpty()
        return when {
            path == "convert.html" -> WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(PAGE.toByteArray()))
            path == "book.pdf" -> WebResourceResponse("application/pdf", null, pdf.inputStream())
            path.startsWith("pdfjs/") && ".." !in path -> {
                val type = when {
                    path.endsWith(".js") -> "text/javascript"
                    path.endsWith(".bcmap") -> "application/octet-stream"
                    else -> "application/octet-stream"
                }
                runCatching { WebResourceResponse(type, if (type.startsWith("text")) "UTF-8" else null, context.assets.open(path)) }
                    .getOrElse { blocked() }
            }
            else -> blocked()
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun parse(json: String): PdfText {
        val o = JSONObject(json)
        val list = o.optJSONArray("chapters")
        val chapters = (0 until (list?.length() ?: 0)).map { i ->
            val c = list!!.getJSONObject(i)
            PdfText.Chapter(c.optString("title", "Section ${i + 1}"), c.optString("html"))
        }
        return PdfText(o.optString("title"), o.optString("author"), o.optBoolean("scanned"), chapters)
    }

    private val PAGE = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <script src="${ORIGIN}pdfjs/pdf.min.js"></script>
        <script src="${ORIGIN}pdfjs/convert.js"></script>
        </head><body><script>
        (function () {
          try {
            pdfjsLib.GlobalWorkerOptions.workerSrc = '${ORIGIN}pdfjs/pdf.worker.min.js';
            fetch('${ORIGIN}book.pdf')
              .then(function (r) { return r.arrayBuffer(); })
              .then(function (buf) {
                return FolioPdf.convert(pdfjsLib, new Uint8Array(buf), {
                  cMapUrl: '${ORIGIN}pdfjs/cmaps/',
                  onProgress: function (i, n) { FolioConvert.progress(i, n); },
                });
              })
              .then(function (r) { FolioConvert.done(JSON.stringify(r)); })
              .catch(function (e) { FolioConvert.failed(String((e && e.message) || e)); });
          } catch (e) { FolioConvert.failed(String((e && e.message) || e)); }
        })();
        </script></body></html>
    """.trimIndent()
}

/** Writes [text] as a small EPUB 3 at [target], with [cover] (JPEG bytes) as its cover. */
object PdfEpubWriter {
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")

    fun write(text: PdfText, title: String, author: String?, cover: Bitmap?, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        ZipOutputStream(FileOutputStream(tmp)).use { zip ->
            // The mimetype entry comes first and uncompressed, as EPUB requires.
            val mime = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun put(name: String, s: String) = put(name, s.toByteArray(Charsets.UTF_8))

            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                    """<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put("OEBPS/style.css", CSS)
            val coverBytes = cover?.let {
                ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.JPEG, 85, out) }.toByteArray()
            }
            if (coverBytes != null) put("OEBPS/cover.jpg", coverBytes)

            val chapters = text.chapters
            chapters.forEachIndexed { i, c ->
                put("OEBPS/c$i.xhtml", page(c.title, CONTROL.replace(c.html, "")))
            }
            put(
                "OEBPS/nav.xhtml",
                page("Contents", "<nav epub:type=\"toc\"><ol>" +
                    chapters.mapIndexed { i, c -> "<li><a href=\"c$i.xhtml\">${esc(c.title)}</a></li>" }.joinToString("") +
                    "</ol></nav>"),
            )
            val manifest = buildString {
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>")
                if (coverBytes != null) append("<item id=\"cover\" href=\"cover.jpg\" media-type=\"image/jpeg\" properties=\"cover-image\"/>")
                chapters.indices.forEach { append("<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>") }
            }
            val spine = chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="utf-8"?>""" +
                    """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">""" +
                    """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                    """<dc:identifier id="id">urn:uuid:${UUID.randomUUID()}</dc:identifier>""" +
                    "<dc:title>${esc(title)}</dc:title>" +
                    (author?.let { "<dc:creator>${esc(it)}</dc:creator>" } ?: "") +
                    "</metadata><manifest>$manifest</manifest><spine>$spine</spine></package>",
            )
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Couldn't save the text of this PDF.")
        }
    }

    private fun page(title: String, body: String) =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">""" +
            "<head><title>${esc(title)}</title><link rel=\"stylesheet\" href=\"style.css\"/></head>" +
            "<body>$body</body></html>"

    private fun esc(s: String) = CONTROL.replace(s, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val CSS = "p{margin:0;text-indent:1.4em;}" +
        "h1+p,h2+p,h3+p,p.c+p{text-indent:0;}" +
        "p.c{text-align:center;text-indent:0;margin:0.6em 0;}" +
        "h1,h2,h3{text-align:center;line-height:1.25;}" +
        "h1{font-size:1.6em;margin:1.5em 0 1em;}h2{font-size:1.3em;margin:1.3em 0 0.8em;}h3{font-size:1.05em;margin:1.2em 0 0.6em;}" +
        "sup{font-size:0.7em;line-height:0;}"
}
