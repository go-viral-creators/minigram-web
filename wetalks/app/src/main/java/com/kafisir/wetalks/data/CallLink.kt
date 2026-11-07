package com.kafisir.wetalks.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters

/** What the call screen shows about the network. */
object CallNetState {
    /** 0 = not measured yet, 1..4 = signal bars. */
    var bars by mutableIntStateOf(0)
    var reconnecting by mutableStateOf(false)
    /** Video was paused automatically because the network is poor (the person's own camera choice is not touched). */
    var videoPaused by mutableStateOf(false)
    fun reset() { bars = 0; reconnecting = false; videoPaused = false }
}

/** Signalling work (sealing and writing ICE candidates) runs here, never on the WebRTC threads. */
object CallSignal {
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "wt-signal").apply { isDaemon = true } }
}

/**
 * Extra ICE servers (TURN) that can be changed without a new app: `config/turn` in the Realtime Database (read-only for the app).
 * Shape: `config/turn/<any key>` = { urls: "turn:host:3478,turns:host:443" or a list, username, credential }.
 * The last good copy is kept on the phone, so a call placed on a bad network still has it. Nothing is invented: no node, no extra servers.
 */
object CallConfig {
    private const val PREF = "wt_callcfg"

    fun extraServers(app: Context): List<PeerConnection.IceServer> = try {
        val arr = JSONArray(app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("turn", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val urls = o.getJSONArray("urls").let { a -> (0 until a.length()).map { a.getString(it) } }
            val b = PeerConnection.IceServer.builder(urls)
            if (o.has("u")) b.setUsername(o.getString("u")); if (o.has("c")) b.setPassword(o.getString("c"))
            b.createIceServer()
        }
    } catch (_: Exception) { emptyList() }

    /** Reads `config/turn` and keeps it for the next call. Safe to call often; errors (rules not deployed yet, offline) keep the old copy. */
    suspend fun refresh(app: Context) {
        try {
            val s = Repo.db.getReference("config/turn").get().await()
            val raw = s.children.map { c ->
                mapOf("urls" to c.child("urls").value, "username" to c.child("username").getValue(String::class.java), "credential" to c.child("credential").getValue(String::class.java))
            }
            val arr = JSONArray()
            IceConfig.parse(raw).forEach { sp ->
                arr.put(JSONObject().put("urls", JSONArray(sp.urls)).also { o -> sp.username?.let { o.put("u", it) }; sp.credential?.let { o.put("c", it) } })
            }
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("turn", arr.toString()).apply()
        } catch (_: Exception) {}
    }
}

/**
 * Glue between one running call and the pure logic ([QualityMonitor], [StatsReader], [ReconnectMachine]):
 * samples WebRTC stats every ~2 s (off the main thread), shows the bars, pauses / resumes video on poor networks, sets sender limits,
 * watches the default network, and carries out the reconnect machine's actions.
 */
class CallLink(private val app: Context, private val caller: Boolean, private val host: Host) {
    interface Host {
        fun pc(): PeerConnection?
        fun rtcConfig(): PeerConnection.RTCConfiguration?
        /** Caller only: make an ICE-restart offer and send it through calls/<id>/neg. */
        suspend fun sendRestartOffer(relay: Boolean)
        fun hasLocalVideo(): Boolean
        fun camOn(): Boolean
        /** True once the call is running (stats are only meaningful then). */
        fun live(): Boolean
        fun onReconnecting(on: Boolean)
        fun giveUp()
    }

    private val lock = Any()
    private val machine = ReconnectMachine(caller)
    private val monitor = QualityMonitor()
    private val reader = StatsReader()
    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var cm: ConnectivityManager? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastCaps: SendCaps? = null
    @Volatile private var paused = false
    private fun now() = SystemClock.elapsedRealtime()

