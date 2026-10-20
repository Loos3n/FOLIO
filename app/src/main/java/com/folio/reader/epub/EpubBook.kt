package com.folio.reader.epub

import com.folio.reader.data.BookSafety
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile

data class ManifestItem(
    val id: String,
    /** Full path inside the zip archive, e.g. "OEBPS/text/ch01.xhtml". */
    val path: String,
    val mediaType: String,
    val properties: Set<String>,
)

data class TocEntry(
    val title: String,
    /** Full path inside the archive, without fragment. */
    val path: String,
    val fragment: String?,
    val level: Int,
)

class EpubFormatException(message: String) : Exception(message)

/**
 * A minimal, dependency-free EPUB 2/3 reader. Keeps the zip open so chapters and
 * resources can be streamed straight into the WebView without unpacking the book.
 */
class EpubBook(file: File) : Closeable {

    private val zip = ZipFile(file)

    val title: String
    val author: String?
    /** BCP 47 language tag from the package metadata, e.g. "en"; used for hyphenation. */
    val language: String?
    /** The series the book belongs to and its number in it, when the package says (Calibre or EPUB 3 style). */
    val series: String?
    val seriesIndex: Float?
    val manifest: Map<String, ManifestItem>
    val spine: List<ManifestItem>
    val toc: List<TocEntry>
    val coverPath: String?

    /** Uncompressed size of each spine item; used to weight whole-book progress. */
    val spineWeights: List<Long>

    private val itemsByPath: Map<String, ManifestItem>


    init {
        try {
            val opfPath = readContainer()
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = parseOpf(opfPath, opfDir)
            title = opf.title?.takeIf { it.isNotBlank() } ?: ""
            author = opf.author?.takeIf { it.isNotBlank() }
            language = opf.language?.takeIf { it.isNotBlank() }
            series = opf.series?.takeIf { it.isNotBlank() }
            seriesIndex = opf.seriesIndex?.takeIf { series != null }
            manifest = opf.manifest
            itemsByPath = manifest.values.associateBy { it.path }
            spine = opf.spineIds.mapNotNull { manifest[it] }
            if (spine.isEmpty()) throw EpubFormatException("This book has no readable chapters.")
            coverPath = findCover(opf)
            toc = (parseNavToc() ?: opf.ncxId?.let { parseNcxToc(it) })
                ?.takeIf { it.isNotEmpty() }
                ?: spine.mapIndexed { i, item -> TocEntry("Section ${i + 1}", item.path, null, 0) }
            spineWeights = spine.map { item -> (zip.getEntry(item.path)?.size ?: 1L).coerceAtLeast(1L) }
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    fun open(path: String): InputStream? = zip.getEntry(path)?.let { zip.getInputStream(it) }

    fun readBytes(path: String): ByteArray? = open(path)?.use { BookSafety.readLimited(it) }

    fun itemForPath(path: String): ManifestItem? = itemsByPath[path]

    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path }

    fun mediaTypeFor(path: String): String =
        itemsByPath[path]?.mediaType ?: guessMediaType(path)

    override fun close() = zip.close()

    // ---------------------------------------------------------------- parsing

    private class Opf(
        val title: String?,
        val author: String?,
        val language: String?,
        val manifest: Map<String, ManifestItem>,
        val spineIds: List<String>,
        val ncxId: String?,
        val coverMetaId: String?,
        val series: String?,
        val seriesIndex: Float?,
    )

    private fun newParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        // Real-world EPUBs often contain HTML entities such as &nbsp; that strict XML rejects.
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(input, null)
        for ((name, value) in HTML_ENTITIES) runCatching { parser.defineEntityReplacementText(name, value) }
        return parser
    }

