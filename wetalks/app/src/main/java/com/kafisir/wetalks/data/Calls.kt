package com.kafisir.wetalks.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.ServiceCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.kafisir.wetalks.MainActivity
import com.kafisir.wetalks.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.webrtc.*
import kotlin.coroutines.resume

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE }

/**
 * 1-to-1 voice and video calls.
 * Media: WebRTC (DTLS-SRTP, always encrypted). Signalling: Realtime Database under calls/{id}; every offer, answer and
 * ICE candidate is additionally sealed with the chat's end-to-end key, so the database can't swap in its own fingerprints.
 */
object Calls {
    private var phaseState by mutableStateOf(CallPhase.IDLE)
    var phase: CallPhase get() = phaseState; private set(v) { phaseState = v; CallAudio.onCallState() }        // audio hook: proximity sensor follows the call phase
    var peer by mutableStateOf(""); private set
    private var videoState by mutableStateOf(false)
    var video: Boolean get() = videoState; private set(v) { videoState = v; CallAudio.onCallState() }          // audio hook: no proximity sensor in video
    var muted by mutableStateOf(false); private set
    var camOn by mutableStateOf(true); private set
    /** True while the sound goes to the loudspeaker (the route itself lives in [CallAudio]). */
    val speaker: Boolean get() = CallAudio.route.kind == RouteKind.SPEAKER
    var frontCam by mutableStateOf(true); private set
    var connectedAt by mutableStateOf(0L); private set
    /** The call keeps running while its screen is hidden: a small floating window shows instead, so the person can keep chatting. */
    private var minimizedState by mutableStateOf(false)
    var minimized: Boolean get() = minimizedState; set(v) { minimizedState = v; CallAudio.onCallState() }      // audio hook
    var note by mutableStateOf(""); private set
    var localVideo by mutableStateOf<VideoTrack?>(null); private set
    var remoteVideo by mutableStateOf<VideoTrack?>(null); private set
    var foreground = false
        set(v) { field = v; CallAudio.onCallState() }          // audio hook: proximity lock is let go when the person leaves the app

    val egl: EglBase by lazy { EglBase.create() }

    private lateinit var app: Context
    private val scope get() = Repo.scope
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null

    private var callId = ""
    private var caller = false
    private var key: ByteArray? = null
    private var pendingOffer: String? = null
    private val queuedIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var listeners = ArrayList<Pair<com.google.firebase.database.Query, Any>>()
    private var timeout: Job? = null
    private var finishing = false
    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var serverOffset = 0L
    private var incomingListening = false
    @Volatile private var callWritten = false
    private var peerRang = false                  // the other phone reported "ringing" (it was reachable)
    private var callStartTs = 0L                  // server time the call began (for the call history)
    private fun serverNow() = System.currentTimeMillis() + serverOffset
    private val pendingIce = ArrayList<String>()

    private val servers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // public relay for networks where a direct path is impossible (media stays DTLS-SRTP encrypted end to end)
        PeerConnection.IceServer.builder(listOf("turn:openrelay.metered.ca:80", "turn:openrelay.metered.ca:443", "turn:openrelay.metered.ca:443?transport=tcp"))
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    private const val MISSED_CH = "missed_calls"
    fun init(c: Context) { app = c.applicationContext }

    val peerRef get() = ChatRef(Repo.dmId(peer), Kind.DM, peer)

    // ------------------------------------------------------------------------------------------ incoming listener
    private var incomingRef: com.google.firebase.database.DatabaseReference? = null
    private var incomingL: ChildEventListener? = null
    private var offsetL: Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>? = null
    /** Called on sign-out so the next account gets its own incoming-call listener. */
    fun reset() {
        try { incomingL?.let { incomingRef?.removeEventListener(it) }; offsetL?.let { it.first.removeEventListener(it.second) } } catch (_: Exception) {}
        incomingL = null; incomingRef = null; offsetL = null; incomingListening = false
        if (phase != CallPhase.IDLE) try { teardown() } catch (_: Exception) {}
    }

