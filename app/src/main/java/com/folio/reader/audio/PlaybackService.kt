package com.folio.reader.audio

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.folio.reader.FolioApp
import com.folio.reader.MainActivity
import com.folio.reader.R

/**
 * Keeps an audiobook playing in the background, with the media notification, lock-screen
 * controls and headphone buttons. The player itself belongs to [AudioPlayer].
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as FolioApp
        val open = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_AUDIO, true)
        val back = CommandButton.Builder()
            .setDisplayName("Back 15 seconds")
            .setIconResId(R.drawable.ic_replay_15)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .build()
        val forward = CommandButton.Builder()
            .setDisplayName("Forward 30 seconds")
            .setIconResId(R.drawable.ic_forward_30)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .build()
        val s = MediaSession.Builder(this, app.audio.sessionPlayer())
            .setSessionActivity(
                PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .setCustomLayout(listOf(back, forward))
            .build()
        session = s
        // Started directly by the app rather than by a controller connecting, so register it here.
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // Swiped away from recents while paused: nothing to keep going for.
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release() // the player outlives the service; only the session goes
        session = null
        super.onDestroy()
    }
}
