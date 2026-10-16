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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
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
    Color(0xFF2F4858) to Color(0xFFF6AE2D),
    Color(0xFF5B3E31) to Color(0xFFE9C46A),
    Color(0xFF264653) to Color(0xFFE9C46A),
    Color(0xFF7D4E57) to Color(0xFFF4D6CC),
    Color(0xFF3D405B) to Color(0xFFF2CC8F),
    Color(0xFF1B4332) to Color(0xFFD8F3DC),
    Color(0xFF6D2E46) to Color(0xFFECE2D0),
    Color(0xFF22333B) to Color(0xFFC6AC8F),
)

@Composable
fun BookCover(title: String, author: String?, file: File?, modifier: Modifier = Modifier) {
    val image by produceState(initialValue = file?.let { CoverCache.get(it.path) }, file) {
        if (value == null && file != null) {
            value = withContext(Dispatchers.IO) { runCatching { CoverCache.load(file) }.getOrNull() }
        }
    }
    val bitmap = image
    Box(modifier) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            GeneratedCover(title, author, Modifier.fillMaxSize())
        }
        // A soft spine shadow and page sheen so covers read as physical books.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.22f),
                        0.035f to Color.White.copy(alpha = 0.12f),
                        0.07f to Color.Transparent,
                        1f to Color.Transparent,
                    ),
                ),
        )
    }
}

/** A designed stand-in for books that ship without cover art. */
@Composable
private fun GeneratedCover(title: String, author: String?, modifier: Modifier) {
    val (base, accent) = placeholderPalettes[title.hashCode().absoluteValue % placeholderPalettes.size]
    val display = MaterialTheme.typography.headlineSmall.fontFamily
    Box(modifier.background(base)) {
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
            Box(Modifier.width(22.dp).height(2.dp).background(accent))
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = Color.White,
                fontFamily = display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (author != null) {
                Text(
                    author.uppercase(),
                    color = accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