    fun start() {
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default); scope = sc
        sc.launch { while (isActive) { delay(500); run(synchronized(lock) { machine.onTick(now()) }) } }
        sc.launch { while (isActive) { delay(2000); sample() } }
        try {
            val c = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager; cm = c
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var armed = false; private var capKey = ""; private var addrKey = ""
                override fun onAvailable(n: Network) { if (!armed) { armed = true; return }; netChanged(true) }
                override fun onLost(n: Network) { netChanged(false) }
                override fun onCapabilitiesChanged(n: Network, nc: NetworkCapabilities) {
                    val k = n.toString() + (if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "w" else "") + (if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "c" else "") + (if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) "e" else "") + (if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) "v" else "")
                    if (capKey.isNotEmpty() && k != capKey) netChanged(true); capKey = k
                }
                override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                    val k = lp.linkAddresses.map { it.address.hostAddress }.sorted().joinToString(",")
                    if (addrKey.isNotEmpty() && k != addrKey) netChanged(true); addrKey = k
                }
            }
            c.registerDefaultNetworkCallback(cb); netCb = cb
        } catch (_: Exception) {}
    }

    fun stop() {
        try { netCb?.let { cm?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        netCb = null
        scope?.cancel(); scope = null
        main.removeCallbacksAndMessages(null)
        CallNetState.reset()
    }

    private fun netChanged(available: Boolean) { run(synchronized(lock) { machine.onNetwork(available, now()) }) }

    /** ICE state from the peer connection (any thread). */
    fun onIce(ev: IceEv) { run(synchronized(lock) { machine.onIce(ev, now()) }) }

    /** After a renegotiation or a new video track: put the current limits on the (possibly new) senders again. */
    fun reapply() { val c = lastCaps ?: CallQuality.caps(NetLevel.EXCELLENT, null); scope?.launch { applySenders(c, paused) } }

    private fun run(actions: List<ReconnectAction>) {
        for (a in actions) when (a) {
            is ReconnectAction.ShowReconnecting -> main.post { CallNetState.reconnecting = true; CallNetState.bars = 1; host.onReconnecting(true) }
            is ReconnectAction.HideReconnecting -> main.post { CallNetState.reconnecting = false; host.onReconnecting(false) }
            is ReconnectAction.GiveUp -> main.post { host.giveUp() }
            is ReconnectAction.RelayOff -> scope?.launch { applyTransport(false) }
            is ReconnectAction.RestartIce -> scope?.launch {
                applyTransport(a.relay)
                try { host.sendRestartOffer(a.relay) } catch (_: kotlinx.coroutines.CancellationException) { } catch (_: Exception) {}
            }
        }
    }

    /** ALL = normal; RELAY = only TURN candidates (for networks where a direct path or STUN does not work). */
    private fun applyTransport(relay: Boolean) {
        try {
            val p = host.pc() ?: return; val cfg = host.rtcConfig() ?: return
            val want = if (relay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            if (cfg.iceTransportsType != want) { cfg.iceTransportsType = want; p.setConfiguration(cfg) }
        } catch (_: Exception) {}
    }

    private fun sample() {
        if (!host.live()) return
        if (synchronized(lock) { machine.state != ReconnectMachine.State.IDLE }) return      // stats mean little while reconnecting
        val p = host.pc() ?: return
        try {
            p.getStats { report ->
                // runs on a WebRTC thread: keep it short, hand the rest to our own scope
                val rows = report.statsMap.values.map { StatRow(it.type, it.id, it.members) }
                val sending = host.hasLocalVideo() && host.camOn()
                val u = synchronized(lock) { monitor.feed(reader.read(rows), sending) }
                main.post { CallNetState.bars = u.level.bars; CallNetState.videoPaused = u.videoPaused }
                if (u.capsChanged || u.video != VideoAction.NONE) {
                    lastCaps = u.caps; paused = u.videoPaused
                    scope?.launch { applySenders(u.caps, u.videoPaused) }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Audio first: the audio encoding gets the highest bitrate / network priority and its own small cap; video is capped by level
     * (bitrate, resolution, frame rate), keeps its frame rate when the encoder has to give something up, and is switched off completely when paused.
     */
    private fun applySenders(caps: SendCaps, pauseVideo: Boolean) {
        val p = host.pc() ?: return
        try {
            for (s in p.senders) {
                val kind = s.track()?.kind() ?: continue
                val prm = s.parameters ?: continue
                val enc = prm.encodings
                if (enc.isEmpty()) continue
                if (kind == "audio") {
                    val old = enc.map { it.networkPriority }
                    for (e in enc) { e.bitratePriority = 4.0; e.networkPriority = 4; e.maxBitrateBps = caps.audioMaxBps }
                    if (!s.setParameters(prm)) {
                        // some builds number the priorities differently: keep the bitrate settings, drop the network priority
                        val p2 = s.parameters ?: continue
                        for ((i, e) in p2.encodings.withIndex()) { e.bitratePriority = 4.0; e.networkPriority = old.getOrElse(i) { e.networkPriority }; e.maxBitrateBps = caps.audioMaxBps }
                        s.setParameters(p2)
                    }
                } else if (kind == "video") {
                    for (e in enc) {
                        e.maxBitrateBps = caps.videoMaxBps; e.scaleResolutionDownBy = caps.videoScaleDown; e.maxFramerate = caps.videoFps
                        e.bitratePriority = 1.0; e.active = !pauseVideo
                    }
                    prm.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    s.setParameters(prm)
                }
            }
        } catch (_: Exception) {}
    }
}
