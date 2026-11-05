package com.kafisir.wetalks.data

import android.content.Context
import java.io.File

/** Keeps the stack trace of the last Java/Kotlin crash so it can be shared from Data & storage. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")
    fun install(c: Context) {
        val app = c.applicationContext; val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { file(app).writeText("WeTalks crash · " + java.util.Date() + " · thread " + t.name + "\n\n" + android.util.Log.getStackTraceString(e)) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
    fun last(c: Context): String? = file(c).takeIf { it.exists() }?.readText()
    fun clear(c: Context) { file(c).delete() }
}
