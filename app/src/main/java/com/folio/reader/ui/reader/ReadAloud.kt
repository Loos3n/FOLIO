package com.folio.reader.ui.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.folio.reader.audio.AiVoiceModel
import com.folio.reader.audio.AiVoices
import java.util.concurrent.Executors
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
    /** The text-to-speech engine to start with (null = automatic). */
    engine: String? = null,
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
    /** Called after [active] or [paused] changes, also while the app is in the background. */
    var onStateChange: () -> Unit = {}
    private var rateState by mutableFloatStateOf(1f)
    /** Speech speed (1 = normal). A change is heard from the current sentence on. */
    var rate: Float
        get() = rateState
        set(value) {
            if (value == rateState) return
            rateState = value
            if (canSpeak && active && !paused) {
                run++
                speak(index)
            }
        }

    companion object {
        /** Sentences kept queued beyond the one being spoken. */
        private const val AHEAD = 2
        /** Speech Services by Google, whose voices sound the most natural on most phones. */
        const val GOOGLE_TTS = "com.google.android.tts"
        private const val PREVIEW = "preview"
        /** AI voice samples run under this, which no reading run ever has. */
        private const val PREVIEW_RUN = Int.MIN_VALUE
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

    private val appContext = context.applicationContext
    /** The engine actually speaking, once it's ready: a package name, or an AI voice's engine key. */
    var engineInUse by mutableStateOf<String?>(null)
        private set
    /** The phone engine that started last, shown when no AI voice is in use. */
    private var systemEngine: String? = null
    /** Why the chosen AI voice couldn't be used, if it couldn't. */
    var aiError by mutableStateOf<String?>(null)
        private set

    /** The AI voice model chosen, if any; reading uses it instead of the phone's engine. */
    private var aiModel: AiVoiceModel? = null
    /** The loaded model, once it's ready to speak. */
    private var ai: AiSpeech? = null
    private var aiRun = 0
    private val aiLoader = Executors.newSingleThreadExecutor()
    private val useAi get() = aiModel != null
    private val canSpeak get() = if (useAi) ai != null else ready
    /** Automatic wanted Google's voices, but Google has none downloaded for the book's language. */
    var googleMissingVoices by mutableStateOf(false)
        private set
    /** The engine in use has no downloaded voice for the book's language. */
    var languageMissing by mutableStateOf(false)
        private set
    /** Changes whenever the engine or its voices may have changed, so lists can be re-read. */
    var voicesVersion by mutableStateOf(0)
        private set

    /** The engine asked for: a package name, or null for automatic (Google's when it has the language). */
    private var enginePref: String? = engine?.takeUnless { it.startsWith(AiVoices.PREFIX) }
    /** Bumped each time the engine is recreated, so a late init from an old one is ignored. */
    private var engineRun = 0
    /** The engine last started (null = the phone's default). */
    private var requested: String? = null
    private var tts: TextToSpeech = create(wantedEngine())
    private var voiceName: String? = null

    /** A text-to-speech app on the phone. */
    data class EngineOption(val name: String, val label: String)

    /** Every text-to-speech app installed. */
    fun engines(): List<EngineOption> {
        val pm = appContext.packageManager
        return runCatching { pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0) }
            .getOrNull().orEmpty()
            .mapNotNull { it.serviceInfo }
            .distinctBy { it.packageName }
            .map { EngineOption(it.packageName, it.loadLabel(pm).toString()) }
            .sortedWith(compareBy({ it.name != GOOGLE_TTS }, { it.label.lowercase() }))
    }

    val googleInstalled: Boolean get() = engines().any { it.name == GOOGLE_TTS }

    /**
     * Picks the engine (null for automatic). Automatic uses Google's Speech Services when it's
     * installed, since its voices sound far more natural than most phones' built-in ones.
     */
    fun setEngine(name: String?) {
        val model = AiVoices.byEngine(name)
        if (model != null) {
            startAi(model)
            return
        }
        if (useAi) stopAi(resume = true)
        enginePref = name
        val want = wantedEngine()
        if (want != requested) restartEngine(want)
    }

    /** Loads an AI voice model in the background and switches reading over to it. */
    private fun startAi(model: AiVoiceModel) {
        if (aiModel == model) return
        val resumeAt = if (active && !paused) index else null
        releaseAi()
        aiModel = model
        aiError = null
        showEngine()
        val myRun = ++aiRun
        if (resumeAt != null) {
            run++
            pendingStart = { speak(resumeAt) }
        }
        aiLoader.execute {
            val loaded = runCatching {
                check(AiVoices.isInstalled(appContext, model)) { "This AI voice isn't downloaded" }
                AiVoices.load(appContext, model)
            }
            main.post {
                if (myRun != aiRun) {
                    loaded.getOrNull()?.let { runCatching { it.release() } }
                    return@post
                }
                loaded.onSuccess { ai = AiSpeech(it, aiListener) }.onFailure {
                    aiError = "Couldn't start the AI voice, so your phone's voice is reading instead."
                    aiModel = null
                }
                showEngine()
                voicesVersion++
                languageMissing = if (useAi) !aiSpeaksLanguage() else ready && !hasLanguage()
                runPending()
                previewIfReady()
            }
        }
    }

    /** Goes back to the phone's own engine. */
    private fun stopAi(resume: Boolean) {
        val resumeAt = if (resume && active && !paused) index else null
        releaseAi()
        aiModel = null
        showEngine()
        voicesVersion++
        if (resumeAt != null) {
            run++
            speak(resumeAt)
        }
    }

    private fun releaseAi() {
        aiRun++
        ai?.shutdown()
        ai = null
    }

    private fun aiSpeaksLanguage() = (language?.language ?: "en") == (aiModel?.language ?: "en")

    private fun showEngine() {
        engineInUse = aiModel?.engineKey ?: systemEngine
    }

    /** Runs the start that was waiting for an engine to be ready. */
    private fun runPending() {
        val start = pendingStart
        pendingStart = null
        start?.invoke()
    }

    private val aiListener = object : AiSpeech.Listener {
        override fun onStart(run: Int, index: Int) {
            if (run != this@ReadAloud.run) return
            this@ReadAloud.index = index
            onSentence(index)
        }

        override fun onWord(run: Int, index: Int, start: Int, end: Int) {
            if (run == this@ReadAloud.run) this@ReadAloud.onWord(index, start, end)
        }

        override fun onDone(run: Int, index: Int) {
            if (run != this@ReadAloud.run || !active || paused) return
            if (index >= sentences.lastIndex) onFinished()
        }

        override fun onError(run: Int) {
            if (run != this@ReadAloud.run) return
            aiError = "The AI voice stopped unexpectedly."
            stop()
        }
    }

    /** Re-checks the engines, e.g. after coming back from installing Google's or downloading a voice. */
    fun refresh() {
        googleMissingVoices = false
        val want = wantedEngine()
        if (want != requested) restartEngine(want) else if (ready) {
            applyVoice()
            voicesVersion++
        }
    }

    private fun wantedEngine(): String? {
        val installed = engines().map { it.name }
        return enginePref?.takeIf { it in installed } ?: GOOGLE_TTS.takeIf { enginePref == null && it in installed && !googleMissingVoices }
    }

    private fun defaultEngine(): String? = runCatching { tts.defaultEngine }.getOrNull()

    private fun create(engine: String?): TextToSpeech {
        val myRun = ++engineRun
        requested = engine
        val listener = TextToSpeech.OnInitListener { status -> onInit(status, myRun, engine) }
        return if (engine == null) TextToSpeech(appContext, listener) else TextToSpeech(appContext, listener, engine)
    }

    private fun restartEngine(engine: String?) {
        // An AI voice keeps reading while the phone engine behind it restarts.
        val resumeAt = if (!useAi && active && !paused) index else null
        if (!useAi) run++
        ready = false
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        tts = create(engine)
        tts.setOnUtteranceProgressListener(progress)
        if (resumeAt != null) {
            run++
            pendingStart = { speak(resumeAt) }
        }
    }

    /** Whether the engine has a downloaded voice for the book's language. */
    private fun hasLanguage(): Boolean {
        val lang = language ?: Locale.getDefault()
        return runCatching { tts.isLanguageAvailable(lang) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
    }

    /** Automatic picked Google but it can't speak this language yet: fall back to the phone's own engine. */
    private fun fallBackIfGoogleCantSpeak(): Boolean {
        if (enginePref != null || requested != GOOGLE_TTS || hasLanguage()) return false
        googleMissingVoices = true
        restartEngine(null)
        return true
    }

    /** A voice the reader can pick, with a readable name and what sets it apart. */
    data class VoiceOption(val name: String, val label: String, val network: Boolean, val detail: String? = null)

    /** Installed voices for the book's language (all languages if it has none), best first. */
    fun voices(): List<VoiceOption> {
        aiModel?.let { m ->
            val count = ai?.speakers ?: m.speakers.size
            return (0 until count).map { VoiceOption(it.toString(), m.speakers.getOrNull(it) ?: "Voice ${it + 1}", false, "AI voice") }
        }
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

    /**
     * Says a short sample in the named voice (null for automatic), pausing any reading first.
     * Uses the sentence being read when there is one, so the sample is in the book's language.
     */
    fun preview(name: String?) {
        ai?.let { speech ->
            if (active && !paused) pause()
            val sample = sentences.getOrNull(index)?.trim()?.takeIf { it.length in 20..240 && aiSpeaksLanguage() }
                ?: "This is how I'll sound reading your book."
            speech.start(PREVIEW_RUN, listOf(clean(sample)), 0, name?.toIntOrNull() ?: 0, rateState)
            return
        }
        if (useAi || !ready) return
        if (active && !paused) pause()
        val voice = name?.let { n -> runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == n } } ?: bestVoice()
        if (voice != null) runCatching { tts.voice = voice }
        previewing = true
        val sample = sentences.getOrNull(index)?.trim()?.takeIf { it.length in 20..240 }
            ?: if ((language ?: Locale.getDefault()).language == "en") "This is how I'll sound reading your book." else null
        if (sample == null) {
            applyVoice()
            return
        }
        tts.setSpeechRate(rateState)
        tts.speak(clean(sample), TextToSpeech.QUEUE_FLUSH, null, PREVIEW)
    }

    /** A voice to play a sample of once the engine just chosen is ready. */
    private var pendingPreview: String? = null
    private var previewWanted = false

    /** Plays a sample of [name] as soon as the voice just picked is ready to speak. */
    fun previewSoon(name: String?) {
        pendingPreview = name
        previewWanted = true
        // Give the new choice a moment to reach setEngine/setVoice first.
        main.postDelayed({ previewIfReady() }, 250)
    }

    private fun previewIfReady() {
        if (!previewWanted || !canSpeak) return
        previewWanted = false
        preview(pendingPreview)
    }

    /** Uses the named voice from the next sentence on (null for the default). */
    fun setVoice(name: String?) {
        voiceName = name
        if (useAi) {
            if (ai != null && active && !paused) {
                run++
                speak(index)
            }
            return
        }
        if (!ready) return
        applyVoice()
        if (active && !paused) {
            run++
            speak(index)
        }
    }

    /** A sample is playing in a voice that may not be the chosen one. */
    private var previewing = false

    private fun applyVoice() {
        previewing = false
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

    private fun onInit(status: Int, myRun: Int, engine: String?) {
        main.post {
            if (myRun != engineRun) return@post
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                available = true
                systemEngine = engine ?: defaultEngine()
                showEngine()
                if (fallBackIfGoogleCantSpeak()) return@post
                if (!useAi) languageMissing = !hasLanguage()
                applyVoice()
                voicesVersion++
                if (!useAi) {
                    runPending()
                    previewIfReady()
                }
                return@post
            } else if (engine != null) {
                // The chosen engine wouldn't start: use the phone's default instead.
                if (enginePref == null) googleMissingVoices = true
                val start = pendingStart
                restartEngine(null)
                if (start != null) pendingStart = start
                return@post
            } else {
                available = useAi
                if (!useAi) {
                    active = false
                    pendingStart = null
                }
            }
        }
    }

    private val progress = object : UtteranceProgressListener() {
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
            if (utteranceId == PREVIEW) {
                main.post { applyVoice() }
                return
            }
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
            if (utteranceId == PREVIEW) {
                main.post { applyVoice() }
                return
            }
            val (r, _) = parse(utteranceId) ?: return
            main.post { if (r == run) stop() }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(progress)
        AiVoices.byEngine(engine)?.let { startAi(it) }
    }

    fun setLanguage(tag: String?) {
        val locale = tag?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) } ?: return
        language = locale
        if (useAi) languageMissing = !aiSpeaksLanguage()
        if (ready && !fallBackIfGoogleCantSpeak()) {
            if (!useAi) languageMissing = !hasLanguage()
            applyVoice()
            voicesVersion++
        }
    }

    /** Starts reading [texts] from the first sentence. */
    fun play(texts: List<String>) {
        run++
        sentences = texts
        active = true
        paused = false
        speak(0)
        onStateChange()
    }

    /** Jumps [delta] sentences back or forward in what's being read. */
    fun skip(delta: Int) {
        if (!active || sentences.isEmpty()) return
        run++
        paused = false
        speak((index + delta).coerceIn(0, sentences.lastIndex))
        onStateChange()
    }

    /** Shows the player without speaking yet, e.g. while the next chapter loads. */
    fun standBy() {
        run++
        active = true
        paused = false
        ai?.stop()
        if (ready) tts.stop()
        onStateChange()
    }

    fun pause() {
        if (!active || paused) return
        run++
        paused = true
        ai?.stop()
        if (ready) tts.stop()
        onStateChange()
    }

    fun resume() {
        if (!active || !paused) return
        run++
        paused = false
        speak(index)
        onStateChange()
    }

    fun stop() {
        run++
        active = false
        paused = false
        pendingStart = null
        ai?.stop()
        if (ready) tts.stop()
        onStateChange()
    }

    fun shutdown() {
        stop()
        releaseAi()
        aiLoader.shutdown()
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
        if (!canSpeak) {
            // The engine is still starting (e.g. just switched): speak once it's ready.
            pendingStart = { speak(i) }
            return
        }
        ai?.let { speech ->
            speech.start(run, sentences.map(::clean), i, voiceName?.toIntOrNull() ?: 0, rateState)
            queued = sentences.lastIndex
            return
        }
        if (previewing) applyVoice()
        tts.setSpeechRate(rateState)
        tts.speak(clean(sentences[i]), TextToSpeech.QUEUE_FLUSH, null, "$run:$i")
        queued = i
        queueAhead()
    }

    private fun queueAhead() {
        if (useAi) return
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

/** Opens the screens where the reader gets better voices. */
object VoiceSetup {
    /** Speech Services by Google on the Play Store. */
    fun getGoogle(context: Context) {
        val id = ReadAloud.GOOGLE_TTS
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(store) }.recoverCatching { context.startActivity(web) }
    }

    /** The engine's own voice downloads, or the phone's text-to-speech settings if it has none. */
    fun downloadVoices(context: Context, engine: String?) {
        val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (engine != null) install.setPackage(engine)
        val settings = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(install) }.recoverCatching { context.startActivity(settings) }
    }
}
