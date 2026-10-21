package com.folio.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** A font file the reader added, kept in the app's own fonts folder. */
data class UserFont(val fileName: String) {
    /** "Merriweather-Regular.ttf" reads as "Merriweather Regular". */
    val label: String get() = fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
}

/** Fonts the reader adds from their phone (TrueType or OpenType files) to read books in. */
class UserFonts(private val context: Context) {
    companion object {
        private const val MAX_BYTES = 20L * 1024 * 1024
        val MIME_TYPES = arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream")

        /** Whether [name] is a safe file name inside the fonts folder. */
        fun safeName(name: String) = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name
    }

    val dir: File = File(context.filesDir, "fonts").apply { mkdirs() }

    private val _fonts = MutableStateFlow(scan())
    val fonts: StateFlow<List<UserFont>> = _fonts.asStateFlow()

    fun file(name: String): File? = name.takeIf(::safeName)?.let { File(dir, it) }?.takeIf { it.isFile }

    /** Copies the font at [uri] in, after checking it really is one. */
    suspend fun add(uri: Uri): Result<UserFont> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { BookSafety.readLimited(it, MAX_BYTES) }
                ?: error("Couldn't open that file.")
            val ext = when {
                bytes.size < 12 -> null
                bytes[0] == 0.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte() -> "ttf"
                String(bytes, 0, 4, Charsets.ISO_8859_1) == "true" -> "ttf"
                String(bytes, 0, 4, Charsets.ISO_8859_1) == "OTTO" -> "otf"
                else -> null
            } ?: error("That isn't a font file Folio can use. Pick a .ttf or .otf file.")
            val base = (displayName(uri) ?: "Font").substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60).ifEmpty { "Font" }
            val font = UserFont("$base.$ext")
            File(dir, font.fileName).writeBytes(bytes)
            _fonts.value = scan()
            font
        }
    }

    fun remove(font: UserFont) {
        file(font.fileName)?.delete()
        _fonts.value = scan()
    }

    /** Fonts brought back by a restore. */
    fun refresh() {
        _fonts.value = scan()
    }

    private fun scan(): List<UserFont> =
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
            ?.map { UserFont(it.name) }?.sortedBy { it.label.lowercase() }.orEmpty()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
