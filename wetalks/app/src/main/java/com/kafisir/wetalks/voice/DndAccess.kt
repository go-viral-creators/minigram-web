package com.kafisir.wetalks.voice

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Other apps' notification sounds can only be silenced by changing Do Not Disturb, which needs the person to grant
 * "Do Not Disturb access" to WeTalks in system settings. While recording we switch to "alarms only" and ALWAYS put the
 * old setting back: the old value is written to disk before the change, so [recoverAtStart] can restore it if the app
 * died while recording.
 */
object DndAccess {
    // same values as NotificationManager.INTERRUPTION_FILTER_*
    const val ALL = 1; const val PRIORITY = 2; const val NONE = 3; const val ALARMS = 4
    const val MAX_ASKS = 2

    // ---- pure rules (unit tested)
    /** If the person already has "alarms only" or "total silence", leave it alone. */
    fun shouldChange(current: Int) = current == ALL || current == PRIORITY
    /** Put the old filter back only if it is still the one we set (the person may have changed it meanwhile). */
    fun shouldRestore(weChanged: Boolean, currentNow: Int, applied: Int) = weChanged && currentNow == applied
    /** Ask at most [MAX_ASKS] times; never when already granted or when "don't ask again" was ticked. */
    fun shouldAsk(granted: Boolean, timesAsked: Int, never: Boolean) = !granted && !never && timesAsked < MAX_ASKS

    // ---- Android side
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("wt_rec", Context.MODE_PRIVATE)
    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun granted(c: Context) = try { nm(c).isNotificationPolicyAccessGranted } catch (_: Throwable) { false }
    fun canAsk(c: Context) = shouldAsk(granted(c), sp(c).getInt("dnd_asked", 0), sp(c).getBoolean("dnd_never", false))
    fun noteAsked(c: Context, never: Boolean) { sp(c).edit().putInt("dnd_asked", sp(c).getInt("dnd_asked", 0) + 1).putBoolean("dnd_never", never).apply() }
    fun settingsIntent() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** @return true when Do Not Disturb is now changed by us (and [restore] will undo it). */
    fun mute(c: Context): Boolean {
        return try {
            val n = nm(c)
            if (!n.isNotificationPolicyAccessGranted) return false
            if (sp(c).getInt("dnd_prev", -1) >= 0) return true          // already muted by us
            val cur = n.currentInterruptionFilter
            if (!shouldChange(cur)) return false
            sp(c).edit().putInt("dnd_prev", cur).putInt("dnd_applied", ALARMS).commit()   // on disk BEFORE the change
            n.setInterruptionFilter(ALARMS)
            true
        } catch (_: Throwable) { sp(c).edit().remove("dnd_prev").remove("dnd_applied").commit(); false }
    }

    fun restore(c: Context) {
        val prev = sp(c).getInt("dnd_prev", -1)
        if (prev < 0) return
        try {
            val n = nm(c)
            if (n.isNotificationPolicyAccessGranted && shouldRestore(true, n.currentInterruptionFilter, sp(c).getInt("dnd_applied", ALARMS))) n.setInterruptionFilter(prev)
        } catch (_: Throwable) {}
        sp(c).edit().remove("dnd_prev").remove("dnd_applied").commit()
    }

    /** Call once at app start: if the app died while recording, the old Do Not Disturb setting is still waiting on disk. */
    fun recoverAtStart(c: Context) = restore(c)
}
