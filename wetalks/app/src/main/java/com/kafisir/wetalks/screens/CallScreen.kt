@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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

private val Navy = Color(0xFF0B1220)
private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)

/** Full-screen call UI: incoming (answer / decline), calling, connecting and the live call. */
@Composable
fun CallScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase = Calls.phase
    val peerId = Calls.peer
    val user by Repo.user(peerId.ifEmpty { Repo.uid }).collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) { while (phase == CallPhase.ACTIVE) { tick = System.currentTimeMillis(); delay(500) } }
    val answerPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val micOk = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = !Calls.video || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (micOk && camOk) scope.launch { try { Calls.accept() } catch (e: Exception) { Nav.say(e.message ?: "Could not answer") } }
        else { Nav.say("Allow microphone" + (if (Calls.video) " and camera" else "") + " to answer"); Calls.decline() }
    }
    fun answer() {
        val need = buildList { add(Manifest.permission.RECORD_AUDIO); if (Calls.video) add(Manifest.permission.CAMERA) }
        answerPerms.launch(need.toTypedArray())
    }

    val live = Calls.video && (phase == CallPhase.ACTIVE || phase == CallPhase.CONNECTING) && Calls.remoteVideo != null
    Box(Modifier.fillMaxSize().background(Navy)) {
        if (live) VideoView(Calls.remoteVideo, mirror = false, overlay = false, Modifier.fillMaxSize())
        else Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF16305C), Navy))), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(.28f))
            Avatar(user?.name ?: "", user?.photo ?: "", 128.dp, peerId)
            Spacer(Modifier.weight(1f))
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (live) Spacer(Modifier.height(0.dp)) else Spacer(Modifier.height(0.dp))
            Text(user?.name ?: "…", color = Color.White, style = T.title.copy(fontSize = 26.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(6.dp))
            val sub = when (phase) {
                CallPhase.INCOMING -> "Incoming " + (if (Calls.video) "video" else "voice") + " call"
                CallPhase.ACTIVE -> if (Calls.note.isNotEmpty()) Calls.note else { val s = ((maxOf(tick, Calls.connectedAt) - Calls.connectedAt) / 1000).toInt(); "%d:%02d".format(s / 60, s % 60) }
                else -> Calls.note.ifEmpty { "Connecting…" }
            }
            Text(sub, color = Color.White.copy(alpha = .75f), style = T.body)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Lock, Color.White.copy(alpha = .55f), 12.dp); Spacer(Modifier.width(5.dp))
                Text("End-to-end encrypted", color = Color.White.copy(alpha = .55f), style = T.tiny)
            }
        }

        // my own camera, picture-in-picture
        if (Calls.video && Calls.localVideo != null && phase != CallPhase.INCOMING) Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 96.dp, end = 14.dp).size(104.dp, 148.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            if (Calls.camOn) VideoView(Calls.localVideo, mirror = Calls.frontCam, overlay = true, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Ic.VideoOff, Color.White, 26.dp) }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (phase == CallPhase.INCOMING) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigBtn(Ic.Phone, Red, "Decline", rotate = 135f) { Calls.decline() }
                    BigBtn(if (Calls.video) Ic.Video else Ic.Phone, Green, "Answer") { answer() }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SmallBtn(if (Calls.muted) Ic.MicOff else Ic.Mic, Calls.muted, "Mute") { Calls.toggleMute() }
                    SmallBtn(Ic.Speaker, Calls.speaker, "Speaker") { Calls.toggleSpeaker() }
                    if (Calls.video) {
                        SmallBtn(if (Calls.camOn) Ic.Video else Ic.VideoOff, !Calls.camOn, "Camera") { Calls.toggleCamera() }
                        SmallBtn(Ic.Rotate, false, "Flip") { Calls.flipCamera() }
                    }
                }
                Spacer(Modifier.height(26.dp))
                BigBtn(Ic.Phone, Red, "End", rotate = 135f) { Calls.hangUp() }
            }
        }
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

@Composable
private fun VideoView(track: VideoTrack?, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
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
