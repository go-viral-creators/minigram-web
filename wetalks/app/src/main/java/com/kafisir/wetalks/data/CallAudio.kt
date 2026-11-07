package com.kafisir.wetalks.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Everything about where the sound of a call goes, and the screen-off sensor:
 * audio mode and audio focus, earpiece / speaker / Bluetooth / wired headset (with auto-switching when a headset connects or leaves),
 * and the proximity wake lock. The rules live in [CallRoutes] and [ProximityRule]; this class only talks to Android.
 *
 * Android 12+ uses setCommunicationDevice; older versions use the speakerphone flag and Bluetooth SCO.
 * [Calls] calls start / stop / select / onVideo and the on*State hooks; the UI reads [route], [available], [bluetoothBlocked].
 */
object CallAudio {
    /** The route in use. */
    var route by mutableStateOf(AudioRoute(RouteKind.EARPIECE)); private set
    /** Everything the person can pick right now (Phone, Speaker, Bluetooth devices, wired headset). */
    var available by mutableStateOf(listOf(AudioRoute(RouteKind.EARPIECE), AudioRoute(RouteKind.SPEAKER))); private set
    /** Android 12+: Bluetooth headsets cannot be used until the person allows "Nearby devices" (BLUETOOTH_CONNECT). */
    var bluetoothBlocked by mutableStateOf(false); private set
    /** Another app took the audio focus; the call goes on, this is only for information. */
    var focusLost by mutableStateOf(false); private set

    private var app: Context? = null
    private var active = false
    private var prevMode = AudioManager.MODE_NORMAL
    private val main = Handler(Looper.getMainLooper())
    private var focusReq: AudioFocusRequest? = null
    private var devCb: AudioDeviceCallback? = null
    private var commL: Any? = null            // AudioManager.OnCommunicationDeviceChangedListener (API 31+)
    private var scoRx: BroadcastReceiver? = null
    private var wantSco = false
    private var scoUp = false
    private var wake: PowerManager.WakeLock? = null

    private val audio get() = app!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun say(s: String) { main.post { try { com.kafisir.wetalks.ui.Nav.say(s) } catch (_: Exception) {} } }

    // ------------------------------------------------------------------------------------------ permission (Android 12+)
    private fun hasBluetoothPermission(c: Context) = Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun hasBluetooth(c: Context) = c.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

    /** True when this phone has Bluetooth but the app may not use it yet (Android 12+). */
    fun needsBluetoothPermission(c: Context) = Build.VERSION.SDK_INT >= 31 && hasBluetooth(c) && !hasBluetoothPermission(c)

    /** Runtime permissions to ask together with microphone / camera when a call starts. Denying it never blocks the call. */
    fun extraPermissions(c: Context): List<String> = if (needsBluetoothPermission(c)) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /** The person just answered the Bluetooth permission question: look for headsets again (a connected one is switched to). */
    fun onBluetoothPermissionResult() { main.post { refresh() } }

    // ------------------------------------------------------------------------------------------ start / stop
    @Synchronized fun start(ctx: Context, video: Boolean) {
        app = ctx.applicationContext
        if (active) stopLocked()
        active = true; focusLost = false
        val am = audio
        prevMode = am.mode; am.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus(am)
        registerCallbacks(am)
        bluetoothBlocked = needsBluetoothPermission(app!!)
        val list = scan(); available = list
        applyOrFallback(CallRoutes.preferred(video, list), video)
        updateProximity()
    }

    @Synchronized fun stop() { stopLocked() }

    private fun stopLocked() {
        releaseProximity()
        if (!active) return
        active = false
        main.removeCallbacks(scoTimeout)
        val am = audio
        try { devCb?.let { am.unregisterAudioDeviceCallback(it) } } catch (_: Exception) {}
        devCb = null
        if (Build.VERSION.SDK_INT >= 31) {
            try { (commL as? AudioManager.OnCommunicationDeviceChangedListener)?.let { am.removeOnCommunicationDeviceChangedListener(it) } } catch (_: Exception) {}
            try { am.clearCommunicationDevice() } catch (_: Exception) {}
        }
        commL = null
        try { scoRx?.let { app?.unregisterReceiver(it) } } catch (_: Exception) {}
        scoRx = null
        stopSco(am)
        try { am.isSpeakerphoneOn = false } catch (_: Exception) {}
        abandonFocus(am)
        try { am.mode = prevMode } catch (_: Exception) {}
        route = AudioRoute(RouteKind.EARPIECE); available = listOf(AudioRoute(RouteKind.EARPIECE), AudioRoute(RouteKind.SPEAKER))
        focusLost = false
    }

