package com.folio.reader.data

/**
 * Works out a book's series from its title when the book itself doesn't say, e.g.
 * "Percy Jackson 1 - The Lightning Thief", "The Hunger Games (The Hunger Games, Book 1)"
 * or "Wheel of Time #4". Titles that merely contain a number ("Fahrenheit 451",
 * "Apollo 13: Lost Moon") are left alone: a bare number only counts before a dash.
 */
object SeriesGuess {
    private const val NUM = """(\d{1,3}(?:\.\d)?)"""
    private const val MARK = """(?:#|No\.?\s*|Book\s+|Vol(?:ume)?\.?\s*|Part\s+)"""

    private val patterns = listOf(
        // Title (Series, #1)
        Regex("""^.+?\s*\(\s*(.+?)\s*,?\s*$MARK$NUM\s*\)\s*$""", RegexOption.IGNORE_CASE),
        // Series Book 2: Title, Series #2 - Title
        Regex("""^(.+?)\s*[,:]?\s+$MARK$NUM\s*[-–—:.]\s*\S.*$""", RegexOption.IGNORE_CASE),
        // Series 1 - Title
        Regex("""^(.+?)\s*,?\s+$NUM\s+[-–—]\s*\S.*$"""),
        // Series Book 2, Series #2
        Regex("""^(.+?)\s*[,:]?\s+$MARK$NUM\s*$""", RegexOption.IGNORE_CASE),
    )

    /** The series name and number in it, or null when the title doesn't name one. */
    fun fromTitle(title: String): Pair<String, Float>? {
        for (p in patterns) {
            val m = p.matchEntire(title.trim()) ?: continue
            val name = m.groupValues[1].trim(' ', '-', '–', '—', ':', ',', '.')
            val number = m.groupValues[2].toFloatOrNull() ?: continue
            if (name.length >= 2 && name.any { it.isLetter() }) return name to number
        }
        return null
    }
}
