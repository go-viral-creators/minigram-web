@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kafisir.wetalks.data.CallPhase
import com.kafisir.wetalks.data.Calls
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private val Navy = Color(0xFF0B1220)
private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)

/** Full-screen call UI: incoming (slide to answer / decline), calling, connecting and the live call. */
@Composable
fun CallScreen() {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val phase = Calls.phase
    val peerId = Calls.peer
    val user by Repo.user(peerId.ifEmpty { Repo.uid }).collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) { while (phase == CallPhase.ACTIVE) { tick = System.currentTimeMillis(); delay(500) } }

    val answerPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val micOk = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = !Calls.video || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (micOk && camOk) Repo.scope.launch { try { Calls.accept() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Nav.say(e.message ?: "Could not answer") } }
        else { Nav.say("Allow microphone" + (if (Calls.video) " and camera" else "") + " to answer"); Calls.decline() }
    }
    fun answer() { answerPerms.launch(buildList { add(Manifest.permission.RECORD_AUDIO); if (Calls.video) add(Manifest.permission.CAMERA) }.toTypedArray()) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Repo.scope.launch { try { Calls.enableVideo() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Nav.say(e.message ?: "Could not start video") } }
        else Nav.say("Camera permission is needed for video")
    }
    fun turnVideoOn() {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) Repo.scope.launch { try { Calls.enableVideo() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Nav.say(e.message ?: "Could not start video") } }
        else camPerm.launch(Manifest.permission.CAMERA)
    }

    if (phase == CallPhase.INCOMING) {
        IncomingCall(user?.name ?: "…", user?.photo ?: "", peerId, Calls.video, ::answer)
        return
    }

    val videoMode = Calls.video && (phase == CallPhase.ACTIVE || phase == CallPhase.CONNECTING)
    val local = Calls.localVideo; val remote = Calls.remoteVideo
    var swapped by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    LaunchedEffect(videoMode, controls, touch, phase) { if (videoMode && controls && phase == CallPhase.ACTIVE) { delay(4000); controls = false } }
    LaunchedEffect(videoMode) { if (!videoMode) { controls = true; swapped = false } }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var pip by remember { mutableStateOf(Offset.Zero) }
    var pipShown by remember { mutableStateOf(true) }
    val bigIsLocal = swapped && local != null
    val bigTrack = if (bigIsLocal) local else remote
    val bigShows = videoMode && bigTrack != null && (!bigIsLocal || Calls.camOn)

    Box(Modifier.fillMaxSize().background(Navy).onSizeChanged { box = it }.pointerInput(Unit) { detectTapGestures { controls = !controls; touch++ } }) {
        if (bigShows) key(bigIsLocal) { VideoView(bigTrack, mirror = bigIsLocal && Calls.frontCam, overlay = false, Modifier.fillMaxSize()) }
        else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF16305C), Navy))))

        // name / timer
        AnimatedVisibility(controls || !videoMode, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.statusBarsPadding().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(user?.name ?: "…", color = Color.White, style = T.title.copy(fontSize = 26.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(6.dp))
                val sub = when (phase) {
                    CallPhase.ACTIVE -> if (Calls.note.isNotEmpty()) Calls.note else { val s = ((maxOf(tick, Calls.connectedAt) - Calls.connectedAt) / 1000).toInt(); "%d:%02d".format(s / 60, s % 60) }
                    else -> Calls.note.ifEmpty { "Connecting…" }
                }
                Text(sub, color = Color.White.copy(alpha = .8f), style = T.body)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Lock, Color.White.copy(alpha = .55f), 12.dp); Spacer(Modifier.width(5.dp))
                    Text("End-to-end encrypted", color = Color.White.copy(alpha = .55f), style = T.tiny)
                }
            }
        }
        // avatar with real spacing under the text (voice calls and while video is not flowing yet)
        if (!bigShows) Box(Modifier.align(Alignment.Center).padding(top = 60.dp)) { Avatar(user?.name ?: "", user?.photo ?: "", 140.dp, peerId) }

        // small window: drag it anywhere, tap it to swap with the big picture
        val pipTrack = if (bigIsLocal) remote else local
        if (videoMode && pipShown && (local != null || remote != null)) {
            val pw = 104.dp; val ph = 148.dp
            val pwPx = pw.value * density; val phPx = ph.value * density
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 100.dp, end = 14.dp).offset { IntOffset(pip.x.roundToInt(), pip.y.roundToInt()) }
                .size(pw, ph).shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(24.dp)).background(Color(0xFF0F1B2D))
                .pointerInput(Unit) { detectTapGestures(onTap = { swapped = !swapped }) }
                .pointerInput(Unit) { detectDragGestures { c, d -> c.consume(); pip = Offset((pip.x + d.x).coerceIn(-(box.width - pwPx - 28 * density), 0f), (pip.y + d.y).coerceIn(-70 * density, box.height - phPx - 220 * density)) } }) {
                val showLocalHere = !bigIsLocal
                if (pipTrack != null && (!showLocalHere || Calls.camOn)) key(bigIsLocal) { VideoView(pipTrack, mirror = showLocalHere && Calls.frontCam, overlay = true, Modifier.fillMaxSize(), cornerDp = 24f) }
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(if (showLocalHere) Ic.VideoOff else Ic.User, Color.White.copy(alpha = .8f), 28.dp) }
            }
        }

        AnimatedVisibility(controls || !videoMode, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SmallBtn(if (Calls.muted) Ic.MicOff else Ic.Mic, Calls.muted, "Mute") { Calls.toggleMute(); touch++ }
                    SmallBtn(Ic.Speaker, Calls.speaker, "Speaker") { Calls.toggleSpeaker(); touch++ }
                    if (videoMode || Calls.video) {
                        SmallBtn(if (Calls.camOn) Ic.Video else Ic.VideoOff, !Calls.camOn, if (Calls.camOn) "Camera" else "Camera off") { if (Calls.localVideo == null) turnVideoOn() else Calls.toggleCamera(); touch++ }
                        SmallBtn(Ic.Rotate, false, "Flip") { Calls.flipCamera(); touch++ }
                        SmallBtn(if (pipShown) Ic.EyeOff else Ic.Eye, !pipShown, if (pipShown) "Hide mini" else "Show mini") { pipShown = !pipShown; touch++ }
                    } else if (phase == CallPhase.ACTIVE) SmallBtn(Ic.Video, false, "Video") { turnVideoOn(); touch++ }
                }
                Spacer(Modifier.height(26.dp))
                BigBtn(Ic.Phone, Red, "End", rotate = 135f) { Calls.hangUp() }
            }
        }
    }
}

