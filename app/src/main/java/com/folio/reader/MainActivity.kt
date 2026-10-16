package com.folio.reader

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import com.folio.reader.ui.FolioNavHost
import com.folio.reader.ui.theme.FolioTheme

class MainActivity : ComponentActivity() {

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
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) intent?.getStringExtra("open_book")?.let { openBook.value = it }
    }
}

/** Lets the reader screen claim the volume keys for turning pages while it's open. */
object PageKeys {
    class Handler(val enabled: () -> Boolean, val turn: (forward: Boolean) -> Unit)

    @Volatile var handler: Handler? = null
}
