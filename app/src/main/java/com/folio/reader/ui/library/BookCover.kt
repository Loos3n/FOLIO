package com.folio.reader.ui.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

/** Decoded covers, kept in memory so the grid scrolls without re-decoding. */
private object CoverCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(path: String): ImageBitmap? = cache.get(path)

    fun load(file: File): ImageBitmap? {
        cache.get(file.path)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(file.path)?.asImageBitmap() ?: return null
        cache.put(file.path, bitmap)
        return bitmap
    }
}

private val placeholderPalettes = listOf(
    Color(0xFF3D5A80) to Color(0xFF98C1D9),
    Color(0xFF6D597A) to Color(0xFFB56576),
    Color(0xFF2A9D8F) to Color(0xFF8AB17D),
    Color(0xFFBC6C25) to Color(0xFFDDA15E),
    Color(0xFF264653) to Color(0xFF2A9D8F),
    Color(0xFF9D0208) to Color(0xFFE85D04),
    Color(0xFF283618) to Color(0xFF606C38),
    Color(0xFF5E548E) to Color(0xFF9F86C0),
)

@Composable
fun BookCover(title: String, author: String?, file: File?, modifier: Modifier = Modifier) {
    val image by produceState(initialValue = file?.let { CoverCache.get(it.path) }, file) {
        if (value == null && file != null) {
            value = withContext(Dispatchers.IO) { runCatching { CoverCache.load(file) }.getOrNull() }
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, contentDescription = title, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        GeneratedCover(title, author, modifier)
    }
}

/** A tasteful stand-in for books that ship without cover art. */
@Composable
private fun GeneratedCover(title: String, author: String?, modifier: Modifier) {
    val (dark, light) = placeholderPalettes[title.hashCode().absoluteValue % placeholderPalettes.size]
    Box(modifier.background(Brush.linearGradient(listOf(dark, light)))) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Box(Modifier.width(24.dp).height(3.dp).background(Color.White.copy(alpha = 0.7f)))
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (author != null) {
                Text(
                    author,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
