package com.folio.reader

import android.app.Application
import com.folio.reader.audio.AudioPlayer
import com.folio.reader.data.LibraryRepository
import com.folio.reader.data.ReadingStats
import com.folio.reader.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class FolioApp : Application() {
    lateinit var library: LibraryRepository
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var stats: ReadingStats
        private set
    /** Created on first use, on the main thread. */
    val audio: AudioPlayer by lazy { AudioPlayer(this) }

    /** For writes that must finish even after the screen that started them is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        library = LibraryRepository(this)
        settings = SettingsRepository(this)
        stats = ReadingStats(this, appScope)
    }
}
