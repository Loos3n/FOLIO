package com.folio.reader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.folio.reader.FolioApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** Whether automatic backup is on, where it saves, and how the last one went. */
data class AutoBackupState(
    val uri: Uri? = null,
    /** A name for where it saves, e.g. the file name. */
    val place: String? = null,
    val lastSavedAt: Long = 0L,
    val lastError: String? = null,
)

/**
 * Saves a small backup (places, bookmarks, highlights, notes, word list, fonts, stats and
 * settings, but not the book files) once a day to one file the reader picked, replacing it
 * each time, so it never piles up in Google Drive or on the phone.
 */
class AutoBackup(private val context: Context) {
    companion object {
        private const val WORK = "auto-backup"
        private const val NOW = "auto-backup-now"
        const val FILE_NAME = "Folio automatic backup.zip"
    }

    private val prefs = context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AutoBackupState> = _state.asStateFlow()

    /** Starts saving to [uri] (a file the reader just created) every day, and once right away. */
    fun turnOn(uri: Uri, place: String?) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        _state.value.uri?.takeIf { it != uri }?.let(::release)
        prefs.edit().putString("uri", uri.toString()).putString("place", place).putLong("lastSavedAt", 0L).remove("lastError").apply()
        _state.value = read()
        schedule()
        runNow()
    }

    fun turnOff() {
        _state.value.uri?.let(::release)
        prefs.edit().clear().apply()
        _state.value = read()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun runNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    /** Keeps the daily save scheduled while it's on (called at app start). */
    fun schedule() {
        if (_state.value.uri == null) return
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun saved() {
        prefs.edit().putLong("lastSavedAt", System.currentTimeMillis()).remove("lastError").apply()
        _state.value = read()
    }

    internal fun failed(message: String) {
        prefs.edit().putString("lastError", message).apply()
        _state.value = read()
    }

    private fun release(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun read() = AutoBackupState(
        uri = prefs.getString("uri", null)?.let(Uri::parse),
        place = prefs.getString("place", null),
        lastSavedAt = prefs.getLong("lastSavedAt", 0L),
        lastError = prefs.getString("lastError", null),
    )
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FolioApp
        val uri = app.autoBackup.state.value.uri ?: return Result.success()
        val backup = Backup(app, app.library, app.settings, app.stats, app.words, app.fonts)
        return runCatching { backup.write(uri, bookFiles = false) }.fold(
            onSuccess = {
                app.autoBackup.saved()
                Result.success()
            },
            onFailure = {
                app.autoBackup.failed(
                    if (it is SecurityException) "Folio can no longer save to that file. Choose where to save again."
                    else "Couldn't save the last backup. ${it.message ?: ""}".trim(),
                )
                if (runAttemptCount < 2 && it !is SecurityException) Result.retry() else Result.failure()
            },
        )
    }
}
