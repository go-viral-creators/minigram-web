package com.kafisir.wetalks.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Locked chats. The list is kept on THIS phone only (it needs this phone's fingerprint / screen lock, and the server should not learn which chats are sensitive).
 * A locked chat leaves every list, opens only after the phone's lock is passed, and never shows its text in a notification.
 * Once passed, the chat stays open until the app goes to the background.
 */
object ChatLock {
    private var ids by mutableStateOf<Set<String>>(emptySet())
    private var open by mutableStateOf<Set<String>>(emptySet())
    /** True while the "Locked chats" folder is open (passed the lock in this visit). */
    var folderOpen by mutableStateOf(false)
    private fun sp(c: Context) = c.getSharedPreferences("wt_locked_chats", Context.MODE_PRIVATE)

    fun load(c: Context) { ids = sp(c).getStringSet("ids", emptySet()).orEmpty().toSet() }
    fun isLocked(id: String) = id in ids
    val all: Set<String> get() = ids
    fun set(c: Context, id: String, v: Boolean) {
        ids = if (v) ids + id else ids - id
        open = if (v) open else open - id
        sp(c).edit().putStringSet("ids", ids).apply()
    }
    fun isOpen(id: String) = id !in ids || id in open
    fun markOpen(id: String) { open = open + id }
    /** Called when the app goes to the background. */
    fun relockAll() { open = emptySet(); folderOpen = false }
    /** Used for chats removed from the account (so the list does not keep dead ids). */
    fun forget(c: Context, id: String) { if (id in ids) set(c, id, false) }
}
