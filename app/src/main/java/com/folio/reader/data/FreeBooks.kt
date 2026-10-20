package com.folio.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A free book from Project Gutenberg's catalogue. */
data class FreeBook(
    val id: Int,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val epubUrl: String,
    val downloads: Int,
    val summary: String?,
)

data class FreeBooksPage(val books: List<FreeBook>, val next: String?)

/**
 * Searches Project Gutenberg's 70,000-odd public-domain books through its own OPDS
 * catalogue feed, and downloads their EPUBs.
 */
object FreeBooks {
    private const val SITE = "https://www.gutenberg.org"

    /** Popular books, or those matching [query] (title or author) and [topic] (a subject keyword). */
    suspend fun search(query: String, topic: String?): FreeBooksPage {
        val words = listOfNotNull(query.trim().takeIf { it.isNotEmpty() }, topic?.takeIf { it.isNotBlank() }).joinToString(" ")
        val url = if (words.isEmpty()) "$SITE/ebooks/search.opds/?sort_order=downloads"
        else "$SITE/ebooks/search.opds/?query=" + URLEncoder.encode(words, "UTF-8") +
            if (query.isBlank()) "&sort_order=downloads" else ""
        return page(url)
    }

    /** The page after one already shown ([FreeBooksPage.next]). */
    suspend fun page(url: String): FreeBooksPage = withContext(Dispatchers.IO) {
        parseFeed(get(url).bufferedReader().use { it.readText() })
    }

    internal fun parseFeed(xml: String): FreeBooksPage {
        val books = xml.split("<entry>").drop(1).mapNotNull { e ->
            val id = Regex("<id>[^<]*/ebooks/(\\d+)\\.opds</id>").find(e)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val title = Regex("<title>([^<]*)</title>").find(e)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            val author = Regex("<content[^>]*>([^<]*)</content>").find(e)?.groupValues?.get(1)?.let(::unescape)
                ?.trim()?.takeIf { it.isNotEmpty() }
            FreeBook(
                id = id,
                title = cleanTitle(title),
                author = author,
                coverUrl = "$SITE/cache/epub/$id/pg$id.cover.medium.jpg",
                epubUrl = "$SITE/ebooks/$id.epub3.images",
                downloads = 0,
                summary = null,
            )
        }
        val next = Regex("<link[^>]*rel=\"next\"[^>]*>").find(xml)?.value
            ?.let { Regex("href=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
            ?.let(::unescape)
            ?.let { if (it.startsWith("/")) SITE + it else it }
            ?.takeIf { it.startsWith("https://") }
        return FreeBooksPage(books.distinctBy { it.id }, next)
    }

    private fun unescape(s: String): String = s
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Downloads [book]'s EPUB into [dir]. */
    suspend fun download(book: FreeBook, dir: File): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val file = File(dir, "gutenberg-${book.id}.epub")
        get(book.epubUrl, timeout = 60_000).use { input -> FileOutputStream(file).use { input.copyTo(it) } }
        file
    }

    /** Bytes of a cover image, small enough to decode straight away. */
    suspend fun coverBytes(url: String): ByteArray = withContext(Dispatchers.IO) { get(url).use { it.readBytes() } }

    private fun get(url: String, timeout: Int = 20_000): java.io.InputStream {
        var target = URL(url)
        // Follow redirects by hand: HttpURLConnection won't between hosts' http and https.
        repeat(5) {
            val conn = target.openConnection() as HttpURLConnection
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Folio (Android e-reader)")
            when (val code = conn.responseCode) {
                in 200..299 -> return conn.inputStream
                in 300..399 -> {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    conn.disconnect()
                    target = URL(target, location)
                }
                else -> {
                    conn.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    /** Gutenberg titles carry subtitles after a colon or semicolon, sometimes over several lines. */
    internal fun cleanTitle(title: String): String {
        val oneLine = title.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()
        val main = oneLine.substringBefore("; ").substringBefore(": ").trim()
        return main.ifBlank { oneLine }
    }
}
