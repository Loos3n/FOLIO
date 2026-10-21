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
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws a page turning like a page of a book: the page folds back along a line that sweeps
 * across the screen, showing its paper back, with shadows either side of the fold.
 *
 * Only a picture of the page being left is needed. Forward, the picture is the page folding
 * away, with the book (already on the next page) showing past the fold. Back, the book (already
 * on the previous page) is the page folding in, laid over the picture of the page being left.
 */
class PageTurner {
    /** The picture of the page being left. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The colour of the paper, for the back of the folding page. */
    var paper by mutableStateOf(Color.White)
        private set
    var forward by mutableStateOf(true)
        private set
    /** Until the new page is drawn, the picture covers the book whichever way it's going. */
    var covering by mutableStateOf(false)
        private set
    /** 0 = the old page lies flat, 1 = the new page lies flat. */
    var progress by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null
    private var ready: CompletableDeferred<Unit>? = null
    /** Where the finger has dragged the page to, while the picture is still being taken. */
    private var dragTarget = 0f
    private var dragging = false
    /** The picture couldn't be taken: the turn happens without being drawn. */
    private var unseen = false

    /** The book finished laying out a chapter (the turn may be waiting for it). */
    fun chapterReady() {
        ready?.complete(Unit)
    }

    /**
     * Starts turning the page in [view] under the reader's finger. [move] makes the book change
     * page (without its own animation); [edge] means into another chapter, so wait for that.
     */
    fun start(scope: CoroutineScope, view: View, forward: Boolean, edge: Boolean, move: () -> Unit) {
        job?.cancel()
        clear()
        this.forward = forward
        dragging = true
        dragTarget = 0f
        unseen = false
        job = scope.launch {
            val shot = capture(view)
            Log.d(TAG, "turn forward=$forward edge=$edge captured=${shot != null}")
            if (shot == null) {
                unseen = true
                return@launch
            }
            paper = paperOf(shot)
            image = shot.asImageBitmap()
            covering = true
            progress = 0f
            val waitFor = if (edge) CompletableDeferred<Unit>().also { ready = it } else null
            move()
            if (waitFor != null) withTimeoutOrNull(900) { waitFor.await() }
            // Give the book a couple of frames to draw the new page under the picture.
            delay(if (edge) 60 else 40)
            covering = false
            progress = if (dragging) dragTarget else 0f
        }
    }

    /** The finger has dragged the page [fraction] of the way across the screen. */
    fun drag(fraction: Float) {
        // Going forward the fold moves half as fast as the edge of the page the finger holds;
        // going back the finger holds the fold itself.
        dragTarget = (if (forward) fraction * 0.5f else fraction).coerceIn(0f, 1f)
        if (image != null && !covering) progress = dragTarget
    }

    /**
     * Lets go: finishes the turn ([commit]) or lays the page back down, with [undo] putting the
     * book back on the page it was on. [move] is for a turn that couldn't be drawn.
     */
    fun end(scope: CoroutineScope, commit: Boolean, move: () -> Unit, undo: () -> Unit) {
        dragging = false
        val preparing = job
        job = scope.launch {
            preparing?.join()
            if (unseen) {
                if (commit) move()
                clear()
                return@launch
            }
            if (image == null) return@launch
            val to = if (commit) 1f else 0f
            val distance = kotlin.math.abs(to - progress)
            // A whole turn takes most of a second however fast the flick, so it is always seen.
            animate(progress, to, animationSpec = tween((260 + 560 * distance).toInt(), easing = TURN_EASING)) { v, _ -> progress = v }
            if (!commit) {
                covering = true
                undo()
                delay(60)
            }
            Log.d(TAG, "turn done commit=$commit")
            clear()
        }
    }

    /** A whole turn on its own (a tap or a volume button). */
    fun turn(scope: CoroutineScope, view: View, forward: Boolean, edge: Boolean, move: () -> Unit) {
        start(scope, view, forward, edge, move)
        end(scope, commit = true, move = move, undo = {})
    }

    private fun clear() {
        image = null
        covering = false
        ready = null
        progress = 0f
        // Not recycled: the last frame drawn may still be using it; it's freed with the image.
    }

    companion object {
        private const val TAG = "FolioTurn"
        private val TURN_EASING = CubicBezierEasing(0.3f, 0.1f, 0.25f, 1f)
    }
}

/** The page's paper colour, from just inside its top-left corner. */
private fun paperOf(shot: Bitmap): Color = runCatching {
    Color(shot.getPixel((shot.width / 40).coerceIn(0, shot.width - 1), (shot.height / 80).coerceIn(0, shot.height - 1)))
}.getOrDefault(Color.White)

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

/**
 * Drawn over the book. The fold is a vertical line at x = [fold]; the page turning lies flat to
 * the left of where its folded part ends, the folded part (its back) sits between there and the
 * fold, and the page underneath shows to the right of the fold.
 */
@Composable
fun PageTurnOver(turner: PageTurner) {
    val image = turner.image ?: return
    val covering = turner.covering
    val forward = turner.forward
    val p = turner.progress
    val paper = turner.paper
    // Compose's Canvas (android.graphics.Canvas is imported for taking the picture).
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val dst = IntSize(w.toInt(), h.toInt())
        if (covering) {
            drawImage(image, dstSize = dst)
            return@Canvas
        }
        // How far the fold has swept, from the right edge (0) to the left edge (1).
        val swept = if (forward) p else 1f - p
        val fold = w * (1f - swept)
        val flapEdge = 2f * fold - w
        // The picture: the flat part of the page turning (forward) or the page underneath (back).
        if (forward) {
            if (flapEdge > 0f) clipRect(0f, 0f, flapEdge, h) { drawImage(image, dstSize = dst) }
        } else {
            clipRect(fold, 0f, w, h) { drawImage(image, dstSize = dst) }
        }
        val strength = sin(PI.toFloat() * swept).coerceAtLeast(0f)
        // The page underneath darkens next to the fold, where the turning page overhangs it.
        val shadow = (w * 0.06f + w * 0.12f * strength).coerceAtMost(w - fold)
        if (shadow > 1f) {
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.38f * strength + 0.06f),
                    1f to Color.Transparent,
                    startX = fold, endX = fold + shadow,
                ),
                topLeft = Offset(fold, 0f), size = Size(shadow, h),
            )
        }
        // The folded part's edge casts a soft shadow on the flat part of the page.
        if (flapEdge > 0f) {
            val cast = (w * 0.05f).coerceAtMost(flapEdge)
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.22f * strength),
                    startX = flapEdge - cast, endX = flapEdge,
                ),
                topLeft = Offset(flapEdge - cast, 0f), size = Size(cast, h),
            )
        }
        // The back of the folding page: paper, with the print showing through faintly, reversed.
        val left = flapEdge.coerceAtLeast(0f)
        if (fold - left > 0.5f) {
            clipRect(left, 0f, fold, h) {
                drawRect(paper)
                withTransform({ scale(-1f, 1f, pivot = Offset(fold, 0f)) }) {
                    drawImage(image, dstSize = dst, alpha = 0.12f)
                }
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.White.copy(alpha = 0.10f),
                        0.6f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.20f),
                        startX = flapEdge, endX = fold,
                    ),
                    topLeft = Offset(left, 0f), size = Size(fold - left, h),
                )
            }
        }
    }
}
