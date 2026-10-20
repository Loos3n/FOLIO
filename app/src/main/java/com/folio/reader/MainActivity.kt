package com.folio.reader

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.folio.reader.data.Backup
import com.folio.reader.ui.library.LibraryViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import com.folio.reader.ui.FolioNavHost
import com.folio.reader.ui.theme.FolioTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_AUDIO = "com.folio.reader.OPEN_AUDIO"
        /** From the home-screen widget: the book to carry on with. */
        const val EXTRA_OPEN_BOOK = "com.folio.reader.OPEN_BOOK"
    }

    /** EPUBs handed to us by other apps ("Open with" / "Share"), waiting to be imported. */
    private val incoming = MutableStateFlow<Uri?>(null)

    /** Debug builds only: a book id to open straight away (used by the CI screenshot check). */
    private val openBook = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FolioTheme {
                FolioNavHost(incoming = incoming, openBook = openBook)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handler = PageKeys.handler
        val code = event.keyCode
        if (handler != null && handler.enabled() &&
            (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler.turn(code == KeyEvent.KEYCODE_VOLUME_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) incoming.value = uri
        // From the playback notification: back to the audiobook that's playing.
        if (intent?.getBooleanExtra(EXTRA_OPEN_AUDIO, false) == true) {
            (application as FolioApp).audio.state.value.bookId?.let { openBook.value = it }
        }
        intent?.getStringExtra(EXTRA_OPEN_BOOK)?.let { id ->
            if ((application as FolioApp).library.get(id) != null) openBook.value = id
        }
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            intent?.getStringExtra("open_book")?.let { openBook.value = it }
            // A file inside the app's own storage, imported as if picked (used by the CI check).
            intent?.getStringExtra("import_file")?.let { incoming.value = Uri.fromFile(java.io.File(filesDir, it)) }
            // Back up to, or restore from, a file in the app's own storage (used by the CI check).
            val app = application as FolioApp
            val backup = Backup(app, app.library, app.settings, app.stats)
            intent?.getStringExtra("backup_to")?.let { name ->
                lifecycleScope.launch {
                    val r = runCatching { backup.write(Uri.fromFile(java.io.File(filesDir, name))) }
                    Log.i("FolioBackup", "backup: $r")
                }
            }
            intent?.getStringExtra("restore_from")?.let { name ->
                if (intent.getBooleanExtra("restore_in_app", false)) {
                    LibraryViewModel.pendingRestore.value = Uri.fromFile(java.io.File(filesDir, name))
                    return@let
                }
                lifecycleScope.launch {
                    val r = runCatching { backup.restore(Uri.fromFile(java.io.File(filesDir, name))) }
                    Log.i("FolioBackup", "restore: $r")
                }
            }
        }
    }
}

/** Lets the reader screen claim the volume keys for turning pages while it's open. */
object PageKeys {
    class Handler(val enabled: () -> Boolean, val turn: (forward: Boolean) -> Unit)

    @Volatile var handler: Handler? = null
}
