package com.folio.reader.audio

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.folio.reader.FolioApp
import com.folio.reader.data.AudioInfo
import com.folio.reader.data.Book
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope

/** What the listening screen and the library's mini player show. */
data class ListenState(
    val bookId: String? = null,
    val playing: Boolean = false,
    /** Waiting for audio, e.g. right after a seek. */
    val loading: Boolean = false,
    val chapter: Int = 0,
    /** From the start of the book, in milliseconds. */
    val positionMs: Long = 0,
    val totalMs: Long = 0,
    val speed: Float = 1f,
    val sleepEndsAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val error: String? = null,
)

/**
 * The one audiobook player for the whole app. The listening screen drives it directly, and
 * [PlaybackService] shares it with the notification, lock screen and headphone buttons,
 * so playback carries on with the screen off or the app in the background.
 * Everything here runs on the main thread.
 */
class AudioPlayer(private val app: FolioApp) {
    private val scope = MainScope()
    private var exo: ExoPlayer? = null
    private var info: AudioInfo? = null
    private var ticker: Job? = null
    private var lastSave = 0L
    private var lastStats = 0L
    private var sleepChapter = -1

    private val _state = MutableStateFlow(ListenState())
    val state: StateFlow<ListenState> = _state.asStateFlow()

    init {
        // Keep track lengths and chapters current if the book changes in the library.
        scope.launch {
            app.library.books.collect { books ->
                val id = _state.value.bookId ?: return@collect
                val audio = books.firstOrNull { it.id == id }?.audio
                if (audio == null) {
                    stop() // removed from the library while loaded
                } else {
                    info = audio.copy(track = info?.track ?: 0, positionMs = info?.positionMs ?: 0)
                }
            }
        }
    }

