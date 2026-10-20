package com.folio.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A PDF drawn page by page with Android's own renderer. It only draws: links, forms,
 * attachments and scripts inside the file are never run. The renderer can show one
 * page at a time, so every call is serialized here.
 */
class PdfBook private constructor(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    private var closed = false

    /** Each page's width / height. */
    @Synchronized
    fun aspectRatios(): List<Float> = List(renderer.pageCount) { i ->
        page(i) { p -> if (p.height > 0) p.width.toFloat() / p.height else 0.707f }
    }

    /** Page [index] drawn [widthPx] wide on white, or null once closed. */
    @Synchronized
    fun render(index: Int, widthPx: Int): Bitmap? {
        if (closed || index !in 0 until renderer.pageCount) return null
        return page(index) { page ->
            val w = widthPx.coerceIn(1, MAX_SIDE)
            val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, MAX_SIDE)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /**
     * The part of page [index] between [left], [top], [right] and [bottom] (points from the
     * page's top-left), drawn at most [maxWidthPx] wide on white. Used for a PDF's pictures.
     */
    @Synchronized
    fun renderRegion(index: Int, left: Float, top: Float, right: Float, bottom: Float, maxWidthPx: Int): Bitmap? {
        if (closed || index !in 0 until renderer.pageCount) return null
        val w = right - left
        val h = bottom - top
        if (w < 1 || h < 1) return null
        return page(index) { page ->
            val scale = minOf(maxWidthPx / w, 4f, MAX_SIDE / h)
            val bw = (w * scale).toInt().coerceIn(1, MAX_SIDE)
            val bh = (h * scale).toInt().coerceIn(1, MAX_SIDE)
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(-left * scale, -top * scale)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /** The first page, about 600px tall, for the library. */
    fun renderCover(): Bitmap? = runCatching {
        val ratio = aspectRatios().firstOrNull() ?: return null
        render(0, (600 * ratio).toInt().coerceAtLeast(200))
    }.getOrNull()

    private inline fun <T> page(index: Int, block: (PdfRenderer.Page) -> T): T {
        val page = renderer.openPage(index)
        try {
            return block(page)
        } finally {
            page.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        private const val MAX_SIDE = 4096

        /** Whether [bitmap] is (nearly) one flat colour: an empty area, not a picture. */
        fun isBlank(bitmap: Bitmap): Boolean {
            val steps = 48
            val lum = IntArray(steps * steps)
            for (y in 0 until steps) for (x in 0 until steps) {
                val c = bitmap.getPixel(x * (bitmap.width - 1) / (steps - 1), y * (bitmap.height - 1) / (steps - 1))
                lum[y * steps + x] = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
            }
            lum.sort()
            val median = lum[lum.size / 2]
            val different = lum.count { kotlin.math.abs(it - median) > 10 }
            return different < lum.size / 100
        }

        fun open(file: File): PdfBook {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close()
                throw IOException("This PDF is password-protected, so Folio can't open it.")
            } catch (e: Exception) {
                fd.close()
                throw IOException("This PDF is damaged or isn't really a PDF.")
            }
            return PdfBook(fd, renderer)
        }
    }
}
