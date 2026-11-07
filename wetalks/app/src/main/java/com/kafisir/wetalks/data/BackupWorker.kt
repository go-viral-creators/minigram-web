package com.kafisir.wetalks.data

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.launch
import com.kafisir.wetalks.R
import java.util.concurrent.TimeUnit

/** Plans the automatic backups with WorkManager (survives restarts of the phone and of the app). */
object BackupScheduler {
    const val PERIODIC = "wt_backup_periodic"
    const val NOW = "wt_backup_now"

    /** Makes the system schedule match the settings: Off cancels it, Daily / Weekly (re)plans it with the Wi-Fi / charging conditions. */
    fun apply(c: Context) {
        val ctx = c.applicationContext
        try {
            val wm = WorkManager.getInstance(ctx)
            if (BackupStore.freq == BackupFreq.OFF) { wm.cancelUniqueWork(PERIODIC); return }
            val cons = Constraints.Builder()
                .setRequiredNetworkType(if (BackupStore.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(BackupStore.charging).setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
            val every = BackupStore.freq.ms
            val delay = BackupPlan.initialDelayMs(BackupStore.freq, BackupStore.lastSuccess, System.currentTimeMillis())
            val req = PeriodicWorkRequestBuilder<BackupWorker>(every, TimeUnit.MILLISECONDS, every / 8, TimeUnit.MILLISECONDS)
                .setConstraints(cons).setInitialDelay(delay, TimeUnit.MILLISECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        } catch (e: Exception) { android.util.Log.w("WeTalks", "could not plan the chat backup: " + e.message) }
    }

    /** "Back up now": starts at once (expedited where the system allows it), on any network. */
    fun backUpNow(c: Context, forceFull: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>().setInputData(workDataOf("manual" to true, "full" to forceFull))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Asks the running backup to stop after the current step. Its progress is kept, the next backup carries on. */
    fun cancel(c: Context) { BackupStore.cancelRequested = true; try { WorkManager.getInstance(c.applicationContext).cancelUniqueWork(NOW) } catch (_: Exception) {} }
}

/**
 * Runs one backup in the background. All the work happens on Dispatchers.IO inside [BackupRunner] (the main thread is never used). Signed out,
 * disk full or no connection end the run with a message in the settings, never with a crash.
 */
class BackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean("manual", false)
        if (manual) try { setForeground(getForegroundInfo()) } catch (_: Exception) { /* not allowed right now: carry on as normal work */ }
        return kotlinx.coroutines.coroutineScope {
            val watcher = launch { BackupStore.progress.collect { pr -> if (pr != null) notify(pr) } }
            try {
                val r = BackupRunner.run(applicationContext, forceFull = inputData.getBoolean("full", false))
                if (r.ok || !r.retry || manual) Result.success() else Result.retry()
            } finally { watcher.cancel(); try { androidx.core.app.NotificationManagerCompat.from(applicationContext).cancel(NOTIF) } catch (_: Exception) {} }
        }
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        NotifChannels.ensureAll(applicationContext)
        val b = NotificationCompat.Builder(applicationContext, NotifChannels.BACKGROUND).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Backing up your chats")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total.coerceAtLeast(1)), total <= 0)
        try { b.addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)) } catch (_: Exception) {}
        return b.build()
    }

    private fun notify(p: BackupEngine.Progress) {
        try { androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(NOTIF, notification(p.stage, p.chatsDone, p.chatsTotal)) } catch (_: SecurityException) { /* no notification permission */ } catch (_: Exception) {}
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = notification("Starting", 0, 0)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIF, n)
    }

    companion object { const val NOTIF = 7301 }
}
