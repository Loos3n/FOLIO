package com.folio.reader.ui.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
    private var rateState by mutableFloatStateOf(1f)
    /** Speech speed (1 = normal). A change is heard from the current sentence on. */
    var rate: Float
        get() = rateState
        set(value) {
            if (value == rateState) return
            rateState = value
            if (ready && active && !paused) {
                run++
                speak(index)
            }
        }

    private companion object {
        /** Sentences kept queued beyond the one being spoken. */
        const val AHEAD = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingStart: (() -> Unit)? = null
    private var sentences: List<String> = emptyList()
    private var index = 0
    /** The last sentence handed to the engine; a couple are kept queued ahead. */
    private var queued = -1
    /** Bumped on every start/stop, so callbacks from an earlier run are ignored. */
    private var run = 0
    private var language: Locale? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    private var voiceName: String? = null

    /** A voice the reader can pick, with a readable name and what sets it apart. */
    data class VoiceOption(val name: String, val label: String, val network: Boolean, val detail: String? = null)

    /** Installed voices for the book's language (all languages if it has none), best first. */
    fun voices(): List<VoiceOption> {
        if (!ready) return emptyList()
        val all = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        val lang = language?.language
        val matching = all.filter { lang == null || it.locale.language == lang }.ifEmpty { all }
        val sorted = matching.sortedWith(
            compareBy<Voice>({ it.locale.displayName }, { -it.quality }, { it.isNetworkConnectionRequired }, { it.name }),
        )
        val counts = HashMap<String, Int>()
        return sorted.map { v ->
            val place = v.locale.displayName
            val n = (counts[place] ?: 0) + 1
            counts[place] = n
            val detail = listOfNotNull(
                when {
                    v.quality >= Voice.QUALITY_VERY_HIGH -> "Most natural"
                    v.quality >= Voice.QUALITY_HIGH -> "Natural"
                    v.quality <= Voice.QUALITY_LOW -> "Basic"
                    else -> null
                },
                if (v.isNetworkConnectionRequired) "needs internet" else null,
            ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { null }
            VoiceOption(v.name, "$place · voice $n", v.isNetworkConnectionRequired, detail)
        }
    }

    /** Uses the named voice from the next sentence on (null for the default). */
    fun setVoice(name: String?) {
        voiceName = name
        if (!ready) return
        applyVoice()
        if (active && !paused) {
            run++
            speak(index)
        }
    }

    private fun applyVoice() {
        val name = voiceName
        val voice = name?.let { n -> runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == n } } ?: bestVoice()
        if (voice != null) runCatching { tts.voice = voice }
        else language?.let { runCatching { tts.setLanguage(it) } }
    }

    /**
     * With no voice chosen, the most natural-sounding one installed for the book's language:
     * engines often default to a flat, low-quality voice even when better ones are on the phone.
     * Prefers the reader's own region (e.g. US English for a US phone) and voices that work offline.
     */
    private fun bestVoice(): Voice? {
        val lang = (language ?: runCatching { tts.defaultVoice?.locale }.getOrNull() ?: Locale.getDefault()).language
        val country = Locale.getDefault().country
        val candidates = runCatching { tts.voices }.getOrNull().orEmpty().filter {
            it.locale.language == lang && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features &&
                !it.isNetworkConnectionRequired
        }
        return candidates.maxWithOrNull(
            compareBy<Voice>({ it.quality }, { it.locale.country == country }, { it == runCatching { tts.defaultVoice }.getOrNull() }),
        )
    }

    private fun onInit(status: Int) {
        main.post {
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                applyVoice()
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
                main.post {
                    if (r != run) return@post
                    index = i
                    onSentence(i)
                }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val (r, i) = parse(utteranceId) ?: return
                main.post { if (r == run) onWord(i, start, end) }
            }

            override fun onDone(utteranceId: String) {
                val (r, i) = parse(utteranceId) ?: return
                main.post {
                    if (r != run || !active || paused) return@post
                    if (i >= sentences.lastIndex) onFinished() else queueAhead()
                }
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
        if (ready) applyVoice()
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

    /**
     * Starts speaking at sentence [i]. The next sentences are queued in the engine behind it,
     * so it flows from one to the next with natural pauses instead of stopping and starting.
     */
    private fun speak(i: Int) {
        if (!active) return
        if (i >= sentences.size) {
            onFinished()
            return
        }
        index = i
        tts.setSpeechRate(rateState)
        tts.speak(clean(sentences[i]), TextToSpeech.QUEUE_FLUSH, null, "$run:$i")
        queued = i
        queueAhead()
    }

    private fun queueAhead() {
        while (queued < sentences.lastIndex && queued < index + AHEAD) {
            queued++
            tts.speak(clean(sentences[queued]), TextToSpeech.QUEUE_ADD, null, "$run:$queued")
        }
    }

    /**
     * Text as the voice should hear it. Offsets must stay the same so spoken words can be
     * highlighted, so characters are swapped one for one rather than removed.
     */
    private fun clean(text: String): String = buildString(text.length) {
        for (c in text) append(
            when (c) {
                '\u00AD', '\u200B' -> ' ' // soft hyphen, zero-width space: would split a word in two
                '\n', '\r', '\t', '\u00A0' -> ' '
                else -> c
            },
        )
    }

    private fun parse(id: String): Pair<Int, Int>? {
        val r = id.substringBefore(':').toIntOrNull() ?: return null
        val i = id.substringAfter(':').toIntOrNull() ?: return null
        return r to i
    }
}
