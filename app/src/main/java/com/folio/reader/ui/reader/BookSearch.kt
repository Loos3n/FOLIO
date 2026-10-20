package com.folio.reader.ui.reader

import android.text.Html

/** One place a search matched: the text around it, and which match it is in its chapter file. */
data class SearchHit(
    val chapterIndex: Int,
    /** Counts matches from the top of the chapter file; the page script finds the same one. */
    val occurrence: Int,
    val before: String,
    val match: String,
    val after: String,
    val chapterTitle: String,
    val progress: Float,
)

data class SearchState(
    val query: String = "",
    val running: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    /** True when there were more matches than are listed. */
    val truncated: Boolean = false,
)

internal object BookSearch {
    const val MAX_HITS = 300

    /**
     * A regex source, valid in both Java and JavaScript, matching [query] case-insensitively
     * with any run of spaces between words and straight or curly quotes alike.
     */
    fun pattern(query: String): String =
        query.trim().split(Regex("\\s+")).joinToString("[\\s\\u00A0]+") { word ->
            buildString {
                for (ch in word) when {
                    ch.isLetterOrDigit() -> append(ch)
                    ch == '\'' || ch == '‘' || ch == '’' -> append("[\\u0027\\u2018\\u2019]")
                    ch == '"' || ch == '“' || ch == '”' -> append("[\\u0022\\u201C\\u201D]")
                    else -> append(String.format("\\u%04X", ch.code))
                }
            }
        }

    private val BODY_START = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BODY_END = Regex("</body\\s*>", RegexOption.IGNORE_CASE)
    private val SKIPPED = Regex(
        "<!--.*?-->|<(script|style|rt|title)\\b[^>]*/>|<(script|style|rt|title)\\b[^>]*>.*?</\\2\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”",
    )

    /**
     * The text of a chapter file's body, as the page script sees it (all text nodes in order,
     * minus scripts, styles, ruby text and titles), so match counts line up with the page.
     */
    fun plainText(html: String): String {
        var body = html.replace("\r\n", "\n").replace('\r', '\n')
        BODY_START.find(body)?.let { body = body.substring(it.range.last + 1) }
        BODY_END.find(body)?.let { body = body.substring(0, it.range.first) }
        body = body.replace(SKIPPED, "").replace(TAG, "")
        return body.replace(ENTITY) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[name] ?: Html.fromHtml(m.value, Html.FROM_HTML_MODE_LEGACY).toString().ifEmpty { m.value }
            }
        }
    }

    /** A short excerpt around a match, on one line. */
    fun excerpt(text: String, start: Int, end: Int): Triple<String, String, String> {
        fun clean(s: String) = s.replace(Regex("\\s+"), " ")
        var before = clean(text.substring((start - 80).coerceAtLeast(0), start)).trimStart()
        var after = clean(text.substring(end, (end + 120).coerceAtMost(text.length))).trimEnd()
        if (start > 80) before = "…" + before.substringAfter(' ', before)
        if (end + 120 < text.length) after = after.substringBeforeLast(' ', after) + "…"
        return Triple(before, clean(text.substring(start, end)), after)
    }
}