    private fun player(): ExoPlayer {
        exo?.let { return it }
        check(Looper.myLooper() == Looper.getMainLooper())
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(BACK_MS)
            .setSeekForwardIncrementMs(FORWARD_MS)
            .build()
        p.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                refresh()
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    if (player.isPlaying) startTicker() else save()
                }
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) learnDurations()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) save()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(
                    playing = false,
                    error = "Couldn't play this audiobook. Its files may have been moved or deleted.",
                )
            }
        })
        exo = p
        return p
    }

    /** The player as the notification and lock screen see it: skip buttons move between chapters. */
    fun sessionPlayer(): Player = object : ForwardingPlayer(player()) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD)
            .build()

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun seekToNext() = nextChapter()
        override fun seekToPrevious() = previousChapter()
        override fun seekToNextMediaItem() = nextChapter()
        override fun seekToPreviousMediaItem() = previousChapter()
    }

    /** Loads [book] ready to play from where the listener left off (does nothing if it's already loaded). */
    fun open(book: Book) {
        val audio = book.audio ?: return
        if (_state.value.bookId == book.id && exo?.mediaItemCount == audio.tracks.size) return
        if (_state.value.bookId != null) save()
        val cover = app.library.coverFile(book)?.let { Uri.fromFile(it) }
        val items = audio.tracks.mapIndexed { i, t ->
            MediaItem.Builder()
                .setUri(t.uri)
                .setMediaId("${book.id}/$i")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .setArtist(book.author)
                        .setAlbumTitle(book.title)
                        .setArtworkUri(cover)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                        .build(),
                )
                .build()
        }
        info = audio
        sleepChapter = -1
        _state.value = ListenState(bookId = book.id, speed = audio.speed)
        val p = player()
        val track = audio.track.coerceIn(0, items.lastIndex)
        p.setMediaItems(items, track, audio.positionMs)
        p.setPlaybackSpeed(audio.speed)
        p.prepare()
        refresh()
        scope.launch { app.library.markOpened(book.id) }
    }

    fun play() {
        val p = exo ?: return
        _state.value = _state.value.copy(error = null)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) seekTo(0)
        // The service keeps playback going in the background and shows the notification.
        // (A plain start while the app is open; the service moves itself to the foreground as it plays.)
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
        p.play()
    }

    fun pause() {
        exo?.pause()
    }

    fun toggle() = if (exo?.playWhenReady == true) pause() else play()

    /** Goes to [absoluteMs] from the start of the book. */
    fun seekTo(absoluteMs: Long) {
        val a = info ?: return
        val (track, pos) = a.locate(absoluteMs.coerceIn(0, a.totalMs))
        exo?.seekTo(track, pos)
        refresh()
    }

    fun skip(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    fun openChapter(index: Int) {
        val a = info ?: return
        val c = a.chapters.getOrNull(index) ?: return
        seekTo(a.absoluteOf(c))
    }

    fun nextChapter() {
        val a = info ?: return
        val next = _state.value.chapter + 1
        if (next < a.chapters.size) openChapter(next)
    }

    /** To the start of this chapter, or the one before if it has only just begun. */
    fun previousChapter() {
        val a = info ?: return
        val s = _state.value
        val start = a.chapters.getOrNull(s.chapter)?.let { a.absoluteOf(it) } ?: 0
        if (s.positionMs - start > 3_000 || s.chapter == 0) seekTo(start) else openChapter(s.chapter - 1)
    }

    fun setSpeed(speed: Float) {
        exo?.setPlaybackSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
        save()
    }

    /** Pauses after [minutes], or at the end of this chapter, or never (both off). */
    fun setSleep(minutes: Int?, chapterEnd: Boolean) {
        sleepChapter = if (chapterEnd) _state.value.chapter else -1
        _state.value = _state.value.copy(
            sleepEndsAt = minutes?.let { System.currentTimeMillis() + it * 60_000L },
            sleepAtChapterEnd = chapterEnd,
        )
        startTicker()
    }

    /** Unloads the book and stops the notification. */
    fun stop() {
        save()
        exo?.stop()
        exo?.clearMediaItems()
        info = null
        _state.value = ListenState()
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        lastStats = SystemClock.elapsedRealtime()
        ticker = scope.launch {
            while (true) {
                refresh()
                val p = exo ?: break
                val s = _state.value
                val now = SystemClock.elapsedRealtime()
                if (p.isPlaying) {
                    if (now - lastSave > 5_000) save()
                    if (now - lastStats >= 15_000) {
                        s.bookId?.let { app.stats.record(it, (now - lastStats) / 1000, 0) }
                        lastStats = now
                    }
                } else {
                    lastStats = now
                }
                // Sleep timer.
                val ends = s.sleepEndsAt
                if (ends != null && System.currentTimeMillis() >= ends) {
                    p.pause()
                    _state.value = _state.value.copy(sleepEndsAt = null)
                }
                if (s.sleepAtChapterEnd && sleepChapter >= 0 && s.chapter != sleepChapter && p.isPlaying) {
                    p.pause()
                    openChapter(s.chapter) // start the next one from its beginning next time
                    sleepChapter = -1
                    _state.value = _state.value.copy(sleepAtChapterEnd = false)
                }
                if (!p.isPlaying && _state.value.sleepEndsAt == null && !_state.value.sleepAtChapterEnd) break
                delay(500)
            }
        }
    }

    private fun refresh() {
        val p = exo ?: return
        val a = info ?: return
        val track = p.currentMediaItemIndex.coerceAtLeast(0)
        val pos = p.currentPosition.coerceAtLeast(0)
        _state.value = _state.value.copy(
            playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED,
            loading = p.playbackState == Player.STATE_BUFFERING,
            chapter = a.chapterAt(track, pos),
            positionMs = a.absolute(track, pos),
            totalMs = a.totalMs,
            speed = p.playbackParameters.speed,
        )
    }

    private fun save() {
        val p = exo ?: return
        val id = _state.value.bookId ?: return
        if (p.mediaItemCount == 0) return
        lastSave = SystemClock.elapsedRealtime()
        val track = p.currentMediaItemIndex
        val pos = p.currentPosition.coerceAtLeast(0)
        val speed = p.playbackParameters.speed
        info = info?.copy(track = track, positionMs = pos, speed = speed)
        app.appScope.launch { app.library.saveAudioPosition(id, track, pos, speed) }
    }

    /** Some files don't state their length up front; take it from the player once it knows. */
    private fun learnDurations() {
        val p = exo ?: return
        val id = _state.value.bookId ?: return
        val a = info ?: return
        val timeline = p.currentTimeline
        val window = androidx.media3.common.Timeline.Window()
        for (i in 0 until minOf(timeline.windowCount, a.tracks.size)) {
            val d = timeline.getWindow(i, window).durationMs
            if (d != C.TIME_UNSET && d > 0 && kotlin.math.abs(d - a.tracks[i].durationMs) > 1_000) {
                app.appScope.launch { app.library.setTrackDuration(id, i, d) }
            }
        }
    }

    companion object {
        const val BACK_MS = 15_000L
        const val FORWARD_MS = 30_000L
    }
}
