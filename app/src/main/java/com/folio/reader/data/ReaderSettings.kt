package com.folio.reader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReaderTheme(val label: String, val background: Long, val text: Long, val link: Long, val isDark: Boolean) {
    Light("Light", 0xFFFFFFFF, 0xFF1F1F1F, 0xFF2D5DA8, false),
    Sepia("Sepia", 0xFFF6EEDC, 0xFF433422, 0xFF8A5A1C, false),
    Dark("Dark", 0xFF1E1F22, 0xFFDCDCDC, 0xFF8AB4F8, true),
    Black("Black", 0xFF000000, 0xFFBDBDBD, 0xFF8AB4F8, true),
}

enum class ReaderFont(val label: String, val css: String?) {
    Original("Book", null),
    Serif("Serif", "Georgia, 'Noto Serif', serif"),
    Sans("Sans", "'Roboto', 'Noto Sans', sans-serif"),
}

data class ReaderSettings(
    /** WebView text zoom in percent. */
    val textZoom: Int = 110,
    val lineHeight: Float = 1.6f,
    /** Horizontal page margin in CSS px. */
    val margin: Int = 20,
    /** null = follow the system light/dark setting. */
    val theme: ReaderTheme? = null,
    val font: ReaderFont = ReaderFont.Serif,
    val justify: Boolean = false,
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
        )
    }
}
