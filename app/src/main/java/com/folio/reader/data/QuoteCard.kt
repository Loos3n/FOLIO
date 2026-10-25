package com.folio.reader.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File

/** A highlight drawn as a picture to share: the quote big, the book underneath. */
object QuoteCard {
    enum class Look(val label: String) { Paper("Paper"), Night("Night"), Colour("Colour") }
    enum class Shape(val label: String, val width: Int, val height: Int) {
        Tall("Tall", 1080, 1350),
        Square("Square", 1080, 1080),
        Story("Story", 1080, 1920),
    }

    fun render(quote: String, title: String, author: String?, color: HighlightColor, look: Look, shape: Shape): Bitmap {
        val w = shape.width
        val h = shape.height
        val tint = color.argb.toInt()
        val (bg, fg, muted, accent) = when (look) {
            Look.Paper -> listOf(0xFFF6F1E7.toInt(), 0xFF2B2620.toInt(), 0xFF7A6F60.toInt(), darker(tint))
            Look.Night -> listOf(0xFF15171C.toInt(), 0xFFEDE9E1.toInt(), 0xFF9A958C.toInt(), tint)
            Look.Colour -> listOf(tint, 0xFF1C1B1A.toInt(), 0xA61C1B1A.toInt(), 0xFF1C1B1A.toInt())
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg)

        val pad = 110f
        val textWidth = (w - pad * 2).toInt()

        // A large opening quote mark sets the tone.
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = accent
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = 240f
        }
        canvas.drawText("“", pad - 12f, pad + 170f, markPaint)

        // The footer: a short rule, the title and the author.
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = fg
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 38f
        }
        val authorPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = muted
            typeface = Typeface.SANS_SERIF
            textSize = 32f
        }
        val titleLayout = layout(title, titlePaint, textWidth, maxLines = 2)
        val authorLayout = author?.takeIf { it.isNotBlank() }?.let { layout(it, authorPaint, textWidth, maxLines = 1) }
        val footerHeight = 6f + 34f + titleLayout.height + (authorLayout?.let { 10f + it.height } ?: 0f)
        val footerTop = h - pad - footerHeight

        // The quote, as large as it can be while still fitting between the mark and the footer.
        val quoteTop = pad + 210f
        val room = (footerTop - 60f - quoteTop).toInt()
        val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = fg
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val text = quote.trim().replace(Regex("\\s+"), " ")
        var size = 72f
        var quoteLayout: StaticLayout
        while (true) {
            quotePaint.textSize = size
            quoteLayout = layout(text, quotePaint, textWidth, spacing = 1.25f)
            if (quoteLayout.height <= room || size <= 34f) break
            size -= 2f
        }
        if (quoteLayout.height > room) {
            val lines = (room / (quoteLayout.height.toFloat() / quoteLayout.lineCount)).toInt().coerceAtLeast(1)
            quoteLayout = layout(text, quotePaint, textWidth, spacing = 1.25f, maxLines = lines)
        }
        canvas.save()
        canvas.translate(pad, quoteTop)
        quoteLayout.draw(canvas)
        canvas.restore()

        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = accent }
        canvas.drawRoundRect(RectF(pad, footerTop, pad + 90f, footerTop + 6f), 3f, 3f, rule)
        canvas.save()
        canvas.translate(pad, footerTop + 40f)
        titleLayout.draw(canvas)
        authorLayout?.let {
            canvas.translate(0f, titleLayout.height + 10f)
            it.draw(canvas)
        }
        canvas.restore()

        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = muted
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textSize = 30f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("Folio", w - pad, pad + 20f, brand)
        return bitmap
    }

    /** Saves [bitmap] and opens the share sheet with it. */
    fun share(context: Context, bitmap: Bitmap, title: String) {
        val dir = File(context.cacheDir, "quotes").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "quote-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "A quote from $title")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share quote").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun layout(text: String, paint: TextPaint, width: Int, spacing: Float = 1.1f, maxLines: Int = Int.MAX_VALUE): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, spacing)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** The highlight colour deepened so it reads on light paper. */
    private fun darker(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(1f)
        hsv[2] *= 0.72f
        return Color.HSVToColor(hsv)
    }
}
