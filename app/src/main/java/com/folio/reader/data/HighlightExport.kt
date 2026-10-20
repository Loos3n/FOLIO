package com.folio.reader.data

import android.content.Context
import android.content.Intent
import kotlin.math.roundToInt

/** A book's highlights and notes as one plain text, to paste or send anywhere. */
object HighlightExport {
    fun text(book: Book): String = buildString {
        appendLine(book.title)
        book.author?.let { appendLine(it) }
        val n = book.highlights.size
        val notes = book.highlights.count { it.note.isNotBlank() }
        appendLine(
            (if (n == 1) "1 highlight" else "$n highlights") +
                (if (notes > 0) ", ${if (notes == 1) "1 with a note" else "$notes with notes"}" else ""),
        )
        var chapter: String? = null
        book.highlights.sortedWith(compareBy({ it.chapterIndex }, { it.start })).forEach { h ->
            val title = h.chapterTitle.ifBlank { "Highlights" }
            if (title != chapter) {
                appendLine()
                appendLine("— $title —")
                chapter = title
            }
            appendLine()
            appendLine("“${h.text.trim()}”")
            if (h.note.isNotBlank()) appendLine("Note: ${h.note.trim()}")
            appendLine("(${(h.progress * 100).roundToInt()}% through)")
        }
    }.trimEnd() + "\n"

    /** Opens the share sheet with [book]'s highlights, ready for Notes, email, messages or the clipboard. */
    fun share(context: Context, book: Book) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Highlights from ${book.title}")
            putExtra(Intent.EXTRA_TEXT, text(book))
        }
        context.startActivity(Intent.createChooser(send, "Share highlights").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
