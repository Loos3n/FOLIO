package com.folio.reader.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Draws a page turning over like a page of a book, hinged at the left edge.
 *
 * Forward: a picture of the current page is laid over the book, which moves to the next
 * page underneath, and the picture swings away. Back: the picture of the current page goes
 * underneath and the book itself, now on the previous page, swings back down over it.
 */
class PageTurner {
    /** The picture of the page being left, over the book (forward) or under it (back). */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    var forward by mutableStateOf(true)
        private set
    /** Until the new page is drawn, the picture covers the book whichever way it's going. */
    var covering by mutableStateOf(false)
        private set
    val progress = Animatable(0f)

    private var job: Job? = null
    private var ready: CompletableDeferred<Unit>? = null

    /** The book finished laying out a chapter (the turn may be waiting for it). */
    fun chapterReady() {
        ready?.complete(Unit)
    }

    /**
     * Turns the page in [view]: [move] makes the book change page (without its own animation).
     * [edge] means it moves into another chapter, so wait for that to be laid out.
     */
    fun turn(scope: CoroutineScope, view: View, forward: Boolean, edge: Boolean, move: () -> Unit) {
        job?.cancel()
        clear()
        job = scope.launch {
            val shot = capture(view)
            Log.d(TAG, "turn forward=$forward edge=$edge captured=${shot != null}")
            if (shot == null) {
                move()
                return@launch
            }
            this@PageTurner.forward = forward
            image = shot.asImageBitmap()
            covering = true
            progress.snapTo(0f)
            val waitFor = if (edge) CompletableDeferred<Unit>().also { ready = it } else null
            move()
            if (waitFor != null) withTimeoutOrNull(900) { waitFor.await() }
            // Give the book a couple of frames to draw the new page under the picture.
            delay(if (edge) 60 else 34)
            covering = false
            progress.animateTo(1f, tween(if (forward) 420 else 380, easing = TURN_EASING))
            Log.d(TAG, "turn done")
            clear()
        }
    }

    private fun clear() {
        image = null
        covering = false
        ready = null
        // Not recycled: the last frame drawn may still be using it; it's freed with the image.
    }

    companion object {
        private const val TAG = "FolioTurn"
        private val TURN_EASING = CubicBezierEasing(0.3f, 0.0f, 0.2f, 1f)
    }
}

/** The page as it's shown on screen right now (including what the GPU draws), or null. */
private suspend fun capture(view: View): Bitmap? {
    val activity = view.context.findActivity() ?: return null
    if (view.width == 0 || view.height == 0 || !view.isAttachedToWindow) return null
    val at = IntArray(2)
    view.getLocationInWindow(at)
    val bitmap = runCatching { Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
    val ok = suspendCancellableCoroutine { cont ->
        runCatching {
            PixelCopy.request(
                activity.window,
                Rect(at[0], at[1], at[0] + view.width, at[1] + view.height),
                bitmap,
                { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure { if (cont.isActive) cont.resume(false) }
    }
    if (ok) return bitmap
    // Some phones refuse a copy of the window; draw the view ourselves instead.
    Log.d("FolioTurn", "PixelCopy failed; drawing the view instead")
    return runCatching {
        view.draw(Canvas(bitmap))
        bitmap
    }.getOrElse {
        bitmap.recycle()
        null
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** How far the book itself is turned (going back it swings down from upright), for its graphicsLayer. */
fun PageTurner.bookAngle(): Float =
    if (image != null && !forward && !covering) -90f * (1f - progress.value) else 0f

/** Drawn under the book: the page being left when going back, darkening as the new page lands. */
@Composable
fun PageTurnUnder(turner: PageTurner) {
    val image = turner.image ?: return
    if (turner.forward || turner.covering) return
    Box(Modifier.fillMaxSize()) {
        Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f * turner.progress.value)))
    }
}

/** Drawn over the book: the page swinging away going forward (or covering the book while it moves). */
@Composable
fun PageTurnOver(turner: PageTurner) {
    val image = turner.image ?: return
    if (!turner.forward && !turner.covering) {
        // Going back: shade the book's page as it swings down, lightest when it lands.
        val p = turner.progress.value
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = turner.bookAngle()
                    cameraDistance = 14f * density
                }
                .background(Color.Black.copy(alpha = 0.28f * (1f - p))),
        )
        return
    }
    val p = if (turner.covering) 0f else turner.progress.value
    Box(Modifier.fillMaxSize()) {
        // The new page underneath brightens as the old one lifts off it.
        if (!turner.covering) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f * (1f - p))))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = -90f * p
                    cameraDistance = 14f * density
                },
        ) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            // Light catches the page as it turns: a soft shade from the spine outwards.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.05f * p),
                            1f to Color.Black.copy(alpha = 0.35f * p),
                        ),
                    ),
            )
        }
    }
}
