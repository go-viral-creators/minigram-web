package com.kafisir.wetalks.data

/**
 * The reconnect policy of a call as a pure state machine (no Android, no clocks of its own: every call gets "now" in ms). Unit tested.
 *
 * Idea: a short network trouble must not end the call. On trouble (ICE DISCONNECTED / FAILED) the call goes into a bounded
 * RECONNECTING window. Only the CALLER makes ICE-restart offers (so both phones never offer at the same time); the callee just shows
 * "Reconnecting…" and answers whatever the caller sends. When the window is over the call ends ("Connection lost").
 *
 *   caller, ICE FAILED at t=0:        attempt 1 (normal path) at 0 s, attempt 2 (forced relay) at 8 s, attempt 3 (relay) at 20 s, give up at 45 s
 *   caller, ICE DISCONNECTED at t=0:  same, but attempt 1 waits 2 s (DISCONNECTED often heals by itself)
 *   callee:                           never restarts, gives up 10 s after the caller's window (the caller normally ends the call first)
 *   network changed (Wi-Fi <-> mobile) while connected: the caller makes one quiet restart (no banner), at most once every 5 s
 */
enum class IceEv { CONNECTED, DISCONNECTED, FAILED }

sealed class ReconnectAction {
    /** Caller only. [relay] = force the TURN relay for this attempt, [silent] = proactive restart after a network change (no "Reconnecting…"). */
    data class RestartIce(val attempt: Int, val relay: Boolean, val silent: Boolean) : ReconnectAction()
    /** Put the ICE transport policy back to normal (ALL). */
    object RelayOff : ReconnectAction()
    object ShowReconnecting : ReconnectAction()
    object HideReconnecting : ReconnectAction()
    /** The window is over: end the call. */
    object GiveUp : ReconnectAction()
}

data class ReconnectCfg(
    val windowMs: Long = 45_000,
    val disconnectGraceMs: Long = 2_000,
    val backoffMs: List<Long> = listOf(8_000, 12_000),   // wait after attempt 1, after attempt 2
    val maxAttempts: Int = 3,
    val relayFromAttempt: Int = 2,
    val calleeExtraMs: Long = 10_000,
    val relayBackAfterMs: Long = 20_000,                  // connected this long again -> policy back to ALL for later restarts
    val netDebounceMs: Long = 1_500,
    val silentMinGapMs: Long = 5_000,
    val netGapMs: Long = 3_000,                           // a network change restarts at once during the window, if the last attempt is older than this
)

class ReconnectMachine(private val caller: Boolean, private val cfg: ReconnectCfg = ReconnectCfg()) {
    enum class State { IDLE, RECONNECTING, GAVE_UP }

    var state = State.IDLE; private set
    var attempts = 0; private set
    var relayForced = false; private set
    var online = true; private set
    private var everConnected = false
    private var deadline = 0L
    private var nextAt = Long.MAX_VALUE
    private var lastAttemptAt = Long.MIN_VALUE / 2
    private var lastSilentAt = Long.MIN_VALUE / 2
    private var silentAt = Long.MAX_VALUE
    private var relayBackAt = Long.MAX_VALUE
    private var extraNow = false          // a non-counting restart is due (network changed during the window)

    fun onIce(ev: IceEv, now: Long): List<ReconnectAction> {
        if (state == State.GAVE_UP) return emptyList()
        val out = ArrayList<ReconnectAction>()
        when (ev) {
            IceEv.CONNECTED -> {
                everConnected = true
                if (state == State.RECONNECTING) { state = State.IDLE; nextAt = Long.MAX_VALUE; extraNow = false; out += ReconnectAction.HideReconnecting }
                if (relayForced) relayBackAt = now + cfg.relayBackAfterMs
            }
            IceEv.DISCONNECTED -> trouble(now, cfg.disconnectGraceMs, out)
            IceEv.FAILED -> trouble(now, 0, out)
        }
        return out
    }

    private fun trouble(now: Long, grace: Long, out: MutableList<ReconnectAction>) {
        silentAt = Long.MAX_VALUE
        if (state == State.RECONNECTING) {
            // FAILED while waiting: do not wait longer for the next attempt than the time since the last one allows
            if (grace == 0L && attempts < cfg.maxAttempts && nextAt > now && now - lastAttemptAt >= cfg.netGapMs) nextAt = now
            return
        }
        state = State.RECONNECTING
        attempts = 0
        deadline = now + cfg.windowMs + (if (caller) 0 else cfg.calleeExtraMs)
        nextAt = now + grace
        relayBackAt = Long.MAX_VALUE        // trouble again: keep whatever the policy is for the first attempt
        out += ReconnectAction.ShowReconnecting
    }

    /** The default network changed ([available] = true: a network is there (new or came back); false: it was lost). */
    fun onNetwork(available: Boolean, now: Long): List<ReconnectAction> {
        online = available
        if (!available || state == State.GAVE_UP || !everConnected) return emptyList()
        if (state == State.RECONNECTING) {
            if (caller && now - lastAttemptAt >= cfg.netGapMs) { extraNow = true; nextAt = minOf(nextAt, now + 300) }
            else if (caller && attempts < cfg.maxAttempts && nextAt > now + 300) nextAt = now + 300
            return emptyList()
        }
        if (caller && silentAt == Long.MAX_VALUE) silentAt = maxOf(now + cfg.netDebounceMs, lastSilentAt + cfg.silentMinGapMs)
        return emptyList()
    }

    fun onTick(now: Long): List<ReconnectAction> {
        val out = ArrayList<ReconnectAction>()
        if (state == State.GAVE_UP) return out
        if (relayForced && state == State.IDLE && now >= relayBackAt) { relayForced = false; relayBackAt = Long.MAX_VALUE; out += ReconnectAction.RelayOff }
        if (state == State.IDLE) {
            if (silentAt != Long.MAX_VALUE && now >= silentAt && online) {
                silentAt = Long.MAX_VALUE; lastSilentAt = now
                out += ReconnectAction.RestartIce(0, relayForced, true)
            }
            return out
        }
        if (now >= deadline) { state = State.GAVE_UP; nextAt = Long.MAX_VALUE; out += ReconnectAction.HideReconnecting; out += ReconnectAction.GiveUp; return out }
        if (caller && online && now >= nextAt) {
            if (extraNow && attempts > 0) {
                // the network changed: try again at once on the same path, without using up an attempt
                extraNow = false; lastAttemptAt = now
                out += ReconnectAction.RestartIce(attempts, relayForced, false)
                nextAt = cfg.backoffMs.getOrNull(attempts - 1)?.let { now + it } ?: Long.MAX_VALUE
            } else if (attempts < cfg.maxAttempts) {
                extraNow = false
                attempts++
                val relay = attempts >= cfg.relayFromAttempt || relayForced
                if (relay) relayForced = true
                lastAttemptAt = now
                out += ReconnectAction.RestartIce(attempts, relay, false)
                nextAt = if (attempts < cfg.maxAttempts) now + (cfg.backoffMs.getOrNull(attempts - 1) ?: cfg.backoffMs.lastOrNull() ?: 10_000) else Long.MAX_VALUE
            } else nextAt = Long.MAX_VALUE
        }
        return out
    }
}
