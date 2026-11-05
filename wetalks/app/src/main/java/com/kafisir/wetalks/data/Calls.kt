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
    var phase by mutableStateOf(CallPhase.IDLE); private set
    var peer by mutableStateOf(""); private set
    var video by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var camOn by mutableStateOf(true); private set
    var speaker by mutableStateOf(false); private set
    var frontCam by mutableStateOf(true); private set
    var connectedAt by mutableStateOf(0L); private set
    var note by mutableStateOf(""); private set
    var localVideo by mutableStateOf<VideoTrack?>(null); private set
    var remoteVideo by mutableStateOf<VideoTrack?>(null); private set
    var foreground = false

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
    private var prevAudioMode = AudioManager.MODE_NORMAL
    private var serverOffset = 0L
    private var incomingListening = false

    private val servers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // public relay for networks where a direct path is impossible (media stays DTLS-SRTP encrypted end to end)
        PeerConnection.IceServer.builder(listOf("turn:openrelay.metered.ca:80", "turn:openrelay.metered.ca:443", "turn:openrelay.metered.ca:443?transport=tcp"))
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    fun init(c: Context) { app = c.applicationContext }

    val peerRef get() = ChatRef(Repo.dmId(peer), Kind.DM, peer)

    // ------------------------------------------------------------------------------------------ incoming listener
    fun listen() {
        if (incomingListening || Repo.uid.isEmpty()) return
        incomingListening = true
        Repo.db.getReference(".info/serverTimeOffset").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { serverOffset = s.getValue(Long::class.java) ?: 0L }
            override fun onCancelled(e: DatabaseError) {}
        })
        Repo.db.getReference("incoming_calls/${Repo.uid}").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) { val id = s.key ?: return; scope.launch { onIncoming(id) } }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { incomingListening = false }
        })
    }

    private suspend fun onIncoming(id: String) {
        val uid = Repo.uid
        try {
            val s = Repo.db.getReference("calls/$id").get().await()
            Repo.db.getReference("incoming_calls/$uid/$id").removeValue()
            if (!s.exists() || s.child("to").getValue(String::class.java) != uid) return
            val from = s.child("from").getValue(String::class.java) ?: return
            val ts = s.child("ts").getValue(Long::class.java) ?: 0L
            if (s.child("state").getValue(String::class.java) != "ringing" || System.currentTimeMillis() + serverOffset - ts > 60_000) return
            if (from in Repo.blockedUsers.value) { setState(id, "declined"); return }
            if (phase != CallPhase.IDLE) { setState(id, "busy"); return }
            callId = id; caller = false; peer = from; video = s.child("video").getValue(Boolean::class.java) ?: false
            pendingOffer = s.child("offer").getValue(String::class.java)
            resetFlags(); phase = CallPhase.INCOMING; note = ""
            key = Repo.chatKey(ChatRef(Repo.dmId(from), Kind.DM, from))
            watchState(); startRinging(); notifyIncoming()
            timeout = scope.launch { delay(55_000); if (phase == CallPhase.INCOMING) finish("missed", notifyPeer = true, remote = false) }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------------------------------ outgoing
    suspend fun start(to: String, withVideo: Boolean) {
        if (phase != CallPhase.IDLE) return
        val k = Repo.chatKey(ChatRef(Repo.dmId(to), Kind.DM, to)) ?: throw IllegalStateException("They need to open WeTalks once before you can call them.")
        key = k; peer = to; video = withVideo; caller = true; resetFlags(); note = "Calling…"; phase = CallPhase.OUTGOING
        callId = Repo.db.getReference("calls").push().key!!
        try {
            ensureFactory(); prepareAudio(); startForegroundService()
            buildPeer(withVideo)
            val offer = pc!!.createOfferSuspend(); pc!!.setLocalSuspend(offer)
            Repo.db.getReference("calls/$callId").setValue(hashMapOf("from" to Repo.uid, "to" to to, "video" to withVideo, "ts" to ServerValue.TIMESTAMP, "state" to "ringing",
                "offer" to Crypto.sealText(k, JSONObject().put("type", offer.type.canonicalForm()).put("sdp", offer.description).toString()))).await()
            Repo.db.getReference("incoming_calls/$to/$callId").setValue(hashMapOf("from" to Repo.uid, "video" to withVideo, "ts" to ServerValue.TIMESTAMP)).await()
            watchState()
            watchIce("ice_callee")
            startRingback()
            timeout = scope.launch { delay(50_000); if (phase == CallPhase.OUTGOING) finish("missed", notifyPeer = true, remote = false) }
        } catch (e: Exception) { teardown(); throw e }
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
    fun hangUp() { if (phase != CallPhase.IDLE) finish(if (connectedAt > 0) "ended" else if (caller) "missed" else "declined", notifyPeer = true, remote = false) }

    // ------------------------------------------------------------------------------------------ controls
    fun toggleMute() { muted = !muted; audioTrack?.setEnabled(!muted) }
    fun toggleCamera() { camOn = !camOn; videoTrack?.setEnabled(camOn) }
    fun toggleSpeaker() { applySpeaker(!speaker) }
    fun flipCamera() { capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler { override fun onCameraSwitchDone(front: Boolean) { frontCam = front }; override fun onCameraSwitchError(e: String?) {} }) }
    private fun applySpeaker(on: Boolean) {
        speaker = on
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = on
    }

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
                Repo.db.getReference("calls/$callId/${if (caller) "ice_caller" else "ice_callee"}").push().setValue(Crypto.sealText(k, j))
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
        if (withVideo) {
            val en = Camera2Enumerator(app)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: throw IllegalStateException("No camera found")
            val cap = en.createCapturer(name, null) ?: throw IllegalStateException("Camera not available")
            capturer = cap
            surfaceHelper = SurfaceTextureHelper.create("wt-capture", egl.eglBaseContext)
            videoSource = f.createVideoSource(false).also { cap.initialize(surfaceHelper, app, it.capturerObserver) }
            cap.startCapture(960, 540, 24)
            videoTrack = f.createVideoTrack("v0", videoSource).also { pc!!.addTrack(it, listOf("s0")); localVideo = it }
        }
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

    // ------------------------------------------------------------------------------------------ end
    private fun finish(state: String, notifyPeer: Boolean, remote: Boolean, why: String = "") {
        if (finishing || phase == CallPhase.IDLE) return
        finishing = true
        val id = callId; val to = peer; val wasCaller = caller; val vid = video
        val dur = if (connectedAt > 0) ((System.currentTimeMillis() - connectedAt) / 1000).toInt() else 0
        if (notifyPeer) setState(id, state)
        // the caller leaves a line in the chat ("Missed video call", "Voice call · 2:31" …)
        if (wasCaller) scope.launch {
            try {
                val status = when { dur > 0 -> "ended"; state == "busy" -> "declined"; else -> state }
                Repo.send(ChatRef(Repo.dmId(to), Kind.DM, to), "call", JSONObject().put("v", if (vid) 1 else 0).put("d", dur).put("s", status).toString(), null, if (vid) "Video call" else "Voice call")
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
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isSpeakerphoneOn = false; am.mode = prevAudioMode
        try { app.stopService(Intent(app, CallService::class.java)) } catch (_: Exception) {}
        key = null; pendingOffer = null; connectedAt = 0L; speaker = false
        phase = CallPhase.IDLE; note = ""
    }

    // ------------------------------------------------------------------------------------------ audio / ring / notifications
    private fun prepareAudio() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prevAudioMode = am.mode; am.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker(false)
    }

    private fun startRinging() {
        try {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.also { if (Build.VERSION.SDK_INT >= 28) it.isLooping = true; it.play() }
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0))
        } catch (_: Exception) {}
    }
    private fun stopRinging() {
        try { ringtone?.stop(); (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (_: Exception) {}
        ringtone = null
    }
    private fun startRingback() { try { tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) } } catch (_: Exception) {} }
    private fun stopRingback() { try { tone?.stopTone(); tone?.release() } catch (_: Exception) {}; tone = null }

    private const val CH = "calls"
    private fun channel() {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
    }
    private fun openIntent() = PendingIntent.getActivity(app, 7, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notifyIncoming() {
        if (foreground) return
        channel()
        val n = NotificationCompat.Builder(app, CH).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Incoming " + (if (video) "video" else "voice") + " call")
            .setContentText("WeTalks").setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true).setAutoCancel(false)
            .setContentIntent(openIntent()).setFullScreenIntent(openIntent(), true).build()
        try { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(77, n) } catch (_: SecurityException) {}
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
        try { ServiceCompat.startForeground(this, 78, Calls.notificationForService(), type) } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
}
