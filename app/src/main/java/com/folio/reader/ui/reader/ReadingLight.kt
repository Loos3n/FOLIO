package com.folio.reader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.folio.reader.data.ReaderSettings

private val WARM = Color(0xFFFF9A3C)

/**
 * The reader's own screen brightness (just while a book is open) and a warm tint over the
 * page, for reading at night. Goes last in the screen's box so it covers the page.
 */
@Composable
fun ReadingLight(settings: ReaderSettings) {
    val window = LocalContext.current.findActivity()?.window
    val brightness = settings.brightness
    DisposableEffect(window, brightness) {
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                else brightness.coerceIn(0.01f, 1f)
            }
        }
        onDispose {
            window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
    val warmth = settings.warmth.coerceIn(0f, 1f)
    // Below the phone's lowest backlight, the page itself is dimmed.
    val dim = if (brightness in 0f..0.1f) (0.1f - brightness) * 4f else 0f
    if (warmth <= 0f && dim <= 0f) return
    Canvas(Modifier.fillMaxSize()) {
        if (warmth > 0f) drawRect(WARM.copy(alpha = warmth * 0.55f), blendMode = BlendMode.Multiply)
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
