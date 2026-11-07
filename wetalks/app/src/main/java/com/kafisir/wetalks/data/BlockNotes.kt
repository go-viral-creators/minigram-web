package com.kafisir.wetalks.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "You blocked this user" / "You unblocked this user" lines inside a chat. Blocking itself is local-first, so these lines are kept
 * on this phone only (the other person never sees them): per chat partner a short list of (time, blocked?) events.
 */
object BlockNotes {
    private const val PREFS = "wt_blocknotes"
    private const val KEEP = 20
    /** Bumps on every change so the chat screen redraws. */
    val tick = MutableStateFlow(0)

    fun parse(raw: String?): List<Pair<Long, Boolean>> =
        raw.orEmpty().split(',').mapNotNull { p -> val a = p.split(':'); val t = a.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null; t to (a.getOrNull(1) == "1") }

    fun encode(list: List<Pair<Long, Boolean>>) = list.takeLast(KEEP).joinToString(",") { it.first.toString() + ":" + (if (it.second) "1" else "0") }

    /** Adds an event, unless it repeats the state the last event already recorded. */
    fun plus(list: List<Pair<Long, Boolean>>, ts: Long, blocked: Boolean): List<Pair<Long, Boolean>> =
        if (list.lastOrNull()?.second == blocked) list else (list + (ts to blocked)).takeLast(KEEP)

    fun events(ctx: Context, peer: String): List<Pair<Long, Boolean>> =
        parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(peer, null))

    fun add(ctx: Context, peer: String, blocked: Boolean) {
        if (peer.isEmpty()) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = parse(p.getString(peer, null))
        val next = plus(cur, System.currentTimeMillis(), blocked)
        if (next === cur) return
        p.edit().putString(peer, encode(next)).apply()
        tick.value = tick.value + 1
    }

    fun text(blocked: Boolean) = if (blocked) "You blocked this user" else "You unblocked this user"
}
