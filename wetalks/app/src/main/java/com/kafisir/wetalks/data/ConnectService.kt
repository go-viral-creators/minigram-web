package com.kafisir.wetalks.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kafisir.wetalks.MainActivity
import com.kafisir.wetalks.R

/**
 * Keeps WeTalks connected while the app is in the background or swiped away, so incoming calls can still ring.
 * (Without Firebase Cloud Messaging this is the only way for a phone to hear about a call while the app is not open.)
 */
class ConnectService : Service() {
    companion object {
        private const val CH = "connection"; private const val ID = 80
        fun enabled(c: Context) = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).getBoolean("bg_connect", true)
        fun setEnabled(c: Context, v: Boolean) { c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).edit().putBoolean("bg_connect", v).apply(); sync(c) }
        /** Starts or stops the service to match the setting and the sign-in state. */
        fun sync(c: Context) {
            val app = c.applicationContext
            try {
                if (enabled(app) && Repo.uid.isNotEmpty()) androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, ConnectService::class.java))
                else app.stopService(Intent(app, ConnectService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotifChannels.ensureAll(this)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Connection", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("WeTalks is connected").setContentText("You can receive calls while the app is closed")
            .setPriority(NotificationCompat.PRIORITY_MIN).setOngoing(true).setContentIntent(open).setSilent(true).build()
        try { ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0) }
        catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        // the process may have been restarted by the system with no screen: re-attach the listeners
        try { if (Repo.uid.isNotEmpty()) { Calls.listen(); Repo.watchBlocks(); Repo.watchPrivacy(); Notifier.start(this) } } catch (_: Exception) {}
        return START_STICKY
    }
}
