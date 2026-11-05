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
import androidx.compose.material3.Text
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** AAC recorder (mono, 32 kbps). Samples the loudness every 100 ms so the message can show a waveform. */
class Recorder(private val ctx: Context) {
    private val file = File(ctx.cacheDir, "rec_${System.nanoTime()}.m4a")
    private var rec: MediaRecorder? = null
    private val amps = ArrayList<Int>()
    var startedAt = 0L; private set

    @Suppress("DEPRECATION")
    fun start() {
        rec = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000); setMaxDuration(300_000)
            setOutputFile(file.absolutePath); prepare(); start()
        }
        startedAt = System.currentTimeMillis()
    }

    /** Current loudness 0..1 (also recorded for the waveform). */
    fun level(): Float {
        val a = try { rec?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
        amps.add(a)
        return (a / 18000f).coerceIn(0f, 1f)
    }

    class Result(val bytes: ByteArray, val seconds: Int, val wave: List<Int>)

    fun finish(): Result? {
        val secs = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        return try {
            rec?.stop(); rec?.release(); rec = null
            val bytes = file.readBytes(); file.delete()
            Result(bytes, secs, downsample(amps))
        } catch (e: Exception) { rec?.release(); rec = null; file.delete(); null }
    }

    fun cancel() { try { rec?.stop() } catch (_: Exception) {}; rec?.release(); rec = null; file.delete() }

    private fun downsample(a: List<Int>, n: Int = 40): List<Int> {
        if (a.isEmpty()) return List(n) { 2 }
        val peak = (a.maxOrNull() ?: 1).coerceAtLeast(1)
        return List(n) { i ->
            val from = i * a.size / n; val to = ((i + 1) * a.size / n).coerceAtLeast(from + 1).coerceAtMost(a.size)
            val m = a.subList(from, to).maxOrNull() ?: 0
            (m * 31f / peak).toInt().coerceIn(2, 31)
        }
    }
}

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

/** Telegram-style voice bubble: round play button, tappable/draggable waveform, elapsed time, 1x/1.5x/2x speed and an unplayed dot. */
@Composable
fun VoiceBody(m: Msg, ref: ChatRef, fg: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(m.dur * 1000) }
    var loading by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    val listened = remember { ctx.getSharedPreferences("wt_listened", Context.MODE_PRIVATE) }
    var heard by remember { mutableStateOf(listened.getBoolean(m.id, false) || m.from == Repo.uid) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(playing) { while (playing) { pos = player?.currentPosition ?: 0; delay(80) } }

    fun applySpeed() { try { player?.let { if (playing) it.playbackParams = PlaybackParams().setSpeed(speed) } } catch (_: Exception) {} }
    fun toggle() {
        val p = player
        if (p != null) { if (playing) { p.pause(); playing = false } else { p.start(); applySpeed(); playing = true }; return }
        scope.launch {
            loading = true
            val bytes = m.mediaId?.let { Repo.loadMedia(ref, it) }
            loading = false
            if (bytes == null) { Nav.say("Could not load voice message"); return@launch }
            val f = File(ctx.cacheDir, "v_${m.id}.m4a"); f.writeBytes(bytes)
            val mp = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); setOnCompletionListener { playing = false; pos = 0; seekTo(0) } }
            total = mp.duration; player = mp; mp.start(); playing = true; applySpeed()
            if (!heard) { heard = true; listened.edit().putBoolean(m.id, true).apply() }
        }
    }

    Row(Modifier.width(236.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { toggle() }), contentAlignment = Alignment.Center) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = We.onPrimary, strokeWidth = 2.dp)
            else Icon(if (playing) Ic.Pause2 else Ic.Play, We.onPrimary, 22.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Waveform(m.wave, if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f, We.primary, fg.copy(alpha = .28f), Modifier.fillMaxWidth().height(30.dp)) { frac ->
                if (player != null) { val t = (frac * total).toInt(); player?.seekTo(t); pos = t }
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val shown = if (playing || pos > 0) pos / 1000 else m.dur
                Text("%d:%02d".format(shown / 60, shown % 60), color = fg.copy(alpha = .65f), style = T.tiny)
                if (!heard) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(We.primary)) }
                Spacer(Modifier.weight(1f))
                if (playing || pos > 0) Text(if (speed == 1f) "1x" else if (speed == 1.5f) "1.5x" else "2x", color = We.primary, style = T.tiny.copy(fontSize = 11.sp),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .14f)).combinedClickable(onClick = {
                        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }; applySpeed()
                    }).padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}
