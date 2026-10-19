package com.folio.reader.pdf

import android.graphics.Bitmap
import android.graphics.Color
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
