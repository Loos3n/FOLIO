package com.folio.reader.data

import android.content.Context
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

data class ReaderSettings(
    /** WebView text zoom in percent. */
    val textZoom: Int = 110,
    val lineHeight: Float = 1.6f,
    /** Horizontal page margin in CSS px. */
    val margin: Int = 24,
    /** null = follow the system light/dark setting. */
    val theme: ReaderTheme? = null,
    val font: ReaderFont = ReaderFont.Literata,
    val justify: Boolean = true,
    /** Volume up/down turn pages while reading. */
    val volumeKeys: Boolean = true,
) {
    fun resolvedTheme(systemDark: Boolean): ReaderTheme =
        theme ?: if (systemDark) ReaderTheme.Dark else ReaderTheme.Light
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
            .putBoolean("justify", next.justify)
            .putBoolean("volumeKeys", next.volumeKeys)
            .apply()
    }

    private fun read(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            textZoom = prefs.getInt("textZoom", d.textZoom),
            lineHeight = prefs.getFloat("lineHeight", d.lineHeight),
            margin = prefs.getInt("margin", d.margin),
            theme = prefs.getString("theme", null)?.let { n -> ReaderTheme.entries.firstOrNull { it.name == n } },
            font = prefs.getString("font", null)?.let { n -> ReaderFont.entries.firstOrNull { it.name == n } } ?: d.font,
            justify = prefs.getBoolean("justify", d.justify),
            volumeKeys = prefs.getBoolean("volumeKeys", d.volumeKeys),
        )
    }
}
