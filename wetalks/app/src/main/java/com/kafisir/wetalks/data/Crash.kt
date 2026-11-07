package com.kafisir.wetalks.data

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Crash reporting (Firebase Crashlytics). Privacy rules: the user id sent is a one-way hash (never the uid, phone number or name),
 * log lines never contain message text, and there is a switch in Privacy & security to turn reporting off.
 */
object Crash {
    private fun sp(c: Context) = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("crash_reports", true)

    fun setEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("crash_reports", v).apply()
        try { FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(v) } catch (_: Throwable) {}
    }

    /** Called once at start (every process). */
    fun init(c: Context) {
        try {
            val cr = FirebaseCrashlytics.getInstance()
            cr.setCrashlyticsCollectionEnabled(enabled(c))
            val v = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
            cr.setCustomKey("app_version", v)
        } catch (_: Throwable) {}
    }

    /** The signed-in account as an anonymous id (first 16 hex characters of SHA-256), or empty after sign-out. */
    fun user(uid: String) {
        try {
            val id = if (uid.isEmpty()) "" else java.security.MessageDigest.getInstance("SHA-256").digest(("wt-crash:$uid").toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            FirebaseCrashlytics.getInstance().setUserId(id)
        } catch (_: Throwable) {}
    }

    /** A breadcrumb that is attached to the next crash report. Never put message text, names or numbers in it. */
    fun log(line: String) { try { FirebaseCrashlytics.getInstance().log(line.take(200)) } catch (_: Throwable) {} }

    /** A handled error that is still worth knowing about (shows up as a non-fatal in the console). */
    fun record(e: Throwable, what: String) {
        try { val cr = FirebaseCrashlytics.getInstance(); cr.setCustomKey("what", what.take(60)); cr.recordException(e) } catch (_: Throwable) {}
    }
}