    /** True while the incoming-call listener is attached. */
    fun isListening() = incomingListening && incomingL != null

    fun listen() {
        if ((incomingListening && incomingL != null) || Repo.uid.isEmpty()) return
        incomingListening = true
        val offRef = Repo.db.getReference(".info/serverTimeOffset")
        val offL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { serverOffset = s.getValue(Long::class.java) ?: 0L }
            override fun onCancelled(e: DatabaseError) {}
        }
        offRef.addValueEventListener(offL); offsetL = offRef to offL
        incomingRef = Repo.db.getReference("incoming_calls/${Repo.uid}")
        incomingL = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { val id = s.key ?: return; val f = s.child("from").getValue(String::class.java); val v = s.child("video").getValue(Boolean::class.java) ?: false; val t = s.child("ts").getValue(Long::class.java) ?: 0L; scope.launch { onIncoming(id, f, v, t) } }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { incomingListening = false }
        }
        incomingRef!!.addChildEventListener(incomingL!!)
    }

    /**
     * A ring request arrived. [incFrom]/[incVideo]/[incTs] come from the request itself, so a call that was already over (my phone was off, offline or the app
     * closed) can still be shown as a Missed call: the request stays in my inbox until I read it.
     */
    private suspend fun onIncoming(id: String, incFrom: String?, incVideo: Boolean, incTs: Long) {
        val uid = Repo.uid
        try {
            // The caller deletes the call a few seconds after it ends, and a deleted call cannot be read (the rules look at its from/to). That read failing
            // used to abort here silently, so a call that ended while this phone was off was never logged and never announced: treat "unreadable" as "gone".
            val s = try { Repo.db.getReference("calls/$id").get().await() } catch (e: Exception) { null }
            Repo.db.getReference("incoming_calls/$uid/$id").removeValue()
            val exists = s?.exists() == true
            val state = s?.child("state")?.getValue(String::class.java)
            val from = (s?.child("from")?.getValue(String::class.java) ?: incFrom) ?: return
            val ts = s?.child("ts")?.getValue(Long::class.java) ?: incTs
            if (exists && s!!.child("to").getValue(String::class.java) != uid) return
            val tooOld = serverNow() - ts > 60_000
            if (!exists || state == "missed" || (state == "ringing" && tooOld)) { if (from !in Repo.blockedUsers.value) recordMissed(id, from, s?.child("video")?.getValue(Boolean::class.java) ?: incVideo, if (ts > 0) ts else serverNow()); return }
            if (state != "ringing") return                      // answered / declined / ended elsewhere
            if (from in Repo.blockedUsers.value) { setState(id, "declined"); return }
            if (phase != CallPhase.IDLE) { setState(id, "busy"); recordMissed(id, from, s!!.child("video").getValue(Boolean::class.java) ?: incVideo, ts); return }
            callId = id; caller = false; peer = from; video = s.child("video").getValue(Boolean::class.java) ?: false; callStartTs = ts; peerRang = true
            pendingOffer = s.child("offer").getValue(String::class.java)
            resetFlags(); minimized = false; phase = CallPhase.INCOMING; note = ""
            key = Repo.chatKey(ChatRef(Repo.dmId(from), Kind.DM, from))
            Repo.db.getReference("calls/$id/ringing").setValue(true)
            watchState(); watchNeg(); startRinging(); notifyIncoming()
            timeout = scope.launch { delay(55_000); if (phase == CallPhase.INCOMING) finish("missed", notifyPeer = true, remote = false) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------------------------------ outgoing
    suspend fun start(to: String, withVideo: Boolean) {
        if (phase != CallPhase.IDLE) return
        val k = Repo.chatKey(ChatRef(Repo.dmId(to), Kind.DM, to)) ?: throw IllegalStateException("They need to open WeTalks once before you can call them.")
        key = k; peer = to; video = withVideo; caller = true; resetFlags(); callWritten = false; peerRang = false; callStartTs = serverNow(); synchronized(pendingIce) { pendingIce.clear() }; note = "Calling…"; minimized = false; phase = CallPhase.OUTGOING
        callId = Repo.db.getReference("calls").push().key!!
        try {
            ensureFactory(); prepareAudio(); startForegroundService()
            buildPeer(withVideo)
            val offer = pc!!.createOfferSuspend(); pc!!.setLocalSuspend(offer)
            Repo.db.getReference("calls/$callId").setValue(hashMapOf("from" to Repo.uid, "to" to to, "video" to withVideo, "ts" to ServerValue.TIMESTAMP, "state" to "ringing",
                "offer" to Crypto.sealText(k, JSONObject().put("type", offer.type.canonicalForm()).put("sdp", offer.description).toString()))).await()
            callWritten = true
            val early = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
            early.forEach { Repo.db.getReference("calls/$callId/ice_caller").push().setValue(it) }
            Repo.db.getReference("incoming_calls/$to/$callId").setValue(hashMapOf("from" to Repo.uid, "video" to withVideo, "ts" to ServerValue.TIMESTAMP)).await()
            watchState(); watchNeg()
            watchIce("ice_callee")
            startRingback()
            timeout = scope.launch {
                delay(12_000); if (phase == CallPhase.OUTGOING && note == "Calling…") note = "Waiting for them to open WeTalks…"
                delay(38_000); if (phase == CallPhase.OUTGOING) finish("missed", notifyPeer = true, remote = false)
            }
        } catch (e: Throwable) {
            // never leave a half-created call behind
            try { Repo.db.getReference("calls/$callId").removeValue(); Repo.db.getReference("incoming_calls/$to/$callId").removeValue() } catch (_: Exception) {}
            teardown(); throw (if (e is Exception) e else IllegalStateException(e.javaClass.simpleName + ": " + (e.message ?: "")))
        }
    }

    suspend fun accept() {
        if (phase != CallPhase.INCOMING) return
        stopRinging(); timeout?.cancel(); cancelNotification()
        phase = CallPhase.CONNECTING; note = "Connecting…"
        try {
            val k = key ?: throw IllegalStateException("No key")
            ensureFactory(); prepareAudio(); startForegroundService()
            buildPeer(video)
            val offerJson = JSONObject(Crypto.openText(k, pendingOffer ?: throw IllegalStateException("Call expired")))
            pc!!.setRemoteSuspend(SessionDescription(SessionDescription.Type.fromCanonicalForm(offerJson.getString("type")), offerJson.getString("sdp")))
            remoteSet = true; flushIce()
            val ans = pc!!.createAnswerSuspend(); pc!!.setLocalSuspend(ans)
            Repo.db.getReference("calls/$callId").updateChildren(mapOf("answer" to Crypto.sealText(k, JSONObject().put("type", ans.type.canonicalForm()).put("sdp", ans.description).toString()), "state" to "accepted")).await()
            watchIce("ice_caller")
        } catch (e: Exception) { finish("ended", notifyPeer = true, remote = false); throw e }
    }

    fun decline() { if (phase == CallPhase.INCOMING) finish("declined", notifyPeer = true, remote = false) }
    fun hangUp() { if (phase != CallPhase.IDLE) finish(if (connectedAt > 0) "ended" else if (caller) "missed" else "declined", notifyPeer = true, remote = false, byMe = caller && connectedAt == 0L) }

    // ------------------------------------------------------------------------------------------ controls
    fun toggleMute() { muted = !muted; audioTrack?.setEnabled(!muted) }
    fun toggleCamera() { camOn = !camOn; videoTrack?.setEnabled(camOn) }
    fun toggleSpeaker() { CallAudio.toggleSpeaker() }
    @Volatile private var flipping = false
    /** Front / back camera. Taps while a switch is still running are ignored (that is what made it need several taps before). */
    fun flipCamera() {
        val c = capturer ?: return
        if (flipping) return
        flipping = true
        scope.launch { delay(2500); flipping = false }
        // phones with several back lenses (wide, tele...) made the plain "next camera" switch cycle through all of them;
        // ask for the exact camera we want instead: the other side, main lens
        val en = Camera2Enumerator(app)
        val target = if (frontCam) en.deviceNames.firstOrNull { en.isBackFacing(it) } else en.deviceNames.firstOrNull { en.isFrontFacing(it) }
        val h = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { scope.launch(Dispatchers.Main) { frontCam = front; flipping = false } }
            override fun onCameraSwitchError(e: String?) { scope.launch(Dispatchers.Main) { flipping = false } }
        }
        if (target != null) c.switchCamera(h, target) else c.switchCamera(h)
    }
    /** true = video wants the loudspeaker (a headset in use stays); false = back to the best route. The route picker lives in [CallAudio]. */
    private fun applySpeaker(on: Boolean) { if (on) CallAudio.onVideo() }

    // ------------------------------------------------------------------------------------------ peer connection
    private fun resetFlags() { muted = false; camOn = true; frontCam = true; connectedAt = 0L; remoteSet = false; queuedIce.clear(); finishing = false; remoteVideo = null; localVideo = null }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun buildPeer(withVideo: Boolean) {
        val f = factory!!
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                scope.launch(Dispatchers.Main) {
                    when (s) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> if (phase != CallPhase.IDLE && connectedAt == 0L) {
                            connectedAt = System.currentTimeMillis(); phase = CallPhase.ACTIVE; note = ""; timeout?.cancel(); stopRingback()
                            if (video) applySpeaker(true)
                        }
                        PeerConnection.IceConnectionState.FAILED -> finish(if (connectedAt > 0) "ended" else "missed", notifyPeer = true, remote = false, why = "Connection failed")
                        PeerConnection.IceConnectionState.DISCONNECTED -> if (phase == CallPhase.ACTIVE) { note = "Reconnecting…"; scope.launch { delay(12_000); if (pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) finish("ended", true, false, "Connection lost") } }
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                val k = key ?: return
                val j = JSONObject().put("m", c.sdpMid).put("i", c.sdpMLineIndex).put("c", c.sdp).toString()
                val sealed = Crypto.sealText(k, j)
                if (caller && !callWritten) synchronized(pendingIce) { pendingIce.add(sealed) }     // the rules only accept candidates once the call exists
                else Repo.db.getReference("calls/$callId/${if (caller) "ice_caller" else "ice_callee"}").push().setValue(sealed)
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            override fun onTrack(t: RtpTransceiver?) {
                val tr = t?.receiver?.track()
                if (tr is VideoTrack) scope.launch(Dispatchers.Main) { remoteVideo = tr }
            }
        }) ?: throw IllegalStateException("Could not start the call engine")

        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("a0", audioSource).also { pc!!.addTrack(it, listOf("s0")) }
        if (withVideo) startVideoTrack()
    }

    /** Opens the front camera and adds it to the call. */
    private fun startVideoTrack() {
        val f = factory!!
        val en = Camera2Enumerator(app)
        val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: throw IllegalStateException("No camera found")
        val cap = en.createCapturer(name, null) ?: throw IllegalStateException("Camera not available")
        capturer = cap; frontCam = en.isFrontFacing(name); flipping = false
        surfaceHelper = SurfaceTextureHelper.create("wt-capture", egl.eglBaseContext)
        videoSource = f.createVideoSource(false).also { cap.initialize(surfaceHelper, app, it.capturerObserver) }
        cap.startCapture(960, 540, 24)
        videoTrack = f.createVideoTrack("v0", videoSource).also { pc!!.addTrack(it, listOf("s0")); localVideo = it }
        camOn = true
    }

    // ------------------------------------------------------------------------------------------ switching a voice call to video (and back) while talking
    private var negSeq = 0
    private var awaitingNeg = -1

    /** Turns my camera on. In a voice call this adds video to the running call; the other side's camera stays off until they choose. */
    suspend fun enableVideo() {
        if (phase != CallPhase.ACTIVE && phase != CallPhase.CONNECTING) return
        val p = pc ?: return
        if (videoTrack != null) { camOn = true; videoTrack?.setEnabled(true); video = true; return }
        startVideoTrack(); video = true; applySpeaker(true)
        val offer = p.createOfferSuspend(); p.setLocalSuspend(offer)
        negSeq++; awaitingNeg = negSeq
        val k = key ?: return
        Repo.db.getReference("calls/$callId/neg").setValue(hashMapOf("by" to Repo.uid, "kind" to "offer", "n" to negSeq, "sdp" to Crypto.sealText(k, JSONObject().put("type", offer.type.canonicalForm()).put("sdp", offer.description).toString()))).await()
    }

    private fun watchNeg() {
        val q = Repo.db.getReference("calls/$callId/neg")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (!s.exists() || s.child("by").getValue(String::class.java) == Repo.uid) return
                val k = key ?: return
                val kind = s.child("kind").getValue(String::class.java) ?: return
                val n = s.child("n").getValue(Long::class.java)?.toInt() ?: return
                val j = try { JSONObject(Crypto.openText(k, s.child("sdp").getValue(String::class.java) ?: return)) } catch (_: Exception) { return }
                val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(j.getString("type")), j.getString("sdp"))
                scope.launch {
                    try {
                        val p = pc ?: return@launch
                        if (kind == "offer") {
                            val sec = desc.description.substringAfter("m=video", "")
                            if (sec.contains("a=sendrecv") || sec.contains("a=sendonly")) { video = true; applySpeaker(true) }
                            p.setRemoteSuspend(desc)
                            val ans = p.createAnswerSuspend(); p.setLocalSuspend(ans)
                            Repo.db.getReference("calls/$callId/neg").setValue(hashMapOf("by" to Repo.uid, "kind" to "answer", "n" to n, "sdp" to Crypto.sealText(k, JSONObject().put("type", ans.type.canonicalForm()).put("sdp", ans.description).toString())))
                        } else if (kind == "answer" && n == awaitingNeg) { awaitingNeg = -1; p.setRemoteSuspend(desc) }
                    } catch (_: Exception) {}
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        q.addValueEventListener(l); listeners += q to l
    }

    private fun flushIce() { queuedIce.forEach { pc?.addIceCandidate(it) }; queuedIce.clear() }

    private fun watchIce(node: String) {
        val q = Repo.db.getReference("calls/$callId/$node")
        val l = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                val k = key ?: return
                try {
                    val j = JSONObject(Crypto.openText(k, s.getValue(String::class.java) ?: return))
                    val c = IceCandidate(j.getString("m"), j.getInt("i"), j.getString("c"))
                    if (remoteSet) pc?.addIceCandidate(c) else queuedIce += c
                } catch (_: Exception) {}
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        q.addChildEventListener(l); listeners += q to l
    }

    private fun watchState() {
        val id = callId
        val q = Repo.db.getReference("calls/$id")
        val l = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (id != callId) return
                if (s.child("ringing").getValue(Boolean::class.java) == true) peerRang = true
                if (caller && phase == CallPhase.OUTGOING && s.child("ringing").getValue(Boolean::class.java) == true && note != "Ringing…") note = "Ringing…"
                val st = s.child("state").getValue(String::class.java) ?: if (s.exists()) return else "ended"
                if (caller && st == "accepted" && !remoteSet && phase == CallPhase.OUTGOING) {
                    val k = key ?: return
                    val a = s.child("answer").getValue(String::class.java) ?: return
                    scope.launch {
                        try {
                            val j = JSONObject(Crypto.openText(k, a))
                            pc?.setRemoteSuspend(SessionDescription(SessionDescription.Type.fromCanonicalForm(j.getString("type")), j.getString("sdp")))
                            remoteSet = true; flushIce(); phase = CallPhase.CONNECTING; note = "Connecting…"; stopRingback(); timeout?.cancel()
                        } catch (e: Exception) { finish("ended", true, false, "Could not connect") }
                    }
                }
                if (st in listOf("declined", "busy", "ended", "missed")) finish(st, notifyPeer = false, remote = true)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        q.addValueEventListener(l); listeners += q to l
    }

    private fun setState(id: String, st: String) { Repo.db.getReference("calls/$id/state").setValue(st) }

    // ------------------------------------------------------------------------------------------ missed calls (also for calls that ended while this phone was off)
    private val missedPrefs get() = app.getSharedPreferences("wt_missed", Context.MODE_PRIVATE)
    private fun alreadyNotified(id: String) = missedPrefs.getStringSet("ids", emptySet()).orEmpty().contains(id)
    private fun markNotified(id: String) { val s = missedPrefs.getStringSet("ids", emptySet()).orEmpty().toMutableList().also { it.add(id) }.takeLast(100).toSet(); missedPrefs.edit().putStringSet("ids", s).apply() }

    /** A call that ended before this phone could answer it: write the line (if the caller did not already) and tell the person once. */
    private suspend fun recordMissed(id: String, from: String, video: Boolean, ts: Long) {
        val uid = Repo.uid
        try {
            val ref = Repo.db.getReference("call_log/$uid/$id")
            if (!ref.get().await().exists()) Repo.logCall(id, from, false, video, "missed", 0, ts)
        } catch (_: Exception) {}
        if (serverNow() - ts < 24 * 3_600_000L) notifyMissed(from, video, ts, id)
    }

    private fun missedChannel() { NotifChannels.ensureAll(app) }

    /** "Missed video call · 10:42 am" from the person, with a Call back button. Shown once per call. */
    fun notifyMissed(from: String, video: Boolean, ts: Long, id: String) {
        if (alreadyNotified(id)) return
        markNotified(id)
        scope.launch {
            try {
                missedChannel()
                val u = Repo.user(from).value ?: try { kotlinx.coroutines.withTimeoutOrNull(2000) { Repo.userOnce(from) } } catch (_: Exception) { null }
                val name = u?.name?.ifBlank { null } ?: "Someone"
                val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(if (ts > 0) ts else System.currentTimeMillis()))
                val kind = if (video) "video" else "voice"
                fun pi(code: Int, call: Boolean) = PendingIntent.getActivity(app, code, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("cb_uid", from).putExtra("cb_video", video).putExtra("cb_call", call), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = NotificationCompat.Builder(app, MISSED_CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(name).setContentText("Missed $kind call · $at")
                    .setCategory(NotificationCompat.CATEGORY_MISSED_CALL).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setWhen(if (ts > 0) ts else System.currentTimeMillis())
                    .setContentIntent(pi(("m" + id).hashCode(), false)).addAction(0, "Call back", pi(("b" + id).hashCode(), true)).build()
                (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(("missed" + from).hashCode(), n)
            } catch (_: SecurityException) { } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------------------------------ end
    private fun finish(state: String, notifyPeer: Boolean, remote: Boolean, why: String = "", byMe: Boolean = false) {
        if (finishing || phase == CallPhase.IDLE) return
        finishing = true
        val id = callId; val to = peer; val wasCaller = caller; val vid = video
        val dur = if (connectedAt > 0) ((System.currentTimeMillis() - connectedAt) / 1000).toInt() else 0
        if (notifyPeer) setState(id, state)
        // my own line in the call history: one per call, keyed by the call id
        val st = if (wasCaller) CallLog.outgoingStatus(dur, state, peerRang, byMe) else CallLog.incomingStatus(dur, state)
        val startTs = if (callStartTs > 0) callStartTs else serverNow()
        scope.launch {
            Repo.logCall(id, to, wasCaller, vid, st, dur, startTs)
            // nobody picked up: leave the Missed call in THEIR history too (it is waiting for them even if their app is closed or offline)
            if (wasCaller && dur == 0 && state == "missed") Repo.leaveMissedCall(to, id, vid, startTs)
        }
        if (!wasCaller && st == "missed") notifyMissed(to, vid, startTs, id)
        // the caller leaves a line in the chat ("Missed video call", "Voice call · 2:31" …)
        if (wasCaller) scope.launch {
            try {
                val status = when { dur > 0 -> "ended"; state == "busy" -> "declined"; else -> state }
                Repo.send(ChatRef(Repo.dmId(to), Kind.DM, to), "call", JSONObject().put("v", if (vid) 1 else 0).put("d", dur).put("s", status).toString(), null, if (vid) "Video call" else "Voice call", mid = id)
            } catch (_: Exception) {}
            delay(4000); Repo.db.getReference("calls/$id").removeValue()
        }
        val msg = when { why.isNotEmpty() -> why; state == "declined" && remote && wasCaller -> "Call declined"; state == "busy" -> "Busy on another call"; else -> "" }
        teardown()
        if (msg.isNotEmpty()) com.kafisir.wetalks.ui.Nav.say(msg)
    }

    private fun teardown() {
        timeout?.cancel(); stopRinging(); stopRingback(); cancelNotification()
        listeners.forEach { (q, l) -> try { if (l is ChildEventListener) q.removeEventListener(l) else if (l is ValueEventListener) q.removeEventListener(l) } catch (_: Exception) {} }
        listeners.clear()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        localVideo = null; remoteVideo = null
        videoTrack?.dispose(); videoTrack = null; videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null; audioSource?.dispose(); audioSource = null
        pc?.close(); pc?.dispose(); pc = null
        CallAudio.stop()                      // audio hook: mode, route, focus, Bluetooth, proximity lock
        try { app.stopService(Intent(app, CallService::class.java)) } catch (_: Exception) {}
        key = null; pendingOffer = null; connectedAt = 0L; flipping = false; negSeq = 0; awaitingNeg = -1
        if (phase != CallPhase.IDLE) com.kafisir.wetalks.ui.Sfx.callEnd()
        minimized = false; phase = CallPhase.IDLE; note = ""
    }

    // ------------------------------------------------------------------------------------------ audio / ring / notifications
    private fun prepareAudio() { CallAudio.start(app, video) }           // audio hook

    private var ringSilencer: android.content.BroadcastReceiver? = null
    /** Like a normal call: pressing the power or a volume button stops the ringing (the call keeps waiting to be answered). */
    fun silenceRing() { try { ringtone?.stop(); (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (_: Exception) {} }
    private fun startRinging() {
        try {
            ringSilencer?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
            ringSilencer = object : android.content.BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (phase == CallPhase.INCOMING) silenceRing() } }
            val f = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction("android.media.VOLUME_CHANGED_ACTION") }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(ringSilencer, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(ringSilencer, f)
        } catch (_: Exception) {}
        try {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.also { if (Build.VERSION.SDK_INT >= 28) it.isLooping = true; it.play() }
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0))
        } catch (_: Exception) {}
    }
    private fun stopRinging() {
        ringSilencer?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }; ringSilencer = null
        try { ringtone?.stop(); (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (_: Exception) {}
        ringtone = null
    }
    private fun startRingback() { try { tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) } } catch (_: Exception) {} }
    private fun stopRingback() { try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}; tone = null }

    private const val CH = "calls"
    private fun channel() { NotifChannels.ensureAll(app) }
    private fun openIntent() = PendingIntent.getActivity(app, 7, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyIncoming() {
        if (foreground) return
        channel()
        val who = peer
        scope.launch {
            // who is calling: name and picture (waits a moment for them if they are not known yet)
            val u = Repo.user(who).value ?: try { kotlinx.coroutines.withTimeoutOrNull(1500) { Repo.userOnce(who) } } catch (_: Exception) { null }
            if (phase != CallPhase.INCOMING) return@launch
            val name = u?.name?.ifBlank { null } ?: "WeTalks call"
            val pic = u?.photo?.let { ph -> com.kafisir.wetalks.ui.Pics.decodeB64(ph)?.asAndroidBitmap() }
            val pb = androidx.core.app.Person.Builder().setName(name).setImportant(true)
            if (pic != null) pb.setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(pic))
            val decline = PendingIntent.getBroadcast(app, 11, Intent(app, NotifReceiver::class.java).setAction(Notifier.ACT_DECLINE).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // CallStyle: the caller's name and picture, a red Decline and a green Answer
            val n = NotificationCompat.Builder(app, CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(name)
                .setContentText("Incoming " + (if (video) "video" else "voice") + " call · WeTalks").setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setAutoCancel(false)
                .setContentIntent(openIntent()).setFullScreenIntent(openIntent(), true).setTimeoutAfter(55_000)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(pb.build(), decline, openIntent()))
                .setColor(0xFF22C55E.toInt()).build()
            try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(77, n) } catch (_: SecurityException) {}
        }
    }
    private fun cancelNotification() { try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(77) } catch (_: Exception) {} }

    private fun startForegroundService() {
        try { androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, CallService::class.java).putExtra("video", video)) } catch (_: Exception) {}
    }

    fun notificationForService(): Notification {
        channel()
        return NotificationCompat.Builder(app, CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("WeTalks call in progress").setContentText("Tap to return to the call")
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setContentIntent(openIntent()).build()
    }

    // ------------------------------------------------------------------------------------------ suspend helpers for WebRTC callbacks
    private open class Sdp(val ok: (SessionDescription?) -> Unit, val fail: (String?) -> Unit) : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) = ok(d)
        override fun onSetSuccess() = ok(null)
        override fun onCreateFailure(e: String?) = fail(e)
        override fun onSetFailure(e: String?) = fail(e)
    }
    private suspend fun PeerConnection.createOfferSuspend(): SessionDescription = suspendCancellableCoroutine { c ->
        createOffer(Sdp({ c.resume(it!!) }, { c.cancel(RuntimeException(it)) }), MediaConstraints())
    }
    private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription = suspendCancellableCoroutine { c ->
        createAnswer(Sdp({ c.resume(it!!) }, { c.cancel(RuntimeException(it)) }), MediaConstraints())
    }
    private suspend fun PeerConnection.setLocalSuspend(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
        setLocalDescription(Sdp({ c.resume(Unit) }, { c.cancel(RuntimeException(it)) }), d)
    }
    private suspend fun PeerConnection.setRemoteSuspend(d: SessionDescription) = suspendCancellableCoroutine<Unit> { c ->
        setRemoteDescription(Sdp({ c.resume(Unit) }, { c.cancel(RuntimeException(it)) }), d)
    }
}

/** Keeps the microphone/camera alive while a call is running with the app in the background. */
class CallService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra("video", false) ?: false
        var type = 0
        if (Build.VERSION.SDK_INT >= 30) { type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE; if (video) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA }
        try { ServiceCompat.startForeground(this, 78, Calls.notificationForService(), type) }
        catch (_: Exception) {
            // a foreground service that never calls startForeground() crashes the app, so fall back to the plain form before giving up
            try { startForeground(78, Calls.notificationForService()) } catch (_: Exception) { stopSelf() }
        }
        return START_NOT_STICKY
    }
}
