package com.folio.reader.pdf

import com.folio.reader.data.Book
import java.io.File
import java.util.zip.ZipFile

/**
 * Moves the reader's place, bookmarks and highlights from one text version of a PDF to a
 * newer one, by finding the same words in the new text. Chapters may be split differently.
 */
object TextCarryOver {
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    /** Returns null when the old text can't be read, so the book starts again from the top. */
    fun build(old: File, new: File, newTitles: List<String>): ((Book) -> Book)? {
        val a = runCatching { chapters(old) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val b = runCatching { chapters(new) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val aStarts = starts(a)
        val bStarts = starts(b)
        val aFull = a.joinToString("")
        val bFull = b.joinToString("")

        fun locate(g: Int): Int {
            val guess = (g.toLong() * bFull.length / aFull.length.coerceAtLeast(1)).toInt()
            val window = maxOf(20_000, bFull.length / 20)
            for (size in intArrayOf(40, 20)) {
                val key = aFull.substring(g.coerceIn(0, aFull.length), (g + size).coerceAtMost(aFull.length))
                if (key.isBlank() || key.trim().length < 8) continue
                var best = -1
                var at = bFull.indexOf(key, (guess - window).coerceAtLeast(0))
                while (at >= 0 && at <= guess + window) {
                    if (best < 0 || kotlin.math.abs(at - guess) < kotlin.math.abs(best - guess)) best = at
                    at = bFull.indexOf(key, at + 1)
                }
                if (best >= 0) return best
            }
            return guess.coerceIn(0, bFull.length)
        }

        fun chapterAt(g: Int): Int {
            var i = bStarts.binarySearch(g)
            if (i < 0) i = -i - 2
            return i.coerceIn(0, b.size - 1)
        }

        /** Old (chapter, character) to new (chapter, character). */
        fun move(chapter: Int, offset: Int): Pair<Int, Int> {
            val c = chapter.coerceIn(0, a.size - 1)
            val g = locate(aStarts[c] + offset.coerceIn(0, a[c].length))
            val n = chapterAt(g)
            return n to (g - bStarts[n])
        }

        fun fraction(chapter: Int, offset: Float): Int {
            val c = chapter.coerceIn(0, a.size - 1)
            return (offset.coerceIn(0f, 1f) * a[c].length).toInt()
        }

        fun title(i: Int) = newTitles.getOrNull(i) ?: "Section ${i + 1}"

        return { book ->
            val (ci, co) = move(book.chapterIndex, fraction(book.chapterIndex, book.chapterOffset))
            book.copy(
                chapterIndex = ci,
                chapterOffset = co.toFloat() / b[ci].length.coerceAtLeast(1),
                bookmarks = book.bookmarks.map { m ->
                    val (i, o) = move(m.chapterIndex, fraction(m.chapterIndex, m.chapterOffset))
                    m.copy(chapterIndex = i, chapterOffset = o.toFloat() / b[i].length.coerceAtLeast(1), chapterTitle = title(i))
                },
                highlights = book.highlights.map { h ->
                    val (i, o) = move(h.chapterIndex, h.start)
                    h.copy(chapterIndex = i, start = o, end = o + (h.end - h.start), chapterTitle = title(i))
                },
            )
        }
    }

    private fun starts(chapters: List<String>): IntArray {
        val out = IntArray(chapters.size)
        var sum = 0
        chapters.forEachIndexed { i, c ->
            out[i] = sum
            sum += c.length
        }
        return out
    }

    /** The text of each chapter of a text version made by [PdfEpubWriter], as the reader counts it. */
    private fun chapters(file: File): List<String> = ZipFile(file).use { zip ->
        generateSequence(0) { it + 1 }
            .map { zip.getEntry("OEBPS/c$it.xhtml") }
            .takeWhile { it != null }
            .map { e -> plain(zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)) }
            .toList()
    }

    private fun plain(xhtml: String): String {
        val body = xhtml.substringAfter("<body>").substringBeforeLast("</body>")
        return ENTITY.replace(TAG.replace(body, "")) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                else -> " "
            }
        }
    }
}
