package com.folio.reader.ui.reader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Speaks sentences with an on-phone AI voice. Each sentence is made into audio a couple ahead
 * of the one playing, so speech flows on without gaps. The model doesn't say when each word
 * is spoken, so word highlights are spread over the sentence by word length.
 * Callbacks arrive on the main thread, tagged with the run they were started with.
 */
class AiSpeech(private val tts: OfflineTts, private val listener: Listener) {
    interface Listener {
        fun onStart(run: Int, index: Int)
        fun onWord(run: Int, index: Int, start: Int, end: Int)
        fun onDone(run: Int, index: Int)
        fun onError(run: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    /** One model can only make one sentence at a time, so all making happens on one thread. */
    private val maker = Executors.newSingleThreadExecutor { r -> Thread(r, "folio-ai-voice").apply { priority = Thread.MAX_PRIORITY } }
    private val sampleRate = runCatching { tts.sampleRate() }.getOrDefault(24_000)
    val speakers: Int = runCatching { tts.numSpeakers() }.getOrDefault(1).coerceAtLeast(1)
    @Volatile private var job: Job? = null

    private class Clip(val index: Int, val text: String, val samples: FloatArray)

    /** A sentence's place in the audio track, in frames. */
    private class Span(val index: Int, val text: String, val start: Long, val end: Long) {
        var started = false
        var done = false
        var word = -1
        val words: List<IntRange> = Regex("\\S+").findAll(text).map { it.range }.toList()
    }

    private inner class Job(
        val run: Int,
        val texts: List<String>,
        val from: Int,
        val speaker: Int,
        val speed: Float,
    ) {
        @Volatile var cancelled = false
        val clips = LinkedBlockingQueue<Clip>(AHEAD)
        val spans = java.util.concurrent.CopyOnWriteArrayList<Span>()
        @Volatile var track: AudioTrack? = null
        /** Total frames written once every sentence is in, else -1. */
        @Volatile var total = -1L
    }

    /** Speaks [texts] from [from] on, stopping anything already playing. */
    fun start(run: Int, texts: List<String>, from: Int, speaker: Int, speed: Float) {
        stop()
        val j = Job(run, texts, from, speaker.coerceIn(0, speakers - 1), speed)
        job = j
        maker.execute { make(j) }
        thread(name = "folio-ai-play") { play(j) }
        main.post(Poll(j))
    }

    fun stop() {
        val j = job ?: return
        job = null
        j.cancelled = true
        j.clips.clear()
        runCatching {
            j.track?.pause()
            j.track?.flush()
        }
    }

    fun shutdown() {
        stop()
        maker.execute { runCatching { tts.release() } }
        maker.shutdown()
    }

    private fun make(j: Job) {
        for (i in j.from until j.texts.size) {
            if (j.cancelled) return
            val text = j.texts[i]
            val samples = if (text.isBlank()) FloatArray(0) else try {
                tts.generate(text, sid = j.speaker, speed = j.speed).samples
            } catch (e: Throwable) {
                main.post { if (!j.cancelled) listener.onError(j.run) }
                return
            }
            if (!offer(j, Clip(i, text, samples))) return
        }
        offer(j, Clip(END, "", FloatArray(0)))
    }

    private fun offer(j: Job, clip: Clip): Boolean {
        while (!j.cancelled) if (j.clips.offer(clip, 100, TimeUnit.MILLISECONDS)) return true
        return false
    }

    private fun play(j: Job) {
        val track = try {
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(min, sampleRate * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            main.post { if (!j.cancelled) listener.onError(j.run) }
            return
        }
        j.track = track
        try {
            if (j.cancelled) return
            track.play()
            val gap = FloatArray(sampleRate * 18 / 100) // a short breath between sentences
            var written = 0L
            while (!j.cancelled) {
                val clip = j.clips.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (clip.index == END) {
                    j.total = written
                    break
                }
                j.spans.add(Span(clip.index, clip.text, written, written + clip.samples.size))
                if (!write(j, track, clip.samples)) break
                written += clip.samples.size
                if (!write(j, track, gap)) break
                written += gap.size
            }
            // Let the last sentence finish playing before letting go of the speaker.
            while (!j.cancelled && j.total >= 0 && head(track) < j.total) Thread.sleep(50)
        } catch (_: InterruptedException) {
        } finally {
            j.track = null
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun write(j: Job, track: AudioTrack, samples: FloatArray): Boolean {
        var off = 0
        while (off < samples.size) {
            if (j.cancelled) return false
            val n = track.write(samples, off, minOf(4096, samples.size - off), AudioTrack.WRITE_BLOCKING)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun head(track: AudioTrack): Long = runCatching { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)

    /** Follows the playback position, reporting sentences and words as they're heard. */
    private inner class Poll(val j: Job) : Runnable {
        override fun run() {
            if (j.cancelled) return
            val track = j.track
            val pos = if (track != null) head(track) else -1L
            for (s in j.spans) {
                if (s.done) continue
                if (pos < s.start) break
                if (!s.started) {
                    s.started = true
                    listener.onStart(j.run, s.index)
                }
                if (pos >= s.end) {
                    s.done = true
                    listener.onDone(j.run, s.index)
                    if (j.cancelled) return
                    continue
                }
                val w = wordAt(s, (pos - s.start).toFloat() / (s.end - s.start).coerceAtLeast(1))
                if (w != s.word && w >= 0) {
                    s.word = w
                    val r = s.words[w]
                    listener.onWord(j.run, s.index, r.first, r.last + 1)
                }
                break
            }
            if (j.cancelled) return
            val total = j.total
            if (total >= 0 && j.spans.all { it.done }) return
            main.postDelayed(this, 40)
        }
    }

    /** The word playing [fraction] of the way through a sentence, weighting longer words more. */
    private fun wordAt(s: Span, fraction: Float): Int {
        if (s.words.isEmpty()) return -1
        val weights = s.words.map { it.last - it.first + 2 }
        val target = fraction * weights.sum()
        var acc = 0f
        weights.forEachIndexed { i, w ->
            acc += w
            if (target < acc) return i
        }
        return s.words.lastIndex
    }

    private companion object {
        /** Sentences made ahead of the one playing. */
        const val AHEAD = 2
        const val END = -1
    }
}