/** Incoming call: the green button drifts up and the red one down with soft waves; slide up to answer, slide down to decline. */
@Composable
private fun IncomingCall(name: String, photo: String, seed: String, video: Boolean, onAnswer: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF16305C), Navy)))) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = Color.White, style = T.title.copy(fontSize = 28.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            Text("Incoming " + (if (video) "video" else "voice") + " call", color = Color.White.copy(alpha = .8f), style = T.body)
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Lock, Color.White.copy(alpha = .55f), 12.dp); Spacer(Modifier.width(5.dp)); Text("End-to-end encrypted", color = Color.White.copy(alpha = .55f), style = T.tiny)
            }
            Spacer(Modifier.height(56.dp))
            Avatar(name, photo, 150.dp, seed)
        }
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp).fillMaxWidth().padding(horizontal = 44.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SlideCallButton(false, Ic.Phone, Red, "Slide down to decline", rotate = 135f) { Calls.decline() }
            SlideCallButton(true, if (video) Ic.Video else Ic.Phone, Green, "Slide up to answer") { onAnswer() }
        }
    }
}

@Composable
private fun SlideCallButton(answer: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, hint: String, rotate: Float = 0f, onTrigger: () -> Unit) {
    val d = LocalDensity.current.density
    val inf = rememberInfiniteTransition(label = "slide")
    val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "p")
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    val travel = 120 * d; val trigger = 80 * d
    val dir = if (answer) -1f else 1f
    val auto = dir * sin(PI * p).toFloat() * 30 * d
    val y = if (dragging) drag else auto
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(150.dp, 260.dp).pointerInput(Unit) {
            detectVerticalDragGestures(onDragStart = { dragging = true },
                // the action happens on release, and only if the button is still past the line - sliding back cancels it
                onDragEnd = { val go = abs(drag) >= trigger; dragging = false; drag = 0f; if (go && !fired) { fired = true; onTrigger() } },
                onDragCancel = { dragging = false; drag = 0f },
                onVerticalDrag = { c, dy ->
                    c.consume()
                    drag = if (answer) (drag + dy).coerceIn(-travel, 0f) else (drag + dy).coerceIn(0f, travel)
                })
        }, contentAlignment = Alignment.Center) {
            // waves leaving the button
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2 + y)
                for (k in 0 until 3) { val q = (p + k / 3f) % 1f; drawCircle(color.copy(alpha = (1f - q) * .34f), (36f + q * 52f) * d, c) }
            }
            Box(Modifier.offset { IntOffset(0, y.roundToInt()) }.size(72.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Icon(icon, Color.White, 32.dp, Modifier.rotate(rotate)) }
        }
        Text(hint, color = Color.White.copy(alpha = .75f), style = T.small, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun BigBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, label: String, rotate: Float = 0f, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(bg).combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, Color.White, 30.dp, Modifier.rotate(rotate)) }
        Text(label, color = Color.White.copy(alpha = .8f), style = T.tiny, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SmallBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = .16f)).combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, if (on) Navy else Color.White, 24.dp) }
        Text(label, color = Color.White.copy(alpha = .8f), style = T.tiny, modifier = Modifier.padding(top = 5.dp))
    }
}

