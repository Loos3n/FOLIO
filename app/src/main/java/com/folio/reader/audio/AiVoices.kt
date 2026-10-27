package com.folio.reader.audio

import android.content.Context
import android.os.Build
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** How an AI voice model is put together, which decides how it's loaded. */
enum class AiKind { Piper, Kokoro, Kitten }

/** A voice model that runs on the phone itself, downloaded once and then used offline. */
data class AiVoiceModel(
    val id: String,
    val name: String,
    val blurb: String,
    /** Rough download size, for the button. */
    val sizeMb: Int,
    val kind: AiKind,
    /** Names of the voices in the model, by speaker number. */
    val speakers: List<String>,
    val language: String = "en",
) {
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"
    /** How read-aloud settings name this model as an engine. */
    val engineKey get() = AiVoices.PREFIX + id
}

/**
 * The AI voices Folio can download: natural-sounding speech made on the phone by small neural
 * models (run with sherpa-onnx), so they work offline and cost nothing to use.
 */
object AiVoices {
    const val PREFIX = "folio:"

    /**
     * The speech engine's native libraries aren't in the APK, which keeps it small for anyone
     * who never uses AI voices. They're downloaded with the first voice, from a release the
     * build workflow publishes. The version must match com.k2fsa.sherpa.onnx.Tts.kt.
     */
    private const val LIBS_VERSION = "1.12.40"
    private const val LIBS_URL = "https://github.com/Loos3n/FOLIO/releases/download/speech-libs-$LIBS_VERSION/speech-libs-arm64.zip"
    /** Rough download size of the libraries, for the button. */
    private const val LIBS_MB = 11

    val models = listOf(
        AiVoiceModel(
            id = "kokoro-int8-en-v0_19",
            name = "Kokoro",
            blurb = "The most lifelike, with 11 American and British voices. Needs a recent phone.",
            sizeMb = 100,
            kind = AiKind.Kokoro,
            speakers = listOf(
                "Default (US woman)", "Bella (US woman)", "Nicole (US woman)", "Sarah (US woman)", "Sky (US woman)",
                "Adam (US man)", "Michael (US man)", "Emma (UK woman)", "Isabella (UK woman)", "George (UK man)", "Lewis (UK man)",
            ),
        ),
        AiVoiceModel(
            id = "vits-piper-en_US-lessac-medium",
            name = "Lessac",
            blurb = "A clear American voice that's quick to start on any phone.",
            sizeMb = 65,
            kind = AiKind.Piper,
            speakers = listOf("Lessac (US woman)"),
        ),
        AiVoiceModel(
            id = "vits-piper-en_GB-alba-medium",
            name = "Alba",
            blurb = "A warm Scottish voice that's quick to start on any phone.",
            sizeMb = 65,
            kind = AiKind.Piper,
            speakers = listOf("Alba (UK woman)"),
        ),
        AiVoiceModel(
            id = "kitten-nano-en-v0_1-fp16",
            name = "Kitten",
            blurb = "The smallest download, with 8 voices. Less natural than Kokoro.",
            sizeMb = 25,
            kind = AiKind.Kitten,
            speakers = listOf(
                "Voice 1 (man)", "Voice 1 (woman)", "Voice 2 (man)", "Voice 2 (woman)",
                "Voice 3 (man)", "Voice 3 (woman)", "Voice 4 (man)", "Voice 4 (woman)",
            ),
        ),
    )

    /** The speech library is built for 64-bit ARM phones, which is nearly all of them. */
    val supported: Boolean by lazy { "arm64-v8a" in Build.SUPPORTED_ABIS }

    fun byEngine(key: String?): AiVoiceModel? =
        key?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let { id -> models.firstOrNull { it.id == id } }

    private fun root(context: Context) = File(context.filesDir, "ai-voices")
    private fun dir(context: Context, model: AiVoiceModel) = File(root(context), model.id)

