package com.folio.reader.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.folio.reader.FolioApp
import com.folio.reader.MainActivity
import com.folio.reader.R
import com.folio.reader.data.Book
import java.time.LocalDate
import kotlin.math.roundToInt

/** A home-screen widget with the book being read; one tap carries on reading it. */
class ReadingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** Redraws every Folio widget on the home screen. Cheap when there are none. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ReadingWidget::class.java))
            if (ids.isEmpty()) return
            val views = views(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        /** The book to show: the one opened most recently that isn't finished. */
        fun currentBook(books: List<Book>): Book? =
            books.filter { it.lastOpenedAt > 0 && it.progress < 0.995f }.maxByOrNull { it.lastOpenedAt }

        private fun views(context: Context): RemoteViews {
            val app = context.applicationContext as FolioApp
            val book = currentBook(app.library.books.value)
            val views = RemoteViews(context.packageName, R.layout.widget_reading)
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (book != null) putExtra(MainActivity.EXTRA_OPEN_BOOK, book.id)
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )

            if (book == null) {
                views.setViewVisibility(R.id.widget_cover, View.GONE)
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                views.setViewVisibility(R.id.widget_author, View.GONE)
                views.setTextViewText(R.id.widget_title, "Nothing on the go")
                views.setTextViewText(R.id.widget_status, "Tap to pick a book")
            } else {
                val cover = app.library.coverFile(book)?.let { decodeCover(it.path) }
                if (cover != null) {
                    views.setImageViewBitmap(R.id.widget_cover, cover)
                    views.setViewVisibility(R.id.widget_cover, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_cover, View.GONE)
                }
                views.setTextViewText(R.id.widget_title, book.title)
                views.setTextViewText(R.id.widget_author, book.author.orEmpty())
                views.setViewVisibility(R.id.widget_author, if (book.author.isNullOrBlank()) View.GONE else View.VISIBLE)
                val percent = (book.progress * 100).roundToInt().coerceIn(0, 100)
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
                views.setProgressBar(R.id.widget_progress, 100, percent, false)
                views.setTextViewText(
                    R.id.widget_status,
                    "$percent% · " + if (book.isAudio) "Tap to keep listening" else "Tap to keep reading",
                )
            }

            val goal = app.settings.settings.value.dailyGoalMinutes
            val stats = app.stats.stats.value
            val today = LocalDate.now()
            if (goal > 0) {
                val minutes = stats.secondsOn(today) / 60
                val streak = stats.streak(today, goal)
                views.setTextViewText(
                    R.id.widget_goal,
                    (if (minutes >= goal) "Goal done today" else "$minutes of $goal min today") +
                        if (streak > 0) " · $streak-day streak" else "",
                )
                views.setViewVisibility(R.id.widget_goal, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_goal, View.GONE)
            }
            return views
        }

        /** A small copy of the cover; widgets have a tight size limit for pictures. */
        private fun decodeCover(path: String): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= 300) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