/** A video surface that can really be rounded: SurfaceView ignores clipping, a TextureView does not. Used for the mini window. */
private class RoundVideoView(c: android.content.Context) : android.view.TextureView(c), org.webrtc.VideoSink, android.view.TextureView.SurfaceTextureListener {
    private val renderer = org.webrtc.EglRenderer("wt-mini")
    private var ready = false
    fun setup(ctx: org.webrtc.EglBase.Context, cornerDp: Float) {
        renderer.init(ctx, org.webrtc.EglBase.CONFIG_PLAIN, org.webrtc.GlRectDrawer())
        surfaceTextureListener = this
        val r = cornerDp * resources.displayMetrics.density
        outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: android.view.View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
        clipToOutline = true
        ready = true
    }
    fun mirror(on: Boolean) = renderer.setMirror(on)
    override fun onFrame(f: org.webrtc.VideoFrame) { if (ready) renderer.onFrame(f) }
    fun dispose() { if (ready) { ready = false; renderer.release() } }
    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { renderer.createEglSurface(st); if (h > 0) renderer.setLayoutAspectRatio(w.toFloat() / h) }
    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { if (h > 0) renderer.setLayoutAspectRatio(w.toFloat() / h) }
    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
        val l = java.util.concurrent.CountDownLatch(1); renderer.releaseEglSurface { l.countDown() }
        try { l.await(500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        return true
    }
    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
}

@Composable
private fun VideoView(track: VideoTrack?, mirror: Boolean, overlay: Boolean, modifier: Modifier, cornerDp: Float = 0f) {
    if (cornerDp > 0f) {
        var view by remember { mutableStateOf<RoundVideoView?>(null) }
        AndroidView(factory = { c -> RoundVideoView(c).apply { setup(Calls.egl.eglBaseContext, cornerDp) }.also { view = it } },
            update = { it.mirror(mirror) }, onRelease = { it.dispose() }, modifier = modifier)
        DisposableEffect(track, view) {
            val v = view
            if (track != null && v != null) track.addSink(v)
            onDispose { if (track != null && v != null) try { track.removeSink(v) } catch (_: Exception) {} }
        }
        return
    }
    var view by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(factory = { c ->
        SurfaceViewRenderer(c).apply {
            init(Calls.egl.eglBaseContext, null); setEnableHardwareScaler(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL); setZOrderMediaOverlay(overlay)
        }.also { view = it }
    }, update = { it.setMirror(mirror) }, onRelease = { it.release() }, modifier = modifier)
    DisposableEffect(track, view) {
        val v = view
        if (track != null && v != null) track.addSink(v)
        onDispose { if (track != null && v != null) try { track.removeSink(v) } catch (_: Exception) {} }
    }
}

/** Asks for mic (+camera) permission, then rings [peer]. Returns a function(video: Boolean). */
@Composable
fun rememberCallStarter(peer: String): (Boolean) -> Unit {
    val ctx = LocalContext.current
    var wantVideo by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val micOk = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = !wantVideo || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!micOk || !camOk) Nav.say(if (!micOk) "Microphone permission is needed for calls" else "Camera permission is needed for video calls")
        else Repo.scope.launch { try { Calls.start(peer, wantVideo) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Nav.say(e.message ?: "Could not start the call") } }
    }
    return { v ->
        if (Calls.phase != CallPhase.IDLE) Nav.say("You're already in a call")
        else {
            wantVideo = v
            launcher.launch(buildList { add(Manifest.permission.RECORD_AUDIO); if (v) add(Manifest.permission.CAMERA); if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray())
        }
    }
}
