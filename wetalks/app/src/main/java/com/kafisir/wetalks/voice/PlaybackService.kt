package com.kafisir.wetalks.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.kafisir.wetalks.MainActivity
import com.kafisir.wetalks.R

/**
 * Foreground "now playing" service for voice messages: media notification (play / pause / close) and lock-screen controls,
 * so playback keeps going, and stays controllable, when the app is in the background or the screen is off.
 */
class PlaybackService : Service() {
    companion object {
        private const val CH = "playback"; private const val ID = 79
        const val ACT_TOGGLE = "wt.toggle"; const val ACT_STOP = "wt.stop"
        /** Starts / refreshes / stops the service to match the player. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            if (VoicePlayer.currentId != null || Speaker.reading) androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
            else app.stopService(Intent(app, PlaybackService::class.java))
        }
    }
    private var session: MediaSessionCompat? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> if (Speaker.reading) Speaker.toggle() else if (VoicePlayer.playing) VoicePlayer.pause() else VoicePlayer.resume()
            ACT_STOP -> { VoicePlayer.stop(); Speaker.stop(); ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        }
        if (VoicePlayer.currentId == null && !Speaker.reading) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        show()
        return START_NOT_STICKY
    }

    private fun show() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Voice message playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val s = session ?: MediaSessionCompat(this, "WeTalks").also { ms ->
            ms.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (Speaker.reading) Speaker.resume() else VoicePlayer.resume(); show() }
                override fun onPause() { if (Speaker.reading) Speaker.pause() else VoicePlayer.pause(); show() }
                override fun onStop() { VoicePlayer.stop(); Speaker.stop(); sync(this@PlaybackService) } })
            ms.isActive = true; session = ms
        }
        val tts = Speaker.reading
        val playing = if (tts) !Speaker.paused else VoicePlayer.playing
        val title = if (tts) Speaker.label else VoicePlayer.title
        s.setMetadata(MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, title).putString(MediaMetadataCompat.METADATA_KEY_ARTIST, if (tts) "WeTalks · reading aloud" else "WeTalks voice message").putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (tts) -1L else VoicePlayer.total.toLong()).build())
        s.setPlaybackState(PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, if (tts) 0L else VoicePlayer.pos.toLong(), if (playing && !tts) VoicePlayer.speed else if (playing) 1f else 0f).build())
        fun pi(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title.ifEmpty { "Voice message" }).setContentText(if (tts) (if (playing) "Reading aloud" else "Paused") else if (playing) "Playing voice message" else "Paused")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(playing).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, if (playing) "Pause" else "Play", pi(ACT_TOGGLE, 1)).addAction(0, "Close", pi(ACT_STOP, 2))
            .setStyle(MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1)).build()
        ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }

    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }
}