    // ------------------------------------------------------------------------------------------ choosing a route
    /** The person picked a route in the sheet. */
    @Synchronized fun select(r: AudioRoute) {
        if (!active) return
        if (r.kind == RouteKind.BLUETOOTH && needsBluetoothPermission(app!!)) { say("Allow Bluetooth to use a headset. Using the phone for now."); return }
        applyOrFallback(r, Calls.video)
    }

    /** Old one-tap speaker on / off. */
    @Synchronized fun toggleSpeaker() { if (active) applyOrFallback(CallRoutes.afterSpeakerToggle(route, available, Calls.video), Calls.video) }

    /** Video started (or the other side turned theirs on): move to the speaker unless a headset is in use. */
    @Synchronized fun onVideo() { if (active) applyOrFallback(CallRoutes.afterVideoStarted(route, available), true) }

    private fun applyOrFallback(r: AudioRoute, video: Boolean) {
        if (apply(r)) return
        val fb = CallRoutes.fallback(video, available, r)
        if (apply(fb)) say("Could not switch to " + CallRoutes.label(r) + ". Using " + CallRoutes.label(fb).lowercase() + ".")
    }

    private fun apply(r: AudioRoute): Boolean {
        val am = audio
        val ok = try { if (Build.VERSION.SDK_INT >= 31) applyModern(am, r) else applyLegacy(am, r) } catch (_: Exception) { false }
        if (ok) applyRoute(r)
        return ok
    }

    private fun applyRoute(r: AudioRoute) { route = r; updateProximity() }

    // Android 12+ -------------------------------------------------------------------------
    private fun matches(d: AudioDeviceInfo, r: AudioRoute): Boolean = when (r.kind) {
        RouteKind.EARPIECE -> d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        RouteKind.SPEAKER -> d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        RouteKind.BLUETOOTH -> isBluetooth(d.type) && d.id == r.id
        RouteKind.WIRED -> isWired(d.type) && d.id == r.id
    }

    private fun applyModern(am: AudioManager, r: AudioRoute): Boolean {
        val target = am.availableCommunicationDevices.firstOrNull { matches(it, r) } ?: return false
        return am.setCommunicationDevice(target)
    }

    // Android 9 to 11 ---------------------------------------------------------------------
    @Suppress("DEPRECATION")
    private fun applyLegacy(am: AudioManager, r: AudioRoute): Boolean {
        when (r.kind) {
            RouteKind.SPEAKER -> { stopSco(am); am.isSpeakerphoneOn = true }
            RouteKind.EARPIECE, RouteKind.WIRED -> { stopSco(am); am.isSpeakerphoneOn = false }
            RouteKind.BLUETOOTH -> {
                if (!am.isBluetoothScoAvailableOffCall) return false
                am.isSpeakerphoneOn = false
                wantSco = true; scoUp = false
                am.startBluetoothSco(); am.isBluetoothScoOn = true
                main.removeCallbacks(scoTimeout); main.postDelayed(scoTimeout, 7000)     // a headset that never connects must not leave the call silent
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun stopSco(am: AudioManager) {
        main.removeCallbacks(scoTimeout)
        if (wantSco || scoUp) { try { am.isBluetoothScoOn = false; am.stopBluetoothSco() } catch (_: Exception) {} }
        wantSco = false; scoUp = false
    }

    private val scoTimeout = Runnable { synchronized(this) { if (active && wantSco && !scoUp && route.kind == RouteKind.BLUETOOTH) scoLost("Bluetooth did not connect.") } }

    private fun scoLost(msg: String) {
        val lost = route
        stopSco(audio)
        val fb = CallRoutes.fallback(Calls.video, available, lost)
        if (apply(fb)) say(msg + " Using " + CallRoutes.label(fb).lowercase() + ".")
    }

    private fun onSco(i: Intent) {
        synchronized(this) {
            if (!active) return
            when (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> { scoUp = true; main.removeCallbacks(scoTimeout) }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> if (scoUp && wantSco && route.kind == RouteKind.BLUETOOTH) scoLost("Bluetooth disconnected.")
                AudioManager.SCO_AUDIO_STATE_ERROR -> if (wantSco && route.kind == RouteKind.BLUETOOTH) scoLost("Bluetooth could not connect.")
            }
        }
    }

    // ------------------------------------------------------------------------------------------ devices coming and going
    private fun isBluetooth(t: Int) = t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_HEARING_AID || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)
    private fun isWired(t: Int) = t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun deviceName(d: AudioDeviceInfo): String = d.productName?.toString()?.trim().orEmpty()

    /** What is connected now. */
    private fun scan(): List<AudioRoute> {
        val am = audio
        val found = ArrayList<AudioRoute>()
        var earpiece = false
        if (Build.VERSION.SDK_INT >= 31) {
            for (d in am.availableCommunicationDevices) when {
                d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> earpiece = true
                isBluetooth(d.type) -> found += AudioRoute(RouteKind.BLUETOOTH, d.id, deviceName(d))
                isWired(d.type) -> found += AudioRoute(RouteKind.WIRED, d.id, deviceName(d))
            }
        } else {
            // before Android 12 a Bluetooth headset shows up as SCO (calls) and / or A2DP (music): one entry per headset
            val seen = HashSet<String>()
            for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) when {
                d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> earpiece = true
                isBluetooth(d.type) || d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                    val key = (if (Build.VERSION.SDK_INT >= 28) d.address else "").ifEmpty { deviceName(d) }
                    if (seen.add(key)) found += AudioRoute(RouteKind.BLUETOOTH, d.id, deviceName(d))
                }
                isWired(d.type) -> found += AudioRoute(RouteKind.WIRED, d.id, deviceName(d))
            }
        }
        val phone = app!!.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        return CallRoutes.normalize(found, earpiece || phone)
    }

