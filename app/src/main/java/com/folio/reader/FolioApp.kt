package com.folio.reader

import android.app.Application
import android.widget.Toast
import com.folio.reader.pdf.PdfPrep
import com.folio.reader.widget.ReadingWidget
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.folio.reader.audio.AudioPlayer
import com.folio.reader.data.AutoBackup
import com.folio.reader.data.LibraryRepository
import com.folio.reader.data.ReadingStats
import com.folio.reader.data.SettingsRepository
import com.folio.reader.data.UserFonts
import com.folio.reader.data.WordList
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
    lateinit var words: WordList
        private set
    lateinit var fonts: UserFonts
        private set
    lateinit var autoBackup: AutoBackup
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
        words = WordList(this, appScope)
        fonts = UserFonts(this)
        autoBackup = AutoBackup(this)
        autoBackup.schedule()
        watchForWidgetAndGoal()
        watchYearGoal()
        // Series named inside EPUBs added before Folio read them, once.
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        if (!prefs.getBoolean("seriesFilled", false)) appScope.launch {
            runCatching { library.fillSeriesFromBooks() }
            prefs.edit().putBoolean("seriesFilled", true).apply()
        }
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

    /** Says so when the last book of the year's goal is finished. */
    private fun watchYearGoal() {
        val finished = library.books.map { list ->
            val year = LocalDate.now().year
            list.count { it.finishedAt > 0 && Instant.ofEpochMilli(it.finishedAt).atZone(ZoneId.systemDefault()).year == year }
        }.distinctUntilChanged()
        val goal = settings.settings.map { it.yearlyBookGoal }.distinctUntilChanged()
        appScope.launch {
            var before: Int? = null
            combine(finished, goal) { n, target -> n to target }.collect { (n, target) ->
                val was = before
                before = n
                if (target > 0 && was != null && was < target && n >= target) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FolioApp, "You've read $target books this year. Goal reached!", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
