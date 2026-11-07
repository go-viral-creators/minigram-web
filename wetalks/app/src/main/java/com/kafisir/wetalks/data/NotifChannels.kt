package com.kafisir.wetalks.data

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

/**
 * Every notification category the app uses, created in one place. Android lets the person change sound, vibration and importance of each
 * category in system settings, and an app can never raise the importance again afterwards, so the ids that already exist keep their ids.
 *
 *  - messages / groups : new chat messages (heads-up, sound, vibration)
 *  - calls             : the incoming-call notification (the ringtone itself is played by the app)
 *  - missed_calls      : missed call with "Call back"
 *  - services          : messages from apps / services (OTP, alerts)
 *  - connection        : the small "WeTalks is connected" notification (silent, lowest)
 *  - background        : work running in the background: exports, backups, uploads (silent)
 *  - playback          : voice message / read-aloud player (silent, lock-screen controls)
 */
object NotifChannels {
    const val MESSAGES = "messages"; const val GROUPS = "groups"
    const val CALLS = "calls"; const val MISSED = "missed_calls"; const val SERVICES = "services"
    const val CONNECTION = "connection"; const val BACKGROUND = "background"; const val PLAYBACK = "playback"
    private const val GROUP_CHATS = "wt_chats"; private const val GROUP_CALLS = "wt_calls"; private const val GROUP_SYSTEM = "wt_system"

    /** What each category looks like: id, title, description, importance, sound?, vibration?, group. Pure data, unit tested. */
    class Spec(val id: String, val title: String, val desc: String, val importance: Int, val sound: Boolean, val vibrate: Boolean, val group: String)

    val specs: List<Spec> = listOf(
        Spec(MESSAGES, "Messages", "New messages in your one-to-one chats", NotificationManager.IMPORTANCE_HIGH, true, true, GROUP_CHATS),
        Spec(GROUPS, "Groups", "New messages in groups", NotificationManager.IMPORTANCE_HIGH, true, true, GROUP_CHATS),
        Spec(CALLS, "Incoming calls", "Shown while a call is ringing", NotificationManager.IMPORTANCE_HIGH, false, false, GROUP_CALLS),
        Spec(MISSED, "Missed calls", "Calls you missed, with a Call back button", NotificationManager.IMPORTANCE_HIGH, true, true, GROUP_CALLS),
        Spec(SERVICES, "Service messages", "Codes and alerts sent by apps and services", NotificationManager.IMPORTANCE_HIGH, true, true, GROUP_CHATS),
        Spec(CONNECTION, "Connection", "Keeps WeTalks connected so calls can ring while the app is closed", NotificationManager.IMPORTANCE_MIN, false, false, GROUP_SYSTEM),
        Spec(BACKGROUND, "Background work", "Exports, backups and uploads running in the background", NotificationManager.IMPORTANCE_LOW, false, false, GROUP_SYSTEM),
        Spec(PLAYBACK, "Voice playback", "Voice message and read-aloud controls", NotificationManager.IMPORTANCE_LOW, false, false, GROUP_SYSTEM),
    )

    /** The category for a chat's messages. Locked chats use the normal message category (their text is hidden elsewhere). */
    fun forChat(kind: Kind) = when (kind) { Kind.DM -> MESSAGES; Kind.GROUP -> GROUPS; Kind.CHANNEL -> MESSAGES }

    /** Creates every category that does not exist yet. Safe to call as often as needed. */
    fun ensureAll(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannelGroups(listOf(NotificationChannelGroup(GROUP_CHATS, "Chats"), NotificationChannelGroup(GROUP_CALLS, "Calls"), NotificationChannelGroup(GROUP_SYSTEM, "Background")))
        for (s in specs) {
            if (nm.getNotificationChannel(s.id) != null) continue
            val ch = NotificationChannel(s.id, s.title, s.importance).apply {
                description = s.desc; group = s.group
                enableVibration(s.vibrate)
                if (!s.sound) setSound(null, null)
                else setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setShowBadge(s.id == MESSAGES || s.id == GROUPS || s.id == MISSED)
            }
            nm.createNotificationChannel(ch)
        }
    }
}

/**
 * Per-chat notification settings, kept on this phone: mute for a while, silent (shows, no sound), hide the text, and for channels
 * "Mute always" is the existing synced `muted` flag.
 */
object ChatNotif {
    private fun sp(c: Context) = c.getSharedPreferences("wt_chat_notif", Context.MODE_PRIVATE)
    const val H8 = 8L * 3_600_000L; const val WEEK = 7L * 86_400_000L

    fun mutedUntil(c: Context, cid: String) = sp(c).getLong("mu_$cid", 0L)
    fun muteFor(c: Context, cid: String, ms: Long) { sp(c).edit().putLong("mu_$cid", System.currentTimeMillis() + ms).apply() }
    fun unmuteTimed(c: Context, cid: String) { sp(c).edit().remove("mu_$cid").apply() }
    fun silent(c: Context, cid: String) = sp(c).getBoolean("si_$cid", false)
    fun setSilent(c: Context, cid: String, v: Boolean) { sp(c).edit().putBoolean("si_$cid", v).apply() }
    fun hidePreview(c: Context, cid: String) = sp(c).getBoolean("hp_$cid", false)
    fun setHidePreview(c: Context, cid: String, v: Boolean) { sp(c).edit().putBoolean("hp_$cid", v).apply() }

    /** Pure rule: should this chat's new message make a notification at all? (the synced `muted` flag and the timed mute; channels do not notify yet) */
    fun shouldNotify(kind: Kind, mutedForever: Boolean, mutedUntil: Long, now: Long): Boolean = when {
        mutedForever -> false
        mutedUntil > now -> false
        kind == Kind.CHANNEL -> false
        else -> true
    }
}