    /** A headset was plugged in / connected / removed (or the permission changed): update the list and switch if the rules say so. */
    @Synchronized private fun refresh() {
        if (!active) return
        bluetoothBlocked = needsBluetoothPermission(app!!)
        val prev = available; val now = scan()
        available = now
        val next = CallRoutes.afterDevicesChanged(prev, now, route, Calls.video)
        if (!CallRoutes.same(next, route)) {
            val lost = CallRoutes.isHeadset(route.kind) && !CallRoutes.contains(now, route)
            if (apply(next)) {
                if (lost) say("Headset disconnected. Using " + CallRoutes.label(next).lowercase() + ".")
            } else applyOrFallback(CallRoutes.preferred(Calls.video, now.filter { !CallRoutes.same(it, next) }), Calls.video)
        } else if (next != route) applyRoute(next)            // same device, new name / id
    }

    private fun registerCallbacks(am: AudioManager) {
        devCb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { refresh() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { refresh() }
        }
        am.registerAudioDeviceCallback(devCb, main)
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnCommunicationDeviceChangedListener { d -> synchronized(this) { mirror(d) } }
            commL = l
            am.addOnCommunicationDeviceChangedListener(app!!.mainExecutor, l)
        } else {
            val rx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { onSco(i) } }
            scoRx = rx
            val f = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            try { if (Build.VERSION.SDK_INT >= 33) app!!.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED) else app!!.registerReceiver(rx, f) } catch (_: Exception) { scoRx = null }
        }
    }

    /** Android 12+: the system moved the call audio (for example the headset button, or another app): show what is really in use. */
    private fun mirror(d: AudioDeviceInfo?) {
        if (!active || d == null) return
        val r = available.firstOrNull { matches(d, it) } ?: return
        if (!CallRoutes.same(r, route)) applyRoute(r)
    }

    // ------------------------------------------------------------------------------------------ audio focus
    private val focusL = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            when (CallRoutes.focusAction(change)) {
                FocusAction.LOST -> focusLost = true               // never end the call because of this
                FocusAction.RESTORE -> { focusLost = false; if (active) apply(route) }   // take the route back
                FocusAction.NONE -> {}
            }
        }
    }

    private fun requestFocus(am: AudioManager) {
        try {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusL, main).build()
            focusReq = req
            am.requestAudioFocus(req)
        } catch (_: Exception) {}
    }

    private fun abandonFocus(am: AudioManager) {
        try { focusReq?.let { am.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusReq = null
    }

    // ------------------------------------------------------------------------------------------ proximity (screen off at the ear)
    /** Hooks from [Calls]: the phase, video flag, minimized flag or foreground flag changed. */
    fun onCallState() { updateProximity() }

    @Synchronized private fun updateProximity() {
        val c = app
        if (c == null || !active) { releaseProximity(); return }
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val sensor = try { pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) } catch (_: Exception) { false }
        // "shown" = the call screen is in front. While the screen is off at the ear the app is stopped too, so a stopped app with a dark screen still counts;
        // a stopped app with the screen on means the person went to the home screen or another app: let go.
        val shown = !Calls.minimized && (Calls.foreground || !pm.isInteractive)
        if (ProximityRule.shouldHold(Calls.phase, Calls.video, route.kind, sensor, shown)) acquireProximity(pm) else releaseProximity()
    }

    private fun acquireProximity(pm: PowerManager) {
        try {
            val w = wake ?: pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "WeTalks:callProximity").also { it.setReferenceCounted(false); wake = it }
            if (!w.isHeld) w.acquire(6 * 3600_000L)         // safety timeout: it can never be held forever
        } catch (_: Exception) {}
    }

    private fun releaseProximity() {
        try {
            val w = wake
            // wait until the phone is away from the face before the screen comes back, so a cheek does not hang up the call
            if (w != null && w.isHeld) w.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        } catch (_: Exception) {}
    }
}
