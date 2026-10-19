package com.folio.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
 * Searches Project Gutenberg's 70,000-odd public-domain books (through the Gutendex
 * catalogue API) and downloads their EPUBs.
 */
object FreeBooks {
    private const val API = "https://gutendex.com/books"

    /** Popular books, or those matching [query] (title or author) and [topic] (subject or shelf). */
    suspend fun search(query: String, topic: String?): FreeBooksPage {
        val params = buildList {
            add("languages=en")
            if (query.isNotBlank()) add("search=" + URLEncoder.encode(query.trim(), "UTF-8"))
            if (!topic.isNullOrBlank()) add("topic=" + URLEncoder.encode(topic, "UTF-8"))
        }
        return page("$API/?" + params.joinToString("&"))
    }

    /** The page after one already shown ([FreeBooksPage.next]). */
    suspend fun page(url: String): FreeBooksPage = withContext(Dispatchers.IO) {
        val o = JSONObject(get(url).bufferedReader().use { it.readText() })
        val results = o.optJSONArray("results")
        val books = (0 until (results?.length() ?: 0)).mapNotNull { i -> parse(results!!.getJSONObject(i)) }
        FreeBooksPage(books, o.optString("next").takeIf { !o.isNull("next") && it.startsWith("https://") })
    }

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

    private fun parse(o: JSONObject): FreeBook? {
        val formats = o.optJSONObject("formats") ?: return null
        val epub = formats.optString("application/epub+zip").takeIf { it.isNotBlank() } ?: return null
        val authors = o.optJSONArray("authors")
        val author = authors?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::displayName)
        return FreeBook(
            id = o.optInt("id"),
            title = cleanTitle(o.optString("title")),
            author = author?.let { if ((authors?.length() ?: 0) > 1) "$it and others" else it },
            coverUrl = formats.optString("image/jpeg").takeIf { it.isNotBlank() },
            epubUrl = epub,
            downloads = o.optInt("download_count"),
            summary = o.optJSONArray("summaries")?.optString(0)?.takeIf { it.isNotBlank() }
                ?.removeSuffix("(This is an automatically generated summary.)")?.trim(),
        )
    }

    /** "Dostoyevsky, Fyodor" → "Fyodor Dostoyevsky"; keeps names with more than one comma as they are. */
    internal fun displayName(name: String): String {
        val parts = name.split(", ")
        if (parts.size != 2) return name
        // Drop any bracketed full name: "Carroll, Lewis (Lewis Carroll)".
        val first = parts[1].substringBefore(" (").trim()
        return "$first ${parts[0].trim()}"
    }

    /** Gutenberg titles carry subtitles after a colon or semicolon, sometimes over several lines. */
    internal fun cleanTitle(title: String): String {
        val oneLine = title.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()
        val main = oneLine.substringBefore("; ").substringBefore(": ").trim()
        return main.ifBlank { oneLine }
    }
}
