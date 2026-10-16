package com.folio.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FolioTheme {
                FolioNavHost(incoming = incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) incoming.value = uri
    }
}
