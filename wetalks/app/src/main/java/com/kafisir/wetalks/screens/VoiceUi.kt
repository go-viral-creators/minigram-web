@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Msg
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import com.kafisir.wetalks.voice.VoicePlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun Waveform(bars: List<Int>, progress: Float, played: Color, idle: Color, modifier: Modifier = Modifier, onSeek: ((Float) -> Unit)? = null) {
    val data = if (bars.isEmpty()) List(40) { 4 + (it * 7 % 13) } else bars
    Canvas(modifier.then(if (onSeek != null) Modifier.pointerInput(Unit) {
        detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
    }.pointerInput(Unit) { detectDragGestures { c, _ -> onSeek((c.position.x / size.width).coerceIn(0f, 1f)) } } else Modifier)) {
        val n = data.size; val gap = 2.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
        data.forEachIndexed { i, v ->
            val h = (size.height * (v / 31f)).coerceAtLeast(3.dp.toPx())
            drawRoundRect(if (i.toFloat() / n < progress) played else idle, Offset(i * (w + gap), (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** Telegram-style voice bubble. Playback is owned by [VoicePlayer], so it carries on when you leave this screen. */
@Composable
fun VoiceBody(m: Msg, ref: ChatRef, fg: Color) {
    val ctx = LocalContext.current
    val who by Repo.user(m.from).collectAsState()
    val active = VoicePlayer.currentId == m.id
    val playing = active && VoicePlayer.playing
    val loading = VoicePlayer.loadingId == m.id || m.pending
    val pos = if (active) VoicePlayer.pos else 0
    val total = if (active && VoicePlayer.total > 0) VoicePlayer.total else m.dur * 1000
    val speed = VoicePlayer.speed
    val listened = remember { ctx.getSharedPreferences("wt_listened", Context.MODE_PRIVATE) }
    val heard = listened.getBoolean(m.id, false) || m.from == Repo.uid || active

    Row(Modifier.width(236.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { if (!m.pending) VoicePlayer.toggle(ctx, ref, m, if (m.from == Repo.uid) "You" else who?.name ?: "Voice message") }), contentAlignment = Alignment.Center) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = We.onPrimary, strokeWidth = 2.dp)
            else Icon(if (playing) Ic.Pause2 else Ic.Play, We.onPrimary, 22.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Waveform(m.wave, if (active && total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f, We.primary, fg.copy(alpha = .28f), Modifier.fillMaxWidth().height(30.dp)) { frac -> if (active) VoicePlayer.seek(frac) }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val shown = if (active && (playing || pos > 0)) pos / 1000 else m.dur
                Text("%d:%02d".format(shown / 60, shown % 60), color = fg.copy(alpha = .65f), style = T.tiny)
                if (!heard) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(We.primary)) }
                Spacer(Modifier.weight(1f))
                if (active) Text(if (speed == 1f) "1x" else if (speed == 1.5f) "1.5x" else "2x", color = We.primary, style = T.tiny.copy(fontSize = 11.sp),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .14f)).combinedClickable(onClick = { VoicePlayer.cycleSpeed() }).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}

/** Slim "now playing" bar shown under the app bar on every screen while a voice note is active. */
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val id = VoicePlayer.currentId ?: return
    val ref = VoicePlayer.ref
    Row(modifier.padding(horizontal = 10.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(We.surface).combinedClickable(onClick = { if (ref != null) Nav.openChatAt(ref, id) })
        .padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { if (VoicePlayer.playing) VoicePlayer.pause() else VoicePlayer.resume() }), contentAlignment = Alignment.Center) {
            Icon(if (VoicePlayer.playing) Ic.Pause2 else Ic.Play, We.onPrimary, 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(VoicePlayer.title, color = We.text, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
            val p = VoicePlayer.pos / 1000; val t = VoicePlayer.total / 1000
            Text("Voice message · %d:%02d / %d:%02d".format(p / 60, p % 60, t / 60, t % 60), color = We.text2, style = T.tiny, maxLines = 1)
        }
        Text(if (VoicePlayer.speed == 1f) "1x" else if (VoicePlayer.speed == 1.5f) "1.5x" else "2x", color = We.primary, style = T.tiny, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .14f)).combinedClickable(onClick = { VoicePlayer.cycleSpeed() }).padding(horizontal = 8.dp, vertical = 3.dp))
        IconBtn(Ic.Close, We.text2, { VoicePlayer.stop() }, 18.dp, Modifier.size(38.dp))
    }
}


/** The bar shown while a message is being read aloud: pause / play and stop, from any screen. */
@Composable
fun ReadAloudBar(modifier: Modifier = Modifier) {
    if (!com.kafisir.wetalks.voice.Speaker.reading) return
    val sp = com.kafisir.wetalks.voice.Speaker
    Row(modifier.padding(horizontal = 10.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(We.surface).padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { sp.toggle() }), contentAlignment = Alignment.Center) {
            Icon(if (sp.paused) Ic.Play else Ic.Pause2, We.onPrimary, 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(sp.label, color = We.text, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
            Text(if (sp.paused) "Paused" else "Reading aloud…", color = We.text2, style = T.tiny, maxLines = 1)
        }
        IconBtn(Ic.Close, We.text2, { sp.stop() }, 18.dp, Modifier.size(38.dp))
    }
}
