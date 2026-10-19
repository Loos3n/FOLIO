package com.folio.reader.audio

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Read-aloud as the media notification, lock screen and headphone buttons see it. It plays
 * nothing itself: it mirrors the reader's read-aloud state and passes the buttons back to it.
 */
@OptIn(UnstableApi::class)
class SpeechPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    /** What the buttons do; set by the reader while it's reading aloud. */
    interface Controls {
        fun play()
        fun pause()
        fun stop()
        fun skip(sentences: Int)
    }

    var controls: Controls? = null
    private var active = false
    private var playing = false
    private var metadata = MediaMetadata.EMPTY

    /** Called whenever read-aloud starts, pauses, resumes, stops or moves to another chapter. */
    fun update(active: Boolean, playing: Boolean, title: String, chapter: String, cover: Uri?) {
        this.active = active
        this.playing = active && playing
        metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(chapter.ifBlank { null })
            .setArtworkUri(cover)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .build()
        invalidateState()
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (!active) return builder.setPlaybackState(Player.STATE_IDLE).build()
        val item = MediaItemData.Builder("read-aloud")
            .setMediaItem(MediaItem.Builder().setMediaId("read-aloud").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .build()
        return builder.setPlaylist(listOf(item)).setPlaybackState(Player.STATE_READY).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controls?.play() else controls?.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controls?.stop()
        return Futures.immediateVoidFuture()
    }

    /** For the session: skip buttons move a sentence back or forward. */
    fun sessionPlayer(): Player = object : ForwardingPlayer(this) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_BACK).add(Player.COMMAND_SEEK_FORWARD)
            .build()

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun seekToNext() { controls?.skip(1) }
        override fun seekToPrevious() { controls?.skip(-1) }
        override fun seekToNextMediaItem() { controls?.skip(1) }
        override fun seekToPreviousMediaItem() { controls?.skip(-1) }
        override fun seekForward() { controls?.skip(1) }
        override fun seekBack() { controls?.skip(-1) }
    }
}
