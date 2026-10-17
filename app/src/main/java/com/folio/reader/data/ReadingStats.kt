package com.folio.reader.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Reading done on one day. */
data class DayStats(val seconds: Long = 0, val pages: Int = 0)

data class StatsSnapshot(
    /** Keyed by ISO date, e.g. "2026-10-17". */
    val days: Map<String, DayStats> = emptyMap(),
    /** Seconds spent in each book, by book id. */
    val books: Map<String, Long> = emptyMap(),
)

/**
 * Keeps a running tally of reading time and pages turned per day, saved to a small JSON
 * file a few seconds after each change.
 */
class ReadingStats(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "stats.json")
    private val _stats = MutableStateFlow(load())
    val stats: StateFlow<StatsSnapshot> = _stats.asStateFlow()
    private var saveJob: Job? = null

    fun record(bookId: String, seconds: Long, pages: Int) {
        if (seconds <= 0 && pages <= 0) return
        val today = LocalDate.now().toString()
        _stats.update { s ->
            val day = s.days[today] ?: DayStats()
            s.copy(
                days = s.days + (today to DayStats(day.seconds + seconds, day.pages + pages)),
                books = s.books + (bookId to (s.books[bookId] ?: 0L) + seconds),
            )
        }
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(3_000)
            save(_stats.value)
        }
    }

    private fun load(): StatsSnapshot = runCatching {
        if (!file.exists()) return StatsSnapshot()
        val o = JSONObject(file.readText())
        val days = HashMap<String, DayStats>()
        o.optJSONObject("days")?.let { d ->
            for (k in d.keys()) d.optJSONObject(k)?.let { days[k] = DayStats(it.optLong("s"), it.optInt("p")) }
        }
        val books = HashMap<String, Long>()
        o.optJSONObject("books")?.let { b -> for (k in b.keys()) books[k] = b.optLong(k) }
        StatsSnapshot(days, books)
    }.getOrDefault(StatsSnapshot())

    private fun save(s: StatsSnapshot) {
        val days = JSONObject()
        s.days.forEach { (k, v) -> days.put(k, JSONObject().put("s", v.seconds).put("p", v.pages)) }
        val books = JSONObject()
        s.books.forEach { (k, v) -> books.put(k, v) }
        val tmp = File(file.parentFile, "stats.json.tmp")
        tmp.writeText(JSONObject().put("days", days).put("books", books).toString())
        tmp.renameTo(file)
    }
}
