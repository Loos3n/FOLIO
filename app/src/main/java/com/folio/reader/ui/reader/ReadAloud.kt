package com.folio.reader.ui.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Reads a chapter aloud one sentence at a time with the phone's text-to-speech voice,
 * reporting each sentence and (where the voice supports it) each word as it is spoken.
 * All callbacks arrive on the main thread.
 */
class ReadAloud(
    context: Context,
    private val onSentence: (index: Int) -> Unit,
    private val onWord: (index: Int, start: Int, end: Int) -> Unit,
    /** Every sentence handed to [play] has been spoken. */
    private val onFinished: () -> Unit,
) {
    /** True from [play] until [stop]; the player stays on screen while this is true. */
    var active by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    /** False if the phone has no working text-to-speech engine. */
    var available by mutableStateOf(true)
        private set
    var rate by mutableFloatStateOf(1f)

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingStart: (() -> Unit)? = null
    private var sentences: List<String> = emptyList()
    private var index = 0
    /** Bumped on every start/stop, so callbacks from an earlier run are ignored. */
    private var run = 0
    private var language: Locale? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        main.post {
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                language?.let { runCatching { tts.setLanguage(it) } }
                pendingStart?.invoke()
            } else {
                available = false
                active = false
            }
            pendingStart = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val (r, i) = parse(utteranceId) ?: return
                main.post { if (r == run) onSentence(i) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val (r, i) = parse(utteranceId) ?: return
                main.post { if (r == run) onWord(i, start, end) }
            }

            override fun onDone(utteranceId: String) {
                val (r, i) = parse(utteranceId) ?: return
                main.post { if (r == run && active && !paused) speak(i + 1) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                val (r, _) = parse(utteranceId) ?: return
                main.post { if (r == run) stop() }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                val (r, _) = parse(utteranceId) ?: return
                main.post { if (r == run) stop() }
            }
        })
    }

    fun setLanguage(tag: String?) {
        val locale = tag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: return
        language = locale
        if (ready) runCatching { tts.setLanguage(locale) }
    }

    /** Starts reading [texts] from the first sentence. */
    fun play(texts: List<String>) {
        run++
        sentences = texts
        active = true
        paused = false
        if (ready) speak(0) else pendingStart = { speak(0) }
    }

    /** Shows the player without speaking yet, e.g. while the next chapter loads. */
    fun standBy() {
        run++
        active = true
        paused = false
        if (ready) tts.stop()
    }

    fun pause() {
        if (!active || paused) return
        run++
        paused = true
        tts.stop()
    }

    fun resume() {
        if (!active || !paused) return
        run++
        paused = false
        speak(index)
    }

    fun stop() {
        run++
        active = false
        paused = false
        pendingStart = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun speak(i: Int) {
        if (!active) return
        if (i >= sentences.size) {
            onFinished()
            return
        }
        index = i
        tts.setSpeechRate(rate)
        tts.speak(sentences[i], TextToSpeech.QUEUE_FLUSH, null, "$run:$i")
    }

    private fun parse(id: String): Pair<Int, Int>? {
        val r = id.substringBefore(':').toIntOrNull() ?: return null
        val i = id.substringAfter(':').toIntOrNull() ?: return null
        return r to i
    }
}
