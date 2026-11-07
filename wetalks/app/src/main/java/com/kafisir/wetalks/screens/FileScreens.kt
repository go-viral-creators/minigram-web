package com.kafisir.wetalks.screens

import android.content.Intent
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** One audio file plays at a time. */
object FilePlayer {
    private var mp: MediaPlayer? = null
    var playingId by mutableStateOf<String?>(null)
    var pos by mutableIntStateOf(0)
    var len by mutableIntStateOf(1)
    fun toggle(id: String, f: File) {
        if (playingId == id) { stop(); return }
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(f.absolutePath); prepare(); len = duration.coerceAtLeast(1); start()
                setOnCompletionListener { stop() }
            }
            playingId = id; pos = 0
        } catch (e: Exception) { stop(); Nav.say("Could not play this audio") }
    }
    fun tick() { try { mp?.let { if (it.isPlaying) pos = it.currentPosition } } catch (_: Exception) {} }
    fun stop() { try { mp?.release() } catch (_: Exception) {}; mp = null; playingId = null; pos = 0 }
}

/** The card for a video, audio or document message. Tap = download (first time) and open. */
@Composable
fun FileBody(m: Msg, ref: ChatRef, mine: Boolean, fg: Color, onLong: () -> Unit) {
    val selecting = LocalSelecting.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val out = if (m.pending) Repo.outgoing.firstOrNull { "out_" + it.id == m.id } else null
    val cached = remember(m.mediaId, m.name, AutoFetch.doneTick) { m.mediaId?.let { FileKit.cachedFile(ctx, it, m.name).let { f -> f.exists() && f.length() > 0 } } == true }
    var progress by remember(m.id) { mutableFloatStateOf(-1f) }
    var ready by remember(m.id, cached) { mutableStateOf(cached) }
    var failed by remember(m.id) { mutableStateOf("") }
    // automatic download: the rule for the current network (Wi-Fi / mobile / roaming) decides; otherwise the tile waits for a tap
    val mtype = MediaPolicy.typeOf(m.type) ?: MediaPolicy.Type.DOC
    val verdict = DataPrefs.autoVerdict(mtype, m.size, ready, m.from == Repo.uid)
    val autoKey = m.mediaId ?: ""
    LaunchedEffect(m.mediaId, verdict.auto, ready) {
        val id = m.mediaId
        if (id != null && ready) MediaLedger.noteFile(ref.id, m.id, id, m.name, m.type)
        else if (id != null && verdict.auto && !m.pending && m.fkey.isNotEmpty()) AutoFetch.start(autoKey, mtype, m.size, m.from == Repo.uid) { p -> Repo.fetchFile(m, ref.id, p) }
    }
    val autoBusy = AutoFetch.progress[autoKey]
    val busy = out != null && !out.failed || progress >= 0f || autoBusy != null
    val shown = if (out != null) out.progress else if (autoBusy != null && progress < 0f) autoBusy else progress.coerceAtLeast(0f)
    val icon = when (m.type) { "video" -> Ic.Video; "audio" -> Ic.Audio; else -> Ic.File }
    val playing = m.type == "audio" && FilePlayer.playingId == m.id
    LaunchedEffect(playing) { while (playing) { FilePlayer.tick(); delay(250) } }

    fun open() {
        if (m.pending || progress >= 0f) return
        if (AutoFetch.running(autoKey)) { AutoFetch.promote(autoKey); return }       // already coming by itself: let it finish
        failed = ""
        if (!ready) MediaNet.warnIfRoaming()
        scope.launch {
            try {
                progress = 0f
                val f = Repo.fetchFile(m, ref.id) { progress = it }
                progress = -1f; ready = true
                when (m.type) {
                    "video" -> Nav.push(RVideo(f.absolutePath, m.name))
                    "audio" -> FilePlayer.toggle(m.id, f)
                    else -> {
                        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, FileKit.mimeOf(FileKit.extOf(m.name))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { ctx.startActivity(i) } catch (_: Exception) { Nav.say("No app on this phone can open ." + FileKit.extOf(m.name) + " files. Use Save to keep it.") }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { progress = -1f; failed = e.message ?: "Could not open the file"; Nav.say(failed) }
        }
    }

    Row(Modifier.width(250.dp).clip(RoundedCornerShape(12.dp)).combinedClickableNoRipple(onClick = { if (selecting) onLong() else open() }, onLong = onLong).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(We.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            if (busy) androidx.compose.material3.CircularProgressIndicator(progress = { shown.coerceIn(0.03f, 1f) }, modifier = Modifier.size(34.dp), color = We.primary, strokeWidth = 2.5.dp)
            else if (out?.failed == true || failed.isNotEmpty()) Icon(Ic.Alert, We.danger, 22.dp)
            else if (!ready && !m.pending) Icon(Ic.Download, We.primary, 22.dp)
            else if (m.type == "audio") Icon(if (playing) Ic.Pause else Ic.Play, We.primary, 24.dp)
            else Icon(icon, We.primary, 22.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name.ifEmpty { "File" }, color = fg, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when {
                failed.isNotEmpty() -> "Tap to try again"
                out?.failed == true -> "Not sent · tap the bubble to retry"
                busy -> (if (out != null) "Sending… " else "Downloading… ") + (shown * 100).toInt() + "%"
                playing -> fmtMs(FilePlayer.pos) + " / " + fmtMs(FilePlayer.len)
                else -> (if (!ready && !m.pending) "Tap to download · " else "") + FileKit.size(m.size) + " · " + FileKit.extOf(m.name).uppercase()
            }
            Text(sub, color = fg.copy(alpha = .6f), style = T.small)
        }
    }
}

private fun fmtMs(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoRipple(onClick: () -> Unit, onLong: () -> Unit) =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))

/** Plays a decrypted video from the cache with the system controls. */
@Composable
fun VideoPlayerScreen(path: String, title: String) {
    var bars by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    LaunchedEffect(bars, touch) { if (bars) { delay(4000); bars = false } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c ->
            VideoView(c).apply {
                val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                setVideoPath(path); setOnPreparedListener { it.isLooping = false; start() }
                setOnErrorListener { _, _, _ -> Nav.say("This video can't be played on this phone"); true }
            }
        }, modifier = Modifier.fillMaxSize().align(Alignment.Center).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { bars = !bars; touch++ },
            onRelease = { try { it.stopPlayback() } catch (_: Exception) {} })
        androidx.compose.animation.AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp).background(Color(0x66000000), RoundedCornerShape(30.dp)), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Close, Color.White, { Nav.pop() })
                Text(title, color = Color.White, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            }
        }
    }
}


