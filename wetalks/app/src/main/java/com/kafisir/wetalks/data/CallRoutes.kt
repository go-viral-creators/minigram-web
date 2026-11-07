package com.kafisir.wetalks.data

/** Where the sound of a call can go. */
enum class RouteKind { EARPIECE, SPEAKER, BLUETOOTH, WIRED }

/** One place the sound can go. [id] is the Android audio device id (0 for the built-in earpiece and speaker); [name] is the device name when it has one. */
data class AudioRoute(val kind: RouteKind, val id: Int = 0, val name: String = "")

/** What to do when Android tells us the audio focus changed. */
enum class FocusAction { NONE, LOST, RESTORE }

/**
 * Pure rules for choosing the call audio route (no Android classes, so it is unit tested on the JVM).
 * Order of preference follows phone apps: Bluetooth, then a wired headset, then the earpiece (the speaker for video calls).
 */
object CallRoutes {
    // same numbers as AudioManager.AUDIOFOCUS_*
    const val FOCUS_GAIN = 1
    const val FOCUS_LOSS = -1
    const val FOCUS_LOSS_TRANSIENT = -2
    const val FOCUS_LOSS_TRANSIENT_CAN_DUCK = -3

    fun isHeadset(k: RouteKind) = k == RouteKind.BLUETOOTH || k == RouteKind.WIRED

    /** Earpiece and speaker are "the same" whatever the id; headsets are told apart by their device id. */
    fun same(a: AudioRoute?, b: AudioRoute?): Boolean {
        if (a == null || b == null) return false
        if (a.kind != b.kind) return false
        return if (isHeadset(a.kind)) a.id == b.id else true
    }

    fun contains(list: List<AudioRoute>, r: AudioRoute) = list.any { same(it, r) }

    /** Text for a button or list row. */
    fun label(r: AudioRoute): String = when (r.kind) {
        RouteKind.EARPIECE -> "Phone"
        RouteKind.SPEAKER -> "Speaker"
        RouteKind.BLUETOOTH -> r.name.ifBlank { "Bluetooth" }
        RouteKind.WIRED -> r.name.ifBlank { "Wired headset" }
    }

    /** Short label under the call button. */
    fun shortLabel(r: AudioRoute): String = when (r.kind) {
        RouteKind.BLUETOOTH -> "Bluetooth"
        RouteKind.WIRED -> "Headset"
        else -> label(r)
    }

    /**
     * Clean list of what can be shown: the speaker is always there, the earpiece only on a phone, every headset once.
     * Order: Phone, Speaker, Bluetooth..., Wired...
     */
    fun normalize(devices: List<AudioRoute>, hasEarpiece: Boolean): List<AudioRoute> {
        val out = ArrayList<AudioRoute>()
        if (hasEarpiece) out += AudioRoute(RouteKind.EARPIECE)
        out += AudioRoute(RouteKind.SPEAKER)
        for (k in listOf(RouteKind.BLUETOOTH, RouteKind.WIRED)) {
            for (d in devices) if (d.kind == k && out.none { same(it, d) }) out += AudioRoute(k, d.id, d.name)
        }
        return out
    }

    private fun first(list: List<AudioRoute>, k: RouteKind) = list.firstOrNull { it.kind == k }

    /** The best route right now: Bluetooth, wired, then earpiece (or speaker for video calls and phones without an earpiece). */
    fun preferred(video: Boolean, available: List<AudioRoute>): AudioRoute =
        first(available, RouteKind.BLUETOOTH) ?: first(available, RouteKind.WIRED)
            ?: (if (video) null else first(available, RouteKind.EARPIECE)) ?: AudioRoute(RouteKind.SPEAKER)

    /** Where to go when [lost] stops working (headset unplugged, Bluetooth did not connect): the best of what is left. */
    fun fallback(video: Boolean, available: List<AudioRoute>, lost: AudioRoute): AudioRoute =
        preferred(video, available.filter { !same(it, lost) })

    /**
     * The device list changed ([prev] -> [now]) while [current] was in use.
     * A newly connected headset is switched to at once (Bluetooth before wired); if the current headset is gone we fall back;
     * otherwise nothing changes, so a choice the person made on purpose is not undone by an unrelated change.
     */
    fun afterDevicesChanged(prev: List<AudioRoute>, now: List<AudioRoute>, current: AudioRoute, video: Boolean): AudioRoute {
        val added = now.filter { isHeadset(it.kind) && !contains(prev, it) }
        if (added.isNotEmpty()) return added.firstOrNull { it.kind == RouteKind.BLUETOOTH } ?: added.first()
        if (contains(now, current)) return now.first { same(it, current) }
        return preferred(video, now)
    }

    /** Video started: use the speaker unless a headset is in use. */
    fun afterVideoStarted(current: AudioRoute, available: List<AudioRoute>): AudioRoute =
        if (current.kind == RouteKind.EARPIECE) first(available, RouteKind.SPEAKER) ?: current else current

    /** The old speaker button: speaker on / off. Off goes back to the best non-speaker route. */
    fun afterSpeakerToggle(current: AudioRoute, available: List<AudioRoute>, video: Boolean): AudioRoute =
        if (current.kind != RouteKind.SPEAKER) first(available, RouteKind.SPEAKER) ?: current
        else preferred(false, available.filter { it.kind != RouteKind.SPEAKER }.ifEmpty { return current })

    /** Losing audio focus must never end the call; it only marks it, and we take the route back when focus returns. */
    fun focusAction(change: Int): FocusAction = when (change) {
        FOCUS_GAIN -> FocusAction.RESTORE
        FOCUS_LOSS, FOCUS_LOSS_TRANSIENT -> FocusAction.LOST
        else -> FocusAction.NONE        // "can duck": another app is quiet for a moment, we keep talking
    }
}

/** When the screen may turn off at the ear (proximity wake lock). Pure, unit tested. */
object ProximityRule {
    /**
     * Only a voice call on the earpiece, while the call screen is in front. Not on speaker, Bluetooth or a wired headset, not in video calls,
     * not while the call is ringing on this phone, and never when the phone has no proximity sensor.
     */
    fun shouldHold(phase: CallPhase, video: Boolean, route: RouteKind, sensorAvailable: Boolean, callScreenShown: Boolean): Boolean {
        if (!sensorAvailable || video || !callScreenShown) return false
        if (route != RouteKind.EARPIECE) return false
        return phase == CallPhase.OUTGOING || phase == CallPhase.CONNECTING || phase == CallPhase.ACTIVE
    }
}
