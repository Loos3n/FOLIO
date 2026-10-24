package com.folio.reader.data

import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme(val label: String, val background: Long, val text: Long, val link: Long, val isDark: Boolean) {
    Light("Paper", 0xFFFCFBF8, 0xFF26231F, 0xFF2F5FA7, false),
    Sepia("Sepia", 0xFFF3E9D2, 0xFF4A3A28, 0xFF8A5A1C, false),
    Dark("Dusk", 0xFF1D1C21, 0xFFE3DFD8, 0xFF9BBCF2, true),
    Black("Night", 0xFF000000, 0xFFB9B5AE, 0xFF8AB4F8, true),
}

enum class ReaderFont(val label: String, val css: String?) {
    Original("Book's own", null),
    Literata("Literata", "'Folio Literata', Georgia, serif"),
    Inter("Inter", "'Folio Inter', 'Roboto', sans-serif"),
    Atkinson("Atkinson", "'Folio Atkinson', 'Roboto', sans-serif"),
}

/** How a page changes in page mode. */
enum class PageTurn(val label: String, val js: String) {
    Turn("Turn", "turn"),
    Slide("Slide", "slide"),
    Off("None", "none"),
}

data class ReaderSettings(
    /** WebView text zoom in percent. */
    val textZoom: Int = 110,
    val lineHeight: Float = 1.6f,
    /** Horizontal page margin in CSS px. */
    val margin: Int = 24,
    /** null = follow the system light/dark setting. */
    val theme: ReaderTheme? = null,
    val font: ReaderFont = ReaderFont.Literata,
    /** Even right edges with hyphenation, like a printed page. */
    val justify: Boolean = true,
    /** Volume up/down turn pages while reading. */
    val volumeKeys: Boolean = false,
    /** Read-aloud speed (1 = normal) and voice name; null voice = the engine's default. */
    val speechRate: Float = 1f,
    val voice: String? = null,
    /** How the library grid is ordered (a SortOrder name). */
    val librarySort: String = "Recent",
    /** The library shows books as spines on wooden shelves instead of a grid of covers. */
    val shelfView: Boolean = false,
    /** Books in the same series stack into one tile in the library. */
    val groupSeries: Boolean = true,
    /** PDFs open in the page view instead of as text (each can still switch while open). */
    val pdfOpenAsPages: Boolean = false,
    /** Books open by scrolling rather than in pages, unless a book says otherwise. */
    val scrollDefault: Boolean = false,
    /** Scrolling mode: a band that marks the reading line. */
    val ruler: Boolean = false,
    /** Auto-scroll speed in screen pixels (dp) a second. */
    val autoScrollSpeed: Float = 40f,
    /** The reader's measured reading speed, for "minutes left"; 0 until learned. */
    val charsPerMinute: Float = 0f,
    /** Minutes of reading (or listening) to aim for each day; 0 = no goal. */
    val dailyGoalMinutes: Int = 0,
    /** Screen brightness while reading, 0..1; below 0 follows the phone. */
    val brightness: Float = -1f,
    /** Warm (amber) tint over the page, 0 = off .. 1 = strongest. */
    val warmth: Float = 0f,
    /** Tapping near the left or right edge turns the page (otherwise only swipes do). */
    val tapToTurn: Boolean = false,
    /**
     * What a tap does in each ninth of the page when [tapToTurn] is on, row by row from the
     * top left: B goes back a page, F forward, M shows the menu.
     */
    val tapZones: String = DEFAULT_TAP_ZONES,
    /** Each book keeps its own font, size, spacing, margins and colours instead of sharing one look. */
    val perBookLook: Boolean = false,
    /** A font the reader added (its file name in the fonts folder), used instead of [font]; null = none. */
    val customFont: String? = null,
    /** Books to finish this year; 0 = no goal. */
    val yearlyBookGoal: Int = 0,
    val pageTurn: PageTurn = PageTurn.Turn,
) {
    fun resolvedTheme(systemDark: Boolean): ReaderTheme =
        theme ?: if (systemDark) ReaderTheme.Dark else ReaderTheme.Light

    /** These settings with the look (font, size, spacing, margins, colours) of [other]. */
    fun withLookOf(other: ReaderSettings) = copy(
        textZoom = other.textZoom,
        lineHeight = other.lineHeight,
        margin = other.margin,
        theme = other.theme,
        font = other.font,
        customFont = other.customFont,
        justify = other.justify,
    )

    fun sameLook(other: ReaderSettings) = withLookOf(other) == this

    /** What a tap in [zone] (0..8, row by row from the top left) does: 'B', 'F' or 'M'. */
    fun tapAction(zone: Int): Char = tapZones.getOrNull(zone)?.takeIf { it in "BFM" } ?: DEFAULT_TAP_ZONES[zone.coerceIn(0, 8)]

    companion object {
        /** Left third back, right third forward, the middle shows the menu. */
        const val DEFAULT_TAP_ZONES = "BMFBMFBMF"
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ReaderSettings> = _settings.asStateFlow()

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putInt("textZoom", next.textZoom)
            .putFloat("lineHeight", next.lineHeight)
            .putInt("margin", next.margin)
            .putString("theme", next.theme?.name)
            .putString("font", next.font.name)
            .putBoolean("justifyText", next.justify)
            .putBoolean("volumeKeysTurnPages", next.volumeKeys)
            .putFloat("speechRate", next.speechRate)
            .putString("voice", next.voice)
            .putString("librarySort", next.librarySort)
            .putBoolean("shelfView", next.shelfView)
            .putBoolean("groupSeries", next.groupSeries)
            .putBoolean("pdfOpenAsPages", next.pdfOpenAsPages)
            .putBoolean("scrollDefault", next.scrollDefault)
            .putBoolean("ruler", next.ruler)
            .putFloat("autoScrollSpeed", next.autoScrollSpeed)
            .putFloat("charsPerMinute", next.charsPerMinute)
            .putInt("dailyGoalMinutes", next.dailyGoalMinutes)
            .putFloat("brightness", next.brightness)
            .putFloat("warmth", next.warmth)
            .putBoolean("tapToTurn", next.tapToTurn)
            .putString("customFont", next.customFont)
            .putInt("yearlyBookGoal", next.yearlyBookGoal)
            .putString("pageTurn", next.pageTurn.name)
            .putString("tapZones", next.tapZones)
            .putBoolean("perBookLook", next.perBookLook)
            .apply()
    }

    private val _looksChanged = MutableStateFlow(0)
    /** Bumped whenever a book's own look is saved or cleared. */
    val looksChanged: StateFlow<Int> = _looksChanged.asStateFlow()

    /** [base] with the book's own look on top, if it has one. */
    fun withBookLook(id: String, base: ReaderSettings): ReaderSettings {
        val o = prefs.getString(lookKey(id), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return base
        return base.copy(
            textZoom = o.optInt("textZoom", base.textZoom),
            lineHeight = o.optDouble("lineHeight", base.lineHeight.toDouble()).toFloat(),
            margin = o.optInt("margin", base.margin),
            theme = if (o.has("theme")) ReaderTheme.entries.firstOrNull { it.name == o.optString("theme") } else base.theme,
            font = ReaderFont.entries.firstOrNull { it.name == o.optString("font") } ?: base.font,
            customFont = if (o.has("customFont")) o.optString("customFont").ifEmpty { null } else base.customFont,
            justify = o.optBoolean("justify", base.justify),
        )
    }

    fun hasBookLook(id: String) = prefs.contains(lookKey(id))

    /** Saves [s]'s look as the book's own. Kept with the other settings, so backups carry it. */
    fun saveBookLook(id: String, s: ReaderSettings) {
        val o = JSONObject()
            .put("textZoom", s.textZoom)
            .put("lineHeight", s.lineHeight.toDouble())
            .put("margin", s.margin)
            .put("theme", s.theme?.name ?: "")
            .put("font", s.font.name)
            .put("customFont", s.customFont ?: "")
            .put("justify", s.justify)
        prefs.edit().putString(lookKey(id), o.toString()).apply()
        _looksChanged.value++
    }

    fun clearBookLook(id: String) {
        prefs.edit().remove(lookKey(id)).apply()
        _looksChanged.value++
    }

    private fun lookKey(id: String) = "look:$id"

    /** Every setting, for a backup. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is Boolean, is Int, is Long, is String -> o.put(key, JSONObject().put("t", value.javaClass.simpleName).put("v", value))
                is Float -> o.put(key, JSONObject().put("t", "Float").put("v", value.toDouble()))
            }
        }
        return o
    }

    /** Puts back the settings from a backup. */
    fun restore(o: JSONObject) {
        val edit = prefs.edit()
        for (key in o.keys()) {
            val e = o.optJSONObject(key) ?: continue
            when (e.optString("t")) {
                "Boolean" -> edit.putBoolean(key, e.optBoolean("v"))
                "Integer" -> edit.putInt(key, e.optInt("v"))
                "Long" -> edit.putLong(key, e.optLong("v"))
                "Float" -> edit.putFloat(key, e.optDouble("v").toFloat())
                "String" -> edit.putString(key, e.optString("v"))
            }
        }
        edit.commit()
        _settings.value = read()
        _looksChanged.value++
    }

    private fun read(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            textZoom = prefs.getInt("textZoom", d.textZoom),
            lineHeight = prefs.getFloat("lineHeight", d.lineHeight),
            margin = prefs.getInt("margin", d.margin),
            theme = prefs.getString("theme", null)?.let { n -> ReaderTheme.entries.firstOrNull { it.name == n } },
            font = prefs.getString("font", null)?.let { n -> ReaderFont.entries.firstOrNull { it.name == n } } ?: d.font,
            justify = prefs.getBoolean("justifyText", d.justify),
            volumeKeys = prefs.getBoolean("volumeKeysTurnPages", d.volumeKeys),
            speechRate = prefs.getFloat("speechRate", d.speechRate),
            voice = prefs.getString("voice", null),
            librarySort = prefs.getString("librarySort", null) ?: d.librarySort,
            shelfView = prefs.getBoolean("shelfView", d.shelfView),
            groupSeries = prefs.getBoolean("groupSeries", d.groupSeries),
            pdfOpenAsPages = prefs.getBoolean("pdfOpenAsPages", d.pdfOpenAsPages),
            scrollDefault = prefs.getBoolean("scrollDefault", d.scrollDefault),
            ruler = prefs.getBoolean("ruler", d.ruler),
            autoScrollSpeed = prefs.getFloat("autoScrollSpeed", d.autoScrollSpeed),
            charsPerMinute = prefs.getFloat("charsPerMinute", d.charsPerMinute),
            dailyGoalMinutes = prefs.getInt("dailyGoalMinutes", d.dailyGoalMinutes),
            brightness = prefs.getFloat("brightness", d.brightness),
            warmth = prefs.getFloat("warmth", d.warmth),
            tapToTurn = prefs.getBoolean("tapToTurn", d.tapToTurn),
            customFont = prefs.getString("customFont", null),
            yearlyBookGoal = prefs.getInt("yearlyBookGoal", d.yearlyBookGoal),
            pageTurn = prefs.getString("pageTurn", null)?.let { n -> PageTurn.entries.firstOrNull { it.name == n } } ?: d.pageTurn,
            tapZones = prefs.getString("tapZones", null)?.takeIf { it.length == 9 } ?: d.tapZones,
            perBookLook = prefs.getBoolean("perBookLook", d.perBookLook),
        )
    }
}
