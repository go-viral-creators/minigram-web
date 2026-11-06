package com.kafisir.wetalks.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.kafisir.wetalks.MainActivity
import com.kafisir.wetalks.R
import com.kafisir.wetalks.screens.previewText
import com.kafisir.wetalks.ui.Nav
import com.kafisir.wetalks.ui.RChat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Message notifications while the app is in the background (the connection service keeps the process alive):
 * one notification per chat in WhatsApp style, with Reply (type right in the shade), Mark as read and Mute.
 */
object Notifier {
    private const val CH = "messages"; private const val GROUP = "wt_messages"; private const val SUMMARY = 81
    const val ACT_REPLY = "wt.reply"; const val ACT_READ = "wt.read"; const val ACT_MUTE = "wt.mute"; const val ACT_DECLINE = "wt.decline"
    const val KEY_REPLY = "reply_text"

    private class Conv(val ref: ChatRef, val title: String, val group: Boolean)
    private class Line(val who: String, val ts: Long, val text: String, val mine: Boolean)
    private lateinit var app: Context
    private var job: Job? = null
    private var baseline = false
    private val last = HashMap<String, Long>()                 // chat id -> unread count we already know about
    private val convs = HashMap<String, Conv>()
    private val lines = HashMap<String, ArrayList<Line>>()

    fun enabled(c: Context) = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).getBoolean("notif_msgs", true)
    fun setEnabled(c: Context, v: Boolean) { c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).edit().putBoolean("notif_msgs", v).apply(); if (v) start(c) else stop() }

    fun start(c: Context) {
        app = c.applicationContext
        if (!enabled(app) || Repo.uid.isEmpty() || job?.isActive == true) return
        baseline = false; last.clear()
        job = Repo.scope.launch { Repo.chats().collect { list -> try { handle(list) } catch (_: Exception) {} } }
    }
    fun stop() { job?.cancel(); job = null; try { NotificationManagerCompat.from(app).cancelAll() } catch (_: Exception) {}; lines.clear(); convs.clear() }
    fun cancel(cid: String) { if (!::app.isInitialized) return; try { NotificationManagerCompat.from(app).cancel(cid.hashCode()) } catch (_: Exception) {}; lines.remove(cid) }

    private fun isOpen(ref: ChatRef) = Calls.foreground && (Nav.stack.lastOrNull() as? RChat)?.ref?.id == ref.id

    private suspend fun handle(list: List<ChatItem>) {
        val first = !baseline; baseline = true
        for (c in list) {
            val id = c.ref.id; val prev = last[id] ?: 0L; last[id] = c.unread
            if (c.unread == 0L) { if (prev > 0) cancel(id); continue }
            if (first || c.unread <= prev) continue
            if (c.ref.kind == Kind.CHANNEL || c.muted || c.lastFrom.isEmpty() || c.lastFrom == Repo.uid || isOpen(c.ref)) continue
            post(c)
        }
    }

    private suspend fun post(c: ChatItem) {
        val m = Repo.decrypt(c.ref, Repo.Raw("", c.lastFrom, c.lastTs, c.lastType, c.lastBody, 0, null, false, false, emptyMap()))
        val text = if (m.unreadable) "New message" else when (m.type) { "call" -> "Call"; else -> previewText(m.type, m.text).ifEmpty { "New message" } }
        val who = (Repo.user(c.lastFrom).value ?: try { Repo.userOnce(c.lastFrom) } catch (_: Exception) { null })?.name ?: "Someone"
        val group = c.ref.kind == Kind.GROUP
        val title = if (group) (Repo.group(c.ref.id).value?.name ?: "Group") else who
        convs[c.ref.id] = Conv(c.ref, title, group)
        val l = lines.getOrPut(c.ref.id) { ArrayList() }
        l.add(Line(who, if (c.lastTs > 0) c.lastTs else System.currentTimeMillis(), text, false)); while (l.size > 8) l.removeAt(0)
        show(c.ref.id, alert = true)
    }

    private fun channel() {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Messages", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }

    private fun show(cid: String, alert: Boolean) {
        val cv = convs[cid] ?: return; val l = lines[cid] ?: return
        channel()
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(cv.group)
        if (cv.group) style.conversationTitle = cv.title
        l.forEach { x -> style.addMessage(x.text, x.ts, if (x.mine) me else Person.Builder().setName(x.who).build()) }
        fun extras(i: Intent) = i.setPackage(app.packageName).putExtra("cid", cid).putExtra("kind", cv.ref.kind.ordinal).putExtra("peer", cv.ref.peer ?: "")
        val code = cid.hashCode()
        val replyPi = PendingIntent.getBroadcast(app, code, extras(Intent(app, NotifReceiver::class.java).setAction(ACT_REPLY)), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val readPi = PendingIntent.getBroadcast(app, code + 1, extras(Intent(app, NotifReceiver::class.java).setAction(ACT_READ)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mutePi = PendingIntent.getBroadcast(app, code + 2, extras(Intent(app, NotifReceiver::class.java).setAction(ACT_MUTE)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(app, code + 3, extras(Intent(app, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).setAction("wt.open." + cid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val reply = NotificationCompat.Action.Builder(0, "Reply", replyPi).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setAllowGeneratedReplies(true).build()
        val n = NotificationCompat.Builder(app, CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(cv.title).setContentText(l.last().text).setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setOnlyAlertOnce(!alert).setWhen(l.last().ts).setShowWhen(true)
            .setContentIntent(open).setGroup(GROUP)
            .addAction(reply)
            .addAction(NotificationCompat.Action.Builder(0, "Mark as read", readPi).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build())
            .addAction(NotificationCompat.Action.Builder(0, "Mute", mutePi).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE).build())
            .build()
        try {
            val nm = NotificationManagerCompat.from(app)
            nm.notify(code, n)
            nm.notify(SUMMARY, NotificationCompat.Builder(app, CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("WeTalks").setContentText("New messages").setGroup(GROUP).setGroupSummary(true).setAutoCancel(true).build())
        } catch (_: SecurityException) {}
    }

    /** Called by the receiver: show my own reply in the notification so the "sending" spinner ends. */
    fun afterReply(cid: String, text: String) {
        val l = lines.getOrPut(cid) { ArrayList() }
        l.add(Line("You", System.currentTimeMillis(), text, true))
        if (convs.containsKey(cid)) show(cid, alert = false)
    }
}

/** Handles the notification buttons: Reply, Mark as read, Mute (and Decline for incoming calls). */
class NotifReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val cid = i.getStringExtra("cid") ?: ""
        val ref = ChatRef(cid, Kind.values().getOrElse(i.getIntExtra("kind", 0)) { Kind.DM }, i.getStringExtra("peer")?.ifEmpty { null })
        val pending = goAsync()
        Repo.scope.launch {
            try {
                when (i.action) {
                    Notifier.ACT_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(Notifier.KEY_REPLY)?.toString()?.trim().orEmpty()
                        if (text.isNotEmpty()) { Repo.send(ref, "text", text, null, text.take(90)); Notifier.afterReply(cid, text); Repo.markRead(ref) }
                    }
                    Notifier.ACT_READ -> { Repo.markRead(ref); Notifier.cancel(cid) }
                    Notifier.ACT_MUTE -> { Repo.setMuted(cid, true); Notifier.cancel(cid) }
                    Notifier.ACT_DECLINE -> Calls.decline()
                }
            } catch (_: Exception) {} finally { pending.finish() }
        }
    }
}

/** Restarts the background connection after the phone reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { if (i.action == Intent.ACTION_BOOT_COMPLETED) ConnectService.sync(ctx) }
}
