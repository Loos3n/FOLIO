package com.folio.reader

import android.app.Application
import android.widget.Toast
import com.folio.reader.pdf.PdfPrep
import com.folio.reader.widget.ReadingWidget
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.folio.reader.audio.AudioPlayer
import com.folio.reader.data.LibraryRepository
import com.folio.reader.data.ReadingStats
import com.folio.reader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@OptIn(FlowPreview::class)
class FolioApp : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var stats: ReadingStats
        private set
    /** Created on first use, on the main thread. */
    val audio: AudioPlayer by lazy { AudioPlayer(this) }
    val pdfPrep: PdfPrep by lazy { PdfPrep(this) }

    /** For writes that must finish even after the screen that started them is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        settings = SettingsRepository(this)
        stats = ReadingStats(this, appScope)
        watchForWidgetAndGoal()
        // PDFs added or restored but not yet ready, or made by an older conversion, once things settle.
        appScope.launch {
            delay(4_000)
            pdfPrep.prepareAll()
        }
    }

    /** Keeps the home-screen widget up to date, and says so when today's reading goal is met. */
    private fun watchForWidgetAndGoal() {
        val today = stats.stats.map { it.secondsOn(LocalDate.now()) / 60 }.distinctUntilChanged()
        val goal = settings.settings.map { it.dailyGoalMinutes }.distinctUntilChanged()
        val book = library.books.map { list ->
            ReadingWidget.currentBook(list)?.let { listOf(it.id, it.title, it.author, it.coverFileName, (it.progress * 100).toInt()) }
        }.distinctUntilChanged()
        appScope.launch {
            combine(book, today, goal) { _, _, _ -> }.debounce(500).collect {
                withContext(Dispatchers.IO) { runCatching { ReadingWidget.refresh(this@FolioApp) } }
            }
        }
        appScope.launch {
            var before: Long? = null
            combine(today, goal) { minutes, target -> minutes to target }.collect { (minutes, target) ->
                val was = before
                before = minutes
                if (target > 0 && was != null && was < target && minutes >= target) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FolioApp, "Daily goal reached: $target minutes. Nice reading!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