/** A video in the chat looks like WhatsApp's: a preview picture with a play button and the length. Tap to play. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoCard(m: Msg, ref: ChatRef, onLong: () -> Unit) {
    val selecting = LocalSelecting.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val out = if (m.pending) Repo.outgoing.firstOrNull { "out_" + it.id == m.id } else null
    val localFile = remember(m.mediaId, m.name, AutoFetch.doneTick) { m.mediaId?.let { FileKit.cachedFile(ctx, it, m.name).takeIf { f -> f.exists() && f.length() > 0 } } }
    // videos sent before previews existed (or received ones): take the picture and length from the copy on this phone
    val info by produceState<FileKit.VideoInfo?>(null, localFile, m.thumb) { if (localFile != null && (m.thumb.isEmpty() || m.dur == 0)) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FileKit.videoInfoOf(ctx, localFile) } }
    val thumb = remember(m.thumb, m.id, out, info) { out?.thumb ?: if (m.thumb.isNotEmpty()) try { Pics.decode(Crypto.unb64(m.thumb)) } catch (_: Exception) { null } else info?.thumb?.let { Pics.decode(it) } }
    val seconds = if (m.dur > 0) m.dur else info?.seconds ?: 0
    var progress by remember(m.id) { mutableFloatStateOf(-1f) }
    val local = localFile != null
    val verdict = DataPrefs.autoVerdict(MediaPolicy.Type.VIDEO, m.size, local, m.from == Repo.uid)
    val autoKey = m.mediaId ?: ""
    LaunchedEffect(m.mediaId, verdict.auto, local) {
        val id = m.mediaId
        if (id != null && local) MediaLedger.noteFile(ref.id, m.id, id, m.name, "video")
        else if (id != null && verdict.auto && !m.pending && m.fkey.isNotEmpty()) AutoFetch.start(autoKey, MediaPolicy.Type.VIDEO, m.size, m.from == Repo.uid) { p -> Repo.fetchFile(m, ref.id, p) }
    }
    val autoBusy = AutoFetch.progress[autoKey]
    val busy = (out != null && !out.failed) || progress >= 0f || autoBusy != null
    val shown = if (out != null) out.progress else if (autoBusy != null && progress < 0f) autoBusy else progress.coerceAtLeast(0f)
    val vw = if (m.w > 0) m.w else info?.w ?: 0; val vh = if (m.h > 0) m.h else info?.h ?: 0
    val ratio = if (vw > 0 && vh > 0) (vw.toFloat() / vh).coerceIn(.6f, 1.8f) else 1.6f
    fun play() {
        if (m.pending || progress >= 0f) return
        if (AutoFetch.running(autoKey)) { AutoFetch.promote(autoKey); return }
        if (!local) MediaNet.warnIfRoaming()
        scope.launch {
            try {
                progress = 0f
                val f = Repo.fetchFile(m, ref.id) { progress = it }
                progress = -1f
                Nav.push(RVideo(f.absolutePath, m.name))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { progress = -1f; Nav.say(e.message ?: "Could not open the video") }
        }
    }
    Box(Modifier.width(254.dp).padding(3.dp).clip(RoundedCornerShape(13.dp)).combinedClickable(onClick = { if (selecting) onLong() else play() }, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xFF16202E))) {
            if (thumb != null) androidx.compose.foundation.Image(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                if (out?.failed == true) Icon(Ic.Alert, Color.White, 26.dp)
                else if (busy) androidx.compose.material3.CircularProgressIndicator(progress = { shown.coerceIn(0.04f, 1f) }, modifier = Modifier.size(38.dp), color = Color.White, strokeWidth = 3.dp, trackColor = Color(0x44FFFFFF))
                else Icon(if (local || m.pending) Ic.Play else Ic.Download, Color.White, 28.dp)
            }
            if (!local && !m.pending && !busy && m.size > 0) Text(FileKit.size(m.size), color = Color.White, style = T.small, modifier = Modifier.align(Alignment.Center).offset(y = 42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 1.dp))
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Video, Color.White, 13.dp); Spacer(Modifier.width(4.dp))
                Text((if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else "Video") + (if (!local && !m.pending && m.size > 0) " · " + FileKit.size(m.size) else ""), color = Color.White, style = T.tiny)
            }
        }
    }
}