    private fun libsDir(context: Context) = File(context.filesDir, "speech-libs-$LIBS_VERSION")
    private fun libsReady(context: Context) = File(libsDir(context), ".ready").exists()
    private fun modelReady(context: Context, model: AiVoiceModel) = File(dir(context, model), ".ready").exists()

    /** Ready to speak: the voice and the speech engine are both on the phone. */
    fun isInstalled(context: Context, model: AiVoiceModel) = modelReady(context, model) && libsReady(context)

    /** What getting [model] still needs to download, in MB. */
    fun downloadMb(context: Context, model: AiVoiceModel): Int =
        (if (modelReady(context, model)) 0 else model.sizeMb) + (if (libsReady(context)) 0 else LIBS_MB)

    /** A download in progress: how far (0..1, null if unknown), whether it's unpacking, and whether it's the speech engine. */
    data class Progress(val fraction: Float?, val unpacking: Boolean = false, val engine: Boolean = false)

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress
    /** Ids of models on the phone; refreshed after each download or delete. */
    private val _installed = MutableStateFlow<Set<String>?>(null)
    /** The last download that failed, and why. */
    private val _failed = MutableStateFlow<Pair<String, String>?>(null)
    val failed: StateFlow<Pair<String, String>?> = _failed

    fun installed(context: Context): StateFlow<Set<String>?> {
        if (_installed.value == null) refresh(context)
        return _installed
    }