    private fun readContainer(): String {
        val stream = open("META-INF/container.xml")
            ?: throw EpubFormatException("This file isn't a valid EPUB (missing container.xml).")
        stream.use {
            val p = newParser(it)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                    p.getAttributeValue(null, "full-path")?.let { path -> return path }
                }
            }
        }
        throw EpubFormatException("This EPUB doesn't say where its contents are.")
    }

    private fun parseOpf(opfPath: String, opfDir: String): Opf {
        val stream = open(opfPath) ?: throw EpubFormatException("This EPUB is missing its package file.")
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var ncxId: String? = null
        var coverMetaId: String? = null
        var series: String? = null
        var seriesIndex: Float? = null
        // EPUB 3: <meta property="belongs-to-collection" id="c">Name</meta> refined by group-position.
        var collectionId: String? = null
        val positions = HashMap<String, Float>()
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        stream.use {
            val p = newParser(it)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "title" -> if (title == null) title = p.nextText().trim()
                    "creator" -> if (author == null) author = p.nextText().trim()
                    "language" -> if (language == null) language = p.nextText().trim()
                    "meta" -> when (p.getAttributeValue(null, "name")) {
                        "cover" -> coverMetaId = p.getAttributeValue(null, "content")
                        "calibre:series" -> series = p.getAttributeValue(null, "content")?.trim()
                        "calibre:series_index" -> seriesIndex = p.getAttributeValue(null, "content")?.trim()?.toFloatOrNull()
                        else -> when (p.getAttributeValue(null, "property")) {
                            "belongs-to-collection" -> if (series == null) {
                                collectionId = p.getAttributeValue(null, "id")
                                series = runCatching { p.nextText().trim() }.getOrNull()
                            }
                            "group-position" -> {
                                val refines = p.getAttributeValue(null, "refines")?.removePrefix("#")
                                val at = runCatching { p.nextText().trim().toFloatOrNull() }.getOrNull()
                                if (refines != null && at != null) positions[refines] = at
                            }
                        }
                    }
                    "item" -> {
                        val id = p.getAttributeValue(null, "id") ?: continue
                        val href = p.getAttributeValue(null, "href") ?: continue
                        manifest[id] = ManifestItem(
                            id = id,
                            path = resolvePath(opfDir, href),
                            mediaType = p.getAttributeValue(null, "media-type") ?: guessMediaType(href),
                            properties = p.getAttributeValue(null, "properties")
                                ?.split(' ')?.filter { s -> s.isNotBlank() }?.toSet() ?: emptySet(),
                        )
                    }
                    "spine" -> ncxId = p.getAttributeValue(null, "toc")
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { ref -> spine += ref }
                }
            }
        }
        if (seriesIndex == null) seriesIndex = collectionId?.let { positions[it] }
        return Opf(title, author, language, manifest, spine, ncxId, coverMetaId, series, seriesIndex)
    }

    private fun findCover(opf: Opf): String? {
        val images = manifest.values.filter { it.mediaType.startsWith("image/") }
        return (images.firstOrNull { "cover-image" in it.properties }
            ?: opf.coverMetaId?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image/") }
            ?: images.firstOrNull { it.id.contains("cover", ignoreCase = true) }
            ?: images.firstOrNull { it.path.substringAfterLast('/').contains("cover", ignoreCase = true) })
            ?.path
    }

    /** EPUB 3 navigation document: <nav epub:type="toc"><ol><li><a href>…</a><ol>… */
    private fun parseNavToc(): List<TocEntry>? {
        val nav = manifest.values.firstOrNull { "nav" in it.properties } ?: return null
        val navDir = nav.path.substringBeforeLast('/', "")
        return runCatching {
            open(nav.path)?.use { stream ->
                val p = newParser(stream)
                val entries = ArrayList<TocEntry>()
                var navDepth = 0 // >0 while inside the toc <nav>
                var tocFound = false
                var olDepth = 0
                var currentHref: String? = null
                val text = StringBuilder()
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "nav" -> {
                                if (navDepth > 0) {
                                    navDepth++
                                } else if (!tocFound) {
                                    val type = p.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                                        ?: p.getAttributeValue(null, "epub:type")
                                    if (type == null || type.split(' ').contains("toc")) {
                                        navDepth = 1
                                        tocFound = true
                                    }
                                }
                            }
                            "ol" -> if (navDepth > 0) olDepth++
                            "a" -> if (navDepth > 0) {
                                currentHref = p.getAttributeValue(null, "href")
                                text.setLength(0)
                            }
                        }
                        XmlPullParser.TEXT -> if (currentHref != null) text.append(p.text)
                        XmlPullParser.END_TAG -> when (p.name) {
                            "nav" -> if (navDepth > 0) navDepth--
                            "ol" -> if (navDepth > 0) olDepth--
                            "a" -> {
                                val href = currentHref
                                if (navDepth > 0 && href != null) {
                                    val label = text.toString().replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
                                    if (label.isNotEmpty()) {
                                        entries += tocEntry(navDir, href, label, (olDepth - 1).coerceAtLeast(0))
                                    }
                                }
                                currentHref = null
                            }
                        }
                    }
                }
                entries
            }
        }.getOrNull()
    }

    /** EPUB 2 NCX: <navMap><navPoint><navLabel><text/></navLabel><content src/>… */
    private fun parseNcxToc(ncxId: String): List<TocEntry>? {
        val ncx = manifest[ncxId] ?: return null
        val ncxDir = ncx.path.substringBeforeLast('/', "")
        return runCatching {
            open(ncx.path)?.use { stream ->
                val p = newParser(stream)
                val entries = ArrayList<TocEntry>()
                var depth = 0
                var label: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "navPoint" -> { depth++; label = null }
                            "text" -> if (depth > 0 && label == null) label = p.nextText().trim()
                            "content" -> {
                                val src = p.getAttributeValue(null, "src")
                                if (depth > 0 && src != null) {
                                    entries += tocEntry(ncxDir, src, label ?: "Section", depth - 1)
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> if (p.name == "navPoint") depth--
                    }
                }
                entries
            }
        }.getOrNull()
    }

    private fun tocEntry(baseDir: String, href: String, title: String, level: Int): TocEntry {
        val fragment = href.substringAfter('#', "").ifEmpty { null }
        return TocEntry(title, resolvePath(baseDir, href.substringBefore('#')), fragment, level)
    }

    companion object {
        private val HTML_ENTITIES = mapOf(
            "nbsp" to "\u00A0", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
            "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
            "copy" to "\u00A9", "eacute" to "\u00E9", "shy" to "",
        )

        /** Resolves an href relative to [baseDir] into a normalized zip path. */
        fun resolvePath(baseDir: String, href: String): String {
            val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
            val combined = if (decoded.startsWith("/")) decoded.drop(1)
            else if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
            val parts = ArrayList<String>()
            for (segment in combined.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts += segment
                }
            }
            return parts.joinToString("/")
        }

        fun guessMediaType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "xhtml", "xht", "xml" -> "application/xhtml+xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "js" -> "text/javascript"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