    private fun refresh(context: Context) {
        _installed.value = models.filter { isInstalled(context, it) }.map { it.id }.toSet()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    /** Downloads and unpacks [model] in the background; [progress] follows it. */
    fun download(context: Context, model: AiVoiceModel) {
        val app = context.applicationContext
        synchronized(jobs) {
            if (jobs[model.id]?.isActive == true) return
            _failed.value = null
            _progress.update { it + (model.id to Progress(0f)) }
            // A download just cancelled may still be letting go of the same files.
            val previous = jobs[model.id]
            jobs[model.id] = scope.launch {
                previous?.join()
                _progress.update { it + (model.id to Progress(0f)) }
                val archive = File(app.cacheDir, "${model.id}.tar.bz2.part")
                val staging = File(root(app), "${model.id}.unpacking")
                try {
                    libsLock.withLock { if (!libsReady(app)) getLibraries(app, model) }
                    if (modelReady(app, model)) return@launch
                    fetch(model.url, archive) { f -> _progress.update { it + (model.id to Progress(f)) } }
                    ensureActive()
                    _progress.update { it + (model.id to Progress(0f, unpacking = true)) }
                    staging.deleteRecursively()
                    unpack(model, archive, staging)
                    ensureActive()
                    val target = dir(app, model)
                    target.deleteRecursively()
                    check(staging.renameTo(target)) { "Couldn't save the voice" }
                    File(target, ".ready").writeText("1")
                } catch (e: Throwable) {
                    staging.deleteRecursively()
                    if (e !is kotlinx.coroutines.CancellationException) {
                        _failed.value = model.id to (e.message ?: "The download didn't finish")
                    }
                } finally {
                    archive.delete()
                    _progress.update { it - model.id }
                    refresh(app)
                }
            }
        }
    }

    fun cancel(model: AiVoiceModel) {
        synchronized(jobs) { jobs[model.id]?.cancel() }
    }

    fun delete(context: Context, model: AiVoiceModel) {
        cancel(model)
        dir(context, model).deleteRecursively()
        refresh(context)
    }

    /** Downloads and unpacks the speech engine's libraries. */
    private suspend fun getLibraries(context: Context, model: AiVoiceModel) {
        val zip = File(context.cacheDir, "speech-libs.zip.part")
        val dir = libsDir(context)
        try {
            fetch(LIBS_URL, zip) { f -> _progress.update { it + (model.id to Progress(f, engine = true)) } }
            _progress.update { it + (model.id to Progress(null, unpacking = true, engine = true)) }
            dir.deleteRecursively()
            dir.mkdirs()
            ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val entry = input.nextEntry ?: break
                    // Only plain library files, never paths.
                    val name = File(entry.name).name
                    if (entry.isDirectory || !name.endsWith(".so")) continue
                    File(dir, name).outputStream().use { input.copyTo(it, 64 * 1024) }
                }
            }
            check(File(dir, "libsherpa-onnx-jni.so").exists()) { "The voice engine download was incomplete" }
            File(dir, ".ready").writeText(LIBS_VERSION)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        } finally {
            zip.delete()
        }
        // Older copies of the libraries aren't needed any more.
        context.filesDir.listFiles { f -> f.name.startsWith("speech-libs-") && f != dir }?.forEach { it.deleteRecursively() }
    }

    private var librariesLoaded = false
    /** Two voices downloading at once share one copy of the libraries. */
    private val libsLock = Mutex()

    /** Loads the speech engine's libraries, dependencies first. */
    @Synchronized
    private fun loadLibraries(context: Context) {
        if (librariesLoaded) return
        val files = libsDir(context).listFiles { f -> f.name.endsWith(".so") }.orEmpty()
        val jni = files.firstOrNull { it.name == "libsherpa-onnx-jni.so" } ?: error("The voice engine isn't downloaded")
        val order = files.sortedBy { if (it.name.startsWith("libonnxruntime")) 0 else 1 }.filter { it != jni } + jni
        order.forEach { System.load(it.absolutePath) }
        librariesLoaded = true
    }

    private suspend fun fetch(url: String, into: File, onProgress: (Float?) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) error("The download failed (error ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 }
            conn.inputStream.use { input ->
                into.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = total?.let { (done * 100 / it).toInt() } ?: -1
                        if (pct != shown) {
                            shown = pct
                            onProgress(total?.let { t -> done.toFloat() / t })
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Unpacks the .tar.bz2 into [into], dropping the archive's top folder. */
    private suspend fun unpack(model: AiVoiceModel, archive: File, into: File) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        val size = archive.length().coerceAtLeast(1)
        val counted = CountingStream(BufferedInputStream(FileInputStream(archive), 256 * 1024))
        TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
            var shown = -1
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfter('/', "")
                if (name.isEmpty() || entry.isSymbolicLink || entry.isLink) continue
                val out = File(into, name)
                // Never write outside the voice's own folder.
                if (!out.canonicalPath.startsWith(base)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it, 64 * 1024) }
                }
                val pct = (counted.count * 100 / size).toInt()
                if (pct != shown) {
                    shown = pct
                    _progress.update { it + (model.id to Progress(counted.count.toFloat() / size, unpacking = true)) }
                }
            }
        }
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /** Loads [model] for speaking. Slow (a second or more), so call it off the main thread. */
    fun load(context: Context, model: AiVoiceModel): OfflineTts {
        loadLibraries(context)
        val d = dir(context, model)
        val onnx = d.listFiles { f -> f.isFile && f.extension == "onnx" }.orEmpty()
            .sortedBy { if ("int8" in it.name || "fp16" in it.name) 0 else 1 }
            .firstOrNull() ?: error("The voice files are missing")
        val tokens = File(d, "tokens.txt").path
        val data = File(d, "espeak-ng-data").takeIf { it.isDirectory }?.path.orEmpty()
        val voices = File(d, "voices.bin").path
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val modelConfig = when (model.kind) {
            AiKind.Piper -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = tokens, dataDir = data),
                numThreads = 2,
            )
            AiKind.Kokoro -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(model = onnx.path, voices = voices, tokens = tokens, dataDir = data),
                numThreads = threads,
            )
            AiKind.Kitten -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(model = onnx.path, voices = voices, tokens = tokens, dataDir = data),
                numThreads = threads,
            )
        }
        return OfflineTts(null, OfflineTtsConfig(model = modelConfig))
    }
}
