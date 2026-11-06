@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.graphicsLayer
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import com.kafisir.wetalks.voice.Recorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun clock(ts: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
private fun dayLabel(ts: Long): String {
    val f = SimpleDateFormat("yyyyMMdd", Locale.US); val now = System.currentTimeMillis()
    return when {
        f.format(Date(ts)) == f.format(Date(now)) -> "Today"
        f.format(Date(ts)) == f.format(Date(now - 86400000L)) -> "Yesterday"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ts))
    }
}
private val HINGLISH = setOf("hai", "hain", "nahi", "nahin", "kya", "main", "mein", "tum", "aap", "ke", "ki", "ko", "se", "ye", "yeh", "wo", "woh", "bhai", "karo", "kar", "karna", "hoga", "tha", "thi", "aur", "lekin", "par", "per", "ab", "abhi", "bahut", "accha", "acha", "theek", "dekho", "dekhna", "kuch", "mujhe", "tumhe", "apne", "iske", "uske", "dunga", "denge", "raha", "rahi", "rahe", "wala", "wali", "isliye", "kyunki", "matlab", "sab", "kaise", "kyu", "hoon", "hu", "padega", "padega", "logon", "andar", "idhar", "udhar", "bhi", "toh", "to", "mat", "ho", "gaya", "gayi")
/** The recogniser reports "English" for romanised Hindi; name what was really spoken. Null = keep the recogniser's language. */
fun speechLabel(lang: String, text: String): String? {
    if (text.any { it in '\u0900'..'\u097F' }) return "Hindi"
    if (lang.substringBefore('-') != "en") return null
    val w = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    if (w.size < 3) return null
    val hits = w.count { it in HINGLISH && it !in setOf("to", "main", "ho", "hu", "per", "par", "ab", "se") }.let { it + w.count { x -> x in setOf("main", "per", "par", "ab", "se", "to", "ho", "hu") } / 2 }
    return if (hits * 100 / w.size >= 18) "Hinglish (Hindi)" else null
}
/** Some recognisers return Every Word Capitalised; turn that into normal sentence case. */
fun tidyTranscript(t: String): String {
    val words = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size < 4 || words.count { it.first().isUpperCase() } * 100 / words.size < 60) return t.trim()
    val sb = StringBuilder(); var cap = true
    for (ch in t.trim().lowercase()) { sb.append(if (cap && ch.isLetter()) ch.uppercaseChar() else ch); if (ch.isLetter()) cap = false else if (ch == '.' || ch == '?' || ch == '!') cap = true }
    return sb.toString()
}
class BubbleNote(val label: String, val text: String, val loading: Boolean)

private fun isMedia(m: Msg) = m.type == "call" || m.type == "image" || m.type == "sticker" || m.type == "voice" || m.type == "gif" || m.type == "link" || m.type == "video" || m.type == "audio" || m.type == "file"

@Composable private fun nameOf(uid: String, fallback: String): String { val u by Repo.user(uid).collectAsState(); return u?.name ?: fallback }

@Composable
fun ChatScreen(ref: ChatRef) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = Repo.uid
    val primaryC = We.primary
    val saved = ref.kind == Kind.DM && ref.peer == me
    val (name0, photo, seed) = titleOf(ref)
    val title = if (saved) "Saved Messages" else name0
    val blocked by Repo.blockedUsers.collectAsState()
    var limit by remember { mutableIntStateOf(50) }
    val msgsRaw by produceState<List<Msg>>(emptyList(), ref, limit) { Repo.rawMessages(ref, limit).collect { raws -> value = raws.map { Repo.decrypt(ref, it) } } }
    var hideTick by remember { mutableIntStateOf(0) }
    var cut by remember(ref) { mutableLongStateOf(Repo.cutoff(ref.id)) }
    val outs = Repo.outgoing.filter { it.ref.id == ref.id }
    val outMsgs = outs.map { o -> Msg("out_" + o.id, me, o.ts, o.type, o.text, null, o.dur, null, false, false, emptyMap(), false, true, o.wave, null, "", o.w, o.h, name = o.name, size = o.size) }
    val msgs = msgsRaw.filter { hideTick >= 0 && !it.deleted && !Repo.isHidden(it.id) && it.from !in blocked && (it.ts == 0L || it.ts > cut) } + outMsgs
    val typing by remember(ref) { Repo.typing(ref) }.collectAsState(emptyList())
    val pinIds by remember(ref) { Repo.pins(ref) }.collectAsState(emptyList())
    val peerUser = if (ref.kind == Kind.DM && !saved) Repo.user(ref.peer!!).collectAsState().value else null
    val group = if (ref.kind == Kind.GROUP) Repo.group(ref.id).collectAsState().value else null
    val channel = if (ref.kind == Kind.CHANNEL) Repo.channel(ref.id).collectAsState().value else null
    val peerRead by (if (ref.kind == Kind.DM && !saved) remember(ref) { Repo.readTs(ref, ref.peer!!) } else remember { kotlinx.coroutines.flow.flowOf(0L) }).collectAsState(0L)
    val subscribed by produceState(true, ref, channel?.id) { if (ref.kind == Kind.CHANNEL) value = Repo.isSubscribed(ref.id) }
    var joined by remember(ref) { mutableStateOf<Boolean?>(null) }
    val isMember = joined ?: subscribed
    val privileged = when (ref.kind) { Kind.DM -> true; Kind.GROUP -> group?.members?.get(me) == "admin"; Kind.CHANNEL -> channel != null && (channel.owner == me || me in channel.admins) }
    val perms = (if (ref.kind == Kind.GROUP) group?.perms else if (ref.kind == Kind.CHANNEL) channel?.perms else null) ?: emptyMap()
    val baseSend = privileged || when (ref.kind) { Kind.GROUP -> perms["send"] != false; Kind.CHANNEL -> perms["followers"] == true && isMember; else -> true }
    fun allowed(k: String) = privileged || (baseSend && perms[k] != false)
    val textOk = allowed("text"); val imageOk = allowed("image"); val stickerOk = allowed("sticker"); val voiceOk = allowed("voice")
    val canPost = baseSend && (textOk || imageOk || stickerOk || voiceOk)
    val canPin = when (ref.kind) { Kind.DM -> true; Kind.GROUP -> privileged || perms["pin"] == true; Kind.CHANNEL -> privileged }

    var reply by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    val tfs = rememberTextFieldState()
    val input = tfs.text.toString()
    fun setInput(v: String) = tfs.setTextAndPlaceCursorAtEnd(v)
    var menuMsg by remember { mutableStateOf<Msg?>(null) }
    var attach by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var delChat by remember { mutableStateOf(false) }
    var clearAsk by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var typedPreview by remember { mutableStateOf<LinkPreview?>(null) }
    var dismissedUrl by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<List<Msg>?>(null) }
    val trNotes = remember { mutableStateMapOf<String, BubbleNote>() }
    val txNotes = remember { mutableStateMapOf<String, BubbleNote>() }
    var pickLang by remember { mutableStateOf<Msg?>(null) }
    var unreadableInfo by remember { mutableStateOf(false) }
    var outActions by remember { mutableStateOf<Repo.Outgoing?>(null) }
    var forwarding by remember { mutableStateOf<List<Msg>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    var pinCursor by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    fun toast(m: String) = Nav.say(m)

    LaunchedEffect(ref.id, group != null) { if (ref.kind == Kind.GROUP && group != null) try { Repo.repairGroupKeys(ref.id) } catch (_: Exception) {} }
    LaunchedEffect(msgs.size, ref) { Repo.markRead(ref); com.kafisir.wetalks.data.Notifier.cancel(ref.id) }
    val animOn = UiPrefs.wallpaper.intValue >= AWalls.BASE || Bubbles.animated(UiPrefs.bubbleStyle.intValue)
    LaunchedEffect(animOn) { while (animOn) androidx.compose.runtime.withFrameNanos { Clock.t = (it / 1_000_000_000.0 % 3600.0).toFloat() } }
    // jump to a message (picked from the shared-media lists, or the original of a reply) and flash it so it is easy to spot
    var jumpReq by remember(ref) { mutableStateOf(Nav.jumpTo.also { Nav.jumpTo = null }) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msgs, jumpReq) {
        val j = jumpReq ?: return@LaunchedEffect
        val idx = msgs.reversed().indexOfFirst { it.id == j }
        if (idx >= 0) {
            jumpReq = null   // clearing this restarts the effect, so the scroll runs in its own scope or it would be cancelled
            scope.launch { delay(120); listState.animateScrollToItem(idx); highlightId = j }
        } else if (msgs.size >= limit && limit < 2000) limit += 300
        else if (msgs.isNotEmpty()) { jumpReq = null; toast("That message is no longer available") }
    }
    LaunchedEffect(highlightId) { if (highlightId != null) { delay(3200); highlightId = null } }
    // new message arrives or I send one: show it (unless the reader has scrolled far up through history)
    val newestId = msgs.lastOrNull()?.id
    LaunchedEffect(newestId) {
        val last = msgs.lastOrNull() ?: return@LaunchedEffect
        if (jumpReq != null) return@LaunchedEffect
        if (last.from == me || listState.firstVisibleItemIndex <= 6) listState.animateScrollToItem(0)
    }
    LaunchedEffect(input) { if (ref.kind != Kind.CHANNEL && !saved) { if (input.isNotEmpty()) { Repo.setTyping(ref, true); delay(3000) }; Repo.setTyping(ref, false) } }
    DisposableEffect(ref) { onDispose { Repo.setTyping(ref, false); com.kafisir.wetalks.voice.Speaker.stop() } }
    // the link preview is built while the person is still typing, so it is ready the moment they press send
    val typedUrl = remember(input, editing) { if (UiPrefs.linkPreview.value && editing == null) LinkPreviews.firstUrl(input) else null }
    LaunchedEffect(typedUrl) {
        typedPreview = null
        val u = typedUrl
        if (u != null && u != dismissedUrl) { delay(600); typedPreview = try { LinkPreviews.fetch(u) } catch (e: Exception) { null } }
    }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.clear() }

    val subtitle: String? = when {
        selecting -> null
        saved -> "Notes to yourself · encrypted"
        typing.isNotEmpty() -> if (ref.kind == Kind.DM) "typing…" else "someone is typing…"
        ref.kind == Kind.DM -> when {
            peerUser == null -> null
            peerUser.online -> "online"
            peerUser.showLastSeen && peerUser.lastSeen > 0 -> "last seen " + timeShort(peerUser.lastSeen)
            else -> peerUser.username.takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "tap for info"
        }
        ref.kind == Kind.GROUP -> group?.let { "${it.members.size} members" }
        else -> channel?.let { "${it.subCount} subscribers" }
    }

    /** What "Listen" reads: the translation if one is showing, else the transcript of a voice message, else the text / caption. */
    fun speakTextOf(m: Msg): String {
        trNotes[m.id]?.takeIf { !it.loading && it.text.isNotEmpty() }?.let { return it.text }
        if (m.type == "voice") return txNotes[m.id]?.takeIf { !it.loading }?.text.orEmpty()
        return if (m.type == "text" || m.type == "link" || m.type == "image") m.text else ""
    }

    fun sendText() {
        val t = tfs.text.toString().trim(); if (t.isEmpty() && editing?.type != "image") return
        val e = editing; val r = reply
        tfs.clearText(); editing = null; reply = null
        Repo.scope.launch {
            try {
                if (e != null) { if (e.type == "image") Repo.editCaption(ref, e, t) else Repo.editMessage(ref, e.id, t) }
                else {
                    val url = if (UiPrefs.linkPreview.value) LinkPreviews.firstUrl(t) else null
                    val ready = typedPreview?.takeIf { it.url == url }
                    typedPreview = null
                    if (ready != null) Repo.send(ref, "link", JSONObject().put("t", t).put("u", ready.url).put("ti", ready.title).put("d", ready.desc).put("i", ready.thumb).toString(), r?.id, t.take(90))
                    else {
                        // the text goes out immediately; if a preview can be built it is attached to the same message a moment later
                        val mid = Repo.send(ref, "text", t, r?.id, t.take(90))
                        if (url != null && url != dismissedUrl) Repo.scope.launch { try { LinkPreviews.fetch(url)?.let { Repo.attachLinkPreview(ref, mid, t, it) } } catch (_: Exception) {} }
                    }
                }
            } catch (ex: Exception) { toast(ex.message ?: "Could not send"); setInput(t) }
        }
    }

    /** Stickers and GIFs from the keyboard arrive as content URIs. */
    fun sendKeyboardImage(uri: Uri) {
        val mime = ctx.contentResolver.getType(uri) ?: "image/png"
        val bytes = try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (ex: Exception) { null }
        if (bytes == null) { toast("Could not read that image"); return }
        if (bytes.size > 1_200_000) { toast("That file is too big to send (max 1.2 MB)"); return }
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val replyId = reply?.id; reply = null
        val o = Repo.Outgoing(java.util.UUID.randomUUID().toString(), ref, "gif", w = maxOf(opts.outWidth, 0), h = maxOf(opts.outHeight, 0), thumb = Pics.decodeSampled(bytes, 400))
        Repo.runOutgoing(o) {
            val id = Repo.uploadMedia(ref, bytes, mime)
            Repo.send(ref, "gif", JSONObject().put("m", id).put("mime", mime).put("w", o.w).put("h", o.h).toString(), replyId, if (mime.contains("gif")) "GIF" else "Sticker")
        }
    }
    val keyboardReceiver = object : ReceiveContentListener {
        override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
            if (!stickerOk || !transferableContent.hasMediaType(MediaType.Image)) return transferableContent
            return transferableContent.consume { item -> val u = item.uri; if (u != null) { sendKeyboardImage(u); true } else false }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { us: List<Uri> ->
        if (us.isNotEmpty()) { Nav.pendingPhotos = us; Nav.pendingRef = ref; Nav.pendingReply = reply?.id; reply = null; Nav.push(RPhotoEdit) }
    }

    // ---------------- files (video / audio / documents, max 30 MB) and the camera
    fun sendPickedFile(uri: Uri, wanted: String?) {
        val p = try { FileKit.check(ctx, uri, wanted) } catch (e: IllegalArgumentException) { toast(e.message ?: "This file can't be sent"); return }
        val replyId = reply?.id; reply = null
        val o = Repo.Outgoing(java.util.UUID.randomUUID().toString(), ref, p.kind, name = p.name, size = p.size)
        Repo.runOutgoing(o) { Repo.sendFile(ref, uri, p, replyId, null) { o.progress = it } }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u: Uri? -> if (u != null) sendPickedFile(u, "video") }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) sendPickedFile(u, "audio") }
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) sendPickedFile(u, null) }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val u = camUri
        if (ok && u != null) { Nav.pendingPhotos = listOf(u); Nav.pendingRef = ref; Nav.pendingReply = reply?.id; reply = null; Nav.push(RPhotoEdit) }
    }
    fun launchCamera() {
        val f = java.io.File(ctx.cacheDir, "cam_" + System.nanoTime() + ".jpg")
        val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        camUri = u; try { takePhoto.launch(u) } catch (e: Exception) { toast("No camera app found") }
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok: Boolean -> if (ok) launchCamera() else toast("Allow the camera to take photos") }

    // ---------------- calls
    val startCall = if (ref.kind == Kind.DM && !saved) rememberCallStarter(ref.peer!!) else { _: Boolean -> }
    fun placeCall(v: Boolean) = startCall(v)

    // ---------------- voice recording (hold to record, slide left to cancel, slide up to lock hands-free)
    var rec by remember { mutableStateOf<Recorder?>(null) }
    var locked by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var willCancel by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var secs by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val levels = remember { mutableStateListOf<Float>() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = ctx.resources.displayMetrics.density
    val cancelPx = 110f * density; val lockPx = 90f * density
    fun endRec(send: Boolean) {
        val r = rec ?: return; rec = null; locked = false; paused = false; willCancel = false; dragX = 0f; dragY = 0f; levels.clear()
        if (!send) { r.cancel(); return }
        val replyId = reply?.id; reply = null
        val quick = r.elapsedSec
        if (quick < 1) { r.cancel(); toast("Hold the mic and release to send"); return }
        val bars = levels.toList().takeLast(40).let { l -> if (l.isEmpty()) List(40) { 4 } else List(40) { i -> (2 + l[(i * l.size / 40).coerceAtMost(l.size - 1)] * 29).toInt().coerceIn(2, 31) } }
        val o = Repo.Outgoing(java.util.UUID.randomUUID().toString(), ref, "voice", dur = quick, wave = bars)
        Repo.runOutgoing(o) {
            val res = r.finish() ?: throw IllegalStateException("Could not process the recording")
            if (res.seconds < 1) throw IllegalStateException("The recording was too short")
            val id = Repo.uploadMedia(ref, res.bytes, "audio/mp4")
            Repo.send(ref, "voice", JSONObject().put("m", id).put("d", res.seconds).put("w", JSONArray(res.wave)).toString(), replyId, "Voice message")
        }
    }
    LaunchedEffect(rec) { while (rec != null) { val r = rec!!; level = r.level(); secs = r.elapsedSec; levels.add(level); if (levels.size > 60) levels.removeAt(0); if (secs >= Recorder.MAX_SECONDS) endRec(true); delay(90) } }
    fun startRec() {
        rec = Recorder(ctx).also { try { it.start(); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) } catch (e: Exception) { rec = null; toast("Could not start recording") } }
        locked = false; paused = false; willCancel = false; dragX = 0f; dragY = 0f; levels.clear()
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) toast("Microphone permission is needed for voice messages") }

    // ---------------- translate / transcribe (messages only change on this phone)
    /** Voice message -> text (language detected automatically). Returns the text, or null after showing the error. */
    suspend fun transcribeNow(m: Msg, show: Boolean = true): String? {
        if (show) txNotes[m.id] = BubbleNote("Transcribing…", "", true)
        return try {
            val bytes = m.mediaId?.let { Repo.loadMedia(ref, it) } ?: throw IllegalStateException("Could not load the voice message")
            val o = com.kafisir.wetalks.voice.Transcriber.transcribe(ctx, bytes, I18n.speechLang.ifEmpty { null })
            val nm = speechLabel(o.lang, o.text) ?: (I18n.languages.firstOrNull { it.code.substringBefore('-') == o.lang.substringBefore('-') }?.english ?: o.lang)
            val clean = tidyTranscript(o.text)
            if (show) txNotes[m.id] = BubbleNote("Transcript · $nm", clean, false)
            clean
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (show) txNotes.remove(m.id); toast(e.message ?: "Could not transcribe"); null }
    }
    fun doTranslate(m: Msg, to: String) {
        trNotes[m.id] = BubbleNote("Translating…", "", true)
        Repo.scope.launch {
            try {
                // translating a voice message needs its text, but that text is only used behind the scenes; the transcript itself shows only when the person taps Transcribe
                val src = if (m.type == "voice") (txNotes[m.id]?.takeIf { !it.loading && it.text.isNotEmpty() }?.text ?: transcribeNow(m, show = false)) else m.text
                if (src.isNullOrBlank()) { trNotes.remove(m.id); return@launch }
                val r = Translator.translate(src, to)
                trNotes[m.id] = BubbleNote("Translated from " + I18n.languages.firstOrNull { it.code == r.detected }?.english.orEmpty().ifEmpty { "another language" } + " to " + I18n.byCode(to).english, r.text, false)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { trNotes.remove(m.id); toast(e.message ?: "Could not translate") }
        }
    }
    fun doTranscribe(m: Msg) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micPerm.launch(Manifest.permission.RECORD_AUDIO); toast("Allow the microphone, then tap Transcribe again"); return }
        Repo.scope.launch { transcribeNow(m) }
    }

    val wall = UiPrefs.wallpaper.intValue
    val bgBrush = when (wall) { 1 -> Brush.verticalGradient(listOf(We.primary.copy(alpha = .10f), We.bg)); 2 -> Brush.verticalGradient(listOf(We.primary.copy(alpha = .22f), We.primary.copy(alpha = .06f))); else -> SolidColor(We.bg) }

    Column(Modifier.fillMaxSize().chatWallpaper().imePadding()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().background(We.appBar).statusBarsPadding().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Close, We.onAppBar, { selected.clear() })
                Text("${selected.size} selected", color = We.onAppBar, style = T.title, modifier = Modifier.weight(1f))
                IconBtn(Ic.Copy, We.onAppBar, {
                    val t = msgs.filter { it.id in selected && (it.type == "text" || it.type == "link") }.joinToString("\n") { it.text }
                    if (t.isNotEmpty()) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("messages", t)); toast("Copied") }
                    selected.clear()
                })
                IconBtn(Ic.Forward, We.onAppBar, { forwarding = msgs.filter { it.id in selected } })
                IconBtn(Ic.Trash, We.onAppBar, { deleting = msgs.filter { it.id in selected } })
            }
        } else TopBar(title, onBack = { Nav.pop() }, subtitle = subtitle,
            leading = { if (saved) Box(Modifier.size(38.dp).clip(CircleShape).background(We.onAppBar.copy(alpha = .2f)), contentAlignment = Alignment.Center) { Icon(Ic.Bookmark, We.onAppBar, 20.dp) } else Avatar(title, photo, 38.dp, seed) },
            titleClick = { if (!saved) when (ref.kind) { Kind.DM -> Nav.push(RProfile(ref.peer!!)); Kind.GROUP -> Nav.push(RGroupInfo(ref.id)); Kind.CHANNEL -> Nav.push(RChannelInfo(ref.id)) } },
            actions = {
                if (ref.kind == Kind.DM && !saved) {
                    IconBtn(Ic.Phone, We.onAppBar, { placeCall(false) })
                    IconBtn(Ic.Video, We.onAppBar, { placeCall(true) })
                }
                IconBtn(Ic.Dots, We.onAppBar, { menu = true })
            })

        // pinned message banner
        val livePins = pinIds.filter { id -> msgs.any { it.id == id } || msgs.isEmpty() }
        if (livePins.isNotEmpty() && !selecting) {
            val idx = pinCursor.coerceIn(0, livePins.size - 1).let { livePins.size - 1 - it }.coerceIn(0, livePins.size - 1)
            val pm = msgs.firstOrNull { it.id == livePins[idx] }
            Row(Modifier.fillMaxWidth().background(We.surface).combinedClickable(onClick = {
                pm?.let { target -> val i = msgs.reversed().indexOfFirst { it.id == target.id }; if (i >= 0) scope.launch { listState.animateScrollToItem(i) } }
                pinCursor = (pinCursor + 1) % livePins.size
            }).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(34.dp).clip(CircleShape).background(We.primary)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (livePins.size > 1) "Pinned message · ${pinCursor.coerceIn(0, livePins.size - 1) + 1}/${livePins.size}" else "Pinned message", color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                    Text(pm?.let { previewText(it.type, it.text).ifEmpty { "Photo" } } ?: "…", color = We.text2, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Ic.Pin, We.text2, 18.dp)
                if (canPin) IconBtn(Ic.Close, We.text2, { Repo.setPin(ref, livePins[idx], false) }, 18.dp, Modifier.size(40.dp)) else Spacer(Modifier.width(12.dp))
            }
            Divider()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(We.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) { Icon(if (saved) Ic.Bookmark else Ic.Lock, We.primary, 28.dp) }
                Spacer(Modifier.height(12.dp))
                Text(if (saved) "Your personal space" else if (ref.kind == Kind.CHANNEL) "No posts yet" else "No messages yet", color = We.text, style = T.name)
                Text(if (saved) "Save notes, photos and voice messages here. Only you can read them." else if (ref.kind == Kind.CHANNEL) "Posts from admins show up here." else "Messages are end-to-end encrypted. Only the people in this chat can read them.", color = We.text2, style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            val rev = msgs.reversed()
            LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                itemsIndexed(rev, key = { _, m -> m.id }) { i, m ->
                    val older = rev.getOrNull(i + 1); val newer = rev.getOrNull(i - 1)
                    if (i == rev.size - 1 && msgs.size >= limit) LaunchedEffect(limit) { limit += 40 }
                    val mine = m.from == me
                    val sel = m.id in selected
                    Column {
                        if (older == null || dayLabel(older.ts) != dayLabel(m.ts)) DayChip(dayLabel(m.ts))
                        val sameAsOlder = older != null && older.from == m.from && dayLabel(older.ts) == dayLabel(m.ts)
                        val hl by animateColorAsState(if (m.id == highlightId) We.primary.copy(alpha = .30f) else Color.Transparent, tween(500), label = "hl")
                        Row(Modifier.fillMaxWidth().background(if (sel) We.primary.copy(alpha = .14f) else hl).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) Box(Modifier.padding(start = 4.dp, end = 6.dp).size(22.dp).clip(CircleShape).background(if (sel) We.primary else We.line), contentAlignment = Alignment.Center) { if (sel) Icon(Ic.Check, We.onPrimary, 14.dp) }
                            Box(Modifier.weight(1f)) { SwipeReply(enabled = canPost && !m.pending && !m.deleted && !selecting, onReply = { reply = m; editing = null }) {
                                Bubble(m, mine, ref, rev.firstOrNull { it.id == m.rid }, showName = ref.kind == Kind.GROUP && !mine && !sameAsOlder,
                                    read = ref.kind == Kind.DM && !saved && peerRead >= m.ts && m.ts > 0, lastInRun = newer == null || newer.from != m.from, pinned = m.id in pinIds,
                                    onClick = { if (m.pending) { outActions = Repo.outgoing.firstOrNull { "out_" + it.id == m.id } } else if (selecting) { if (sel) selected.remove(m.id) else selected.add(m.id) } else if (m.unreadable) unreadableInfo = true },
                                    onLong = { if (m.pending) { outActions = Repo.outgoing.firstOrNull { "out_" + it.id == m.id } } else if (!m.deleted) { if (selecting) { if (sel) selected.remove(m.id) else selected.add(m.id) } else menuMsg = m } },
                                    onReact = { e -> Repo.react(ref, m.id, if (m.reactions[me] == e) null else e) },
                                    notes = listOfNotNull(txNotes[m.id], trNotes[m.id]), onQuote = { jumpReq = it }) }
                            }
                        }
                        val tight = UiPrefs.compact.value
                        val gap = if (newer == null) 4.dp else if (newer.from == m.from && !isMedia(m) && !isMedia(newer)) (if (tight) 1.dp else 3.dp) else if (newer.from == m.from) (if (tight) 3.dp else 7.dp) else (if (tight) 6.dp else 12.dp)
                        Spacer(Modifier.height(gap))
                    }
                }
            }
        }

        // ---------------- input
        if (!canPost) {
            Column(Modifier.background(We.surface)) {
                Divider()
                if (isMember) Text(if (ref.kind == Kind.GROUP) "Only admins can send messages in this group" else "Only admins can post in this channel", color = We.text2, style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
                else Box(Modifier.navigationBarsPadding().padding(12.dp)) { PrimaryButton("Join channel", { scope.launch { try { Repo.subscribe(ref.id); joined = true } catch (e: Exception) { toast("Could not join") } } }) }
            }
        } else if (!selecting) Column(Modifier.background(We.surface)) {
            Divider()
            if (reply != null || editing != null) {
                val q = editing ?: reply!!
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(34.dp).clip(CircleShape).background(We.primary)); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Edit message" else "Reply to " + (if (q.from == me) "yourself" else nameOf(q.from, title)), color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                        Text(previewText(q.type, q.text), color = We.text2, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(Ic.Close, We.text2, { reply = null; if (editing != null) { editing = null; setInput("") } }, 20.dp)
                }
            }
            typedPreview?.let { p ->
                val th = remember(p.thumb) { Pics.decodeB64(p.thumb) }
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(46.dp).clip(CircleShape).background(We.primary)); Spacer(Modifier.width(10.dp))
                    if (th != null) { Image(th, null, Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(p.title.ifEmpty { p.url }, color = We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.desc.ifEmpty { p.url }, color = We.text2, style = T.tiny, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(Ic.Close, We.text2, { dismissedUrl = p.url; typedPreview = null }, 20.dp)
                }
            }
            Box {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                    if (rec != null) {
                        RecorderBar(secs, levels, locked, paused, willCancel, dragX, cancelPx, onTrash = { endRec(false) }, onPauseToggle = { paused = !paused; rec?.paused = paused })
                    } else {
                        if (imageOk || stickerOk) IconBtn(Ic.Plus, We.text2, { attach = true }) else Spacer(Modifier.width(10.dp))
                        Row(Modifier.weight(1f).heightIn(min = 46.dp).padding(vertical = 2.dp).clip(RoundedCornerShape(23.dp)).background(We.field).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                if (input.isEmpty()) Text(if (textOk) "Message" else "Text messages are turned off", color = We.text2, style = T.body)
                                if (textOk) BasicTextField(tfs, Modifier.fillMaxWidth().contentReceiver(keyboardReceiver), textStyle = T.body.copy(color = We.text), cursorBrush = SolidColor(We.primary),
                                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                            }
                            if (stickerOk) IconBtn(Ic.Smile, We.text2, { stickers = true }, 22.dp, Modifier.size(40.dp)) else Spacer(Modifier.width(10.dp))
                        }
                    }
                    // one persistent mic / send button so the press-and-hold gesture is never interrupted
                    if (rec == null) Spacer(Modifier.width(6.dp))
                    if ((rec != null && locked) || (rec == null && input.isNotBlank())) Box(Modifier.size(46.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { if (rec != null) endRec(true) else sendText() }), contentAlignment = Alignment.Center) { Icon(Ic.Send, We.onPrimary, 22.dp) }
                    else if (!voiceOk && rec == null) Spacer(Modifier.width(4.dp))
                    else MicGesture(rec != null, willCancel, level, dragX, dragY,
                        onStart = {
                            if (rec != null) true
                            else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { startRec(); rec != null }
                            else { micPerm.launch(Manifest.permission.RECORD_AUDIO); false }
                        },
                        onMove = { dx, dy ->
                            if (rec != null && !locked) {
                                dragX = dx.coerceAtMost(0f); dragY = dy.coerceAtMost(0f)
                                val cancelNow = dx < -cancelPx
                                if (cancelNow != willCancel) { willCancel = cancelNow; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
                                if (dy < -lockPx && !willCancel) { locked = true; dragX = 0f; dragY = 0f; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
                            }
                        },
                        onUp = { dx -> if (rec != null && !locked) endRec(!willCancel && dx > -cancelPx) })
                }
                if (rec != null && !locked) LockPill((-dragY / lockPx).coerceIn(0f, 1f), dragY, Modifier.align(Alignment.TopEnd).padding(end = 9.dp).offset(y = (-118).dp))
            }
        }
    }

    // ---------------- popups / sheets
    if (attach) Sheet({ attach = false }) {
        if (imageOk) SheetItem(Ic.Camera, "Camera") { attach = false; if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera() else camPerm.launch(Manifest.permission.CAMERA) }
        if (imageOk) SheetItem(Ic.Image, "Photos") { attach = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        if (imageOk) SheetItem(Ic.Video, "Video (max 30 MB)") { attach = false; videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
        if (imageOk) SheetItem(Ic.Audio, "Audio file (max 30 MB)") { attach = false; audioPicker.launch(arrayOf("audio/*")) }
        if (imageOk) SheetItem(Ic.File, "Document (max 30 MB)") { attach = false; docPicker.launch(arrayOf("*/*")) }
        if (stickerOk) SheetItem(Ic.Smile, "Sticker") { attach = false; stickers = true }
    }
    if (stickers) StickerSheet({ stickers = false }) { payload ->
        stickers = false
        scope.launch { try { Repo.send(ref, "sticker", payload, reply?.id, "Sticker"); reply = null } catch (e: Exception) { toast(e.message ?: "Could not send") } }
    }
    menuMsg?.let { m ->
        MessageMenu(m, ref, canReply = canPost, canPin = canPin, pinned = m.id in pinIds, mine = m.from == me, onDismiss = { menuMsg = null },
            onReact = { e -> Repo.react(ref, m.id, if (m.reactions[me] == e) null else e); menuMsg = null },
            onReply = { reply = m; editing = null; menuMsg = null },
            onCopy = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", m.text)); menuMsg = null; toast("Copied") },
            speakText = speakTextOf(m), onSpeak = { menuMsg = null; com.kafisir.wetalks.voice.Speaker.speak(ctx, m.id, speakTextOf(m), I18n.speechLang, ::toast) },
            onSaveFile = if (m.type in listOf("video", "audio", "file") && !m.pending) ({
                menuMsg = null
                scope.launch { try { val f = Repo.fetchFile(m) {}; toast(if (FileKit.saveToDownloads(ctx, f, m.name, FileKit.mimeOf(FileKit.extOf(m.name)))) "Saved to Downloads/WeTalks" else "Could not save") } catch (e: Exception) { toast(e.message ?: "Could not save") } }
            }) else null,
            onPin = { Repo.setPin(ref, m.id, m.id !in pinIds); menuMsg = null },
            onForward = { forwarding = listOf(m); menuMsg = null },
            onEdit = { editing = m; reply = null; setInput(m.text); menuMsg = null },
            onSelect = { selected.add(m.id); menuMsg = null },
            onTranslate = if (m.type == "voice" || (m.text.isNotBlank() && m.type in listOf("text", "link", "image"))) ({ menuMsg = null; doTranslate(m, I18n.translateTo) }) else null,
            onTranslateTo = if (m.type == "voice" || (m.text.isNotBlank() && m.type in listOf("text", "link", "image"))) ({ menuMsg = null; pickLang = m }) else null,
            onTranscribe = if (m.type == "voice") ({ menuMsg = null; doTranscribe(m) }) else null,
            onHideNotes = if (trNotes.containsKey(m.id) || txNotes.containsKey(m.id)) ({ menuMsg = null; trNotes.remove(m.id); txNotes.remove(m.id) }) else null,
            onSave = { menuMsg = null; Repo.scope.launch { val b = try { m.mediaId?.let { Repo.loadMedia(ref, it) } } catch (e: Exception) { null }; toast(if (b != null && Gallery.save(ctx, b, m.mime.ifEmpty { "image/jpeg" })) "Saved to gallery" else "Could not save") } },
            onDelete = { deleting = listOf(m); menuMsg = null })
    }
    deleting?.let { list ->
        val canAll = when (ref.kind) { Kind.DM -> true; Kind.GROUP -> list.all { it.from == me } || group?.members?.get(me) == "admin"; Kind.CHANNEL -> canPost }
        val peerName = if (ref.kind == Kind.DM && !saved) title else null
        Sheet({ deleting = null }) {
            Text(if (list.size == 1) "Delete message?" else "Delete ${list.size} messages?", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
            SheetItem(Ic.Trash2, "Delete for me") { list.forEach { Repo.hideForMe(it.id) }; hideTick++; selected.clear(); deleting = null }
            if (canAll) SheetItem(Ic.Trash, if (peerName != null) "Delete for me and $peerName" else "Delete for everyone", danger = true) {
                val ids = list.map { it.id }; selected.clear(); deleting = null
                scope.launch { try { Repo.deleteMessages(ref, ids) } catch (ex: Exception) { toast("Could not delete: " + (ex.message ?: "")) } }
            }
        }
    }
    outActions?.let { o ->
        Sheet({ outActions = null }) {
            Text(if (o.failed) "Couldn't send" else "Sending…", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
            if (o.failed) Text(o.error, color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            if (o.failed) SheetItem(Ic.Rotate, "Try again") { outActions = null; Repo.retryOutgoing(o) }
            SheetItem(Ic.Trash, if (o.failed) "Delete" else "Cancel sending", danger = true) { outActions = null; Repo.dropOutgoing(o) }
        }
    }
    if (unreadableInfo) Sheet({ unreadableInfo = false }) {
        val n = msgs.count { it.unreadable }
        Text("These messages can't be opened", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Text("They were locked with an encryption key that is no longer on this phone (the app's data was cleared, it was reinstalled, or the account was used on another phone). Messages sent from now on work normally. If you backed the key up, go to Me → Privacy → Back up encryption key on the old phone, then restore it here.", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        SheetItem(Ic.Trash2, "Hide $n unreadable message${if (n == 1) "" else "s"}") { msgs.filter { it.unreadable }.forEach { Repo.hideForMe(it.id) }; hideTick++; unreadableInfo = false }
        SheetItem(Ic.Key, "Back up my key now") { unreadableInfo = false; Nav.pop(); Nav.push(RPrivacy) }
    }
    pickLang?.let { m ->
        Sheet({ pickLang = null }) {
            var q by remember { mutableStateOf("") }
            Text("Translate to", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.field).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.Search, We.text2, 18.dp); Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) { if (q.isEmpty()) Text("Search languages", color = We.text2, style = T.body); BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = T.body.copy(color = We.text), cursorBrush = SolidColor(We.primary)) }
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                I18n.languages.filter { q.isBlank() || it.english.contains(q, true) || it.native.contains(q, true) }.forEach { l ->
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { pickLang = null; doTranslate(m, l.code) }).padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.native, color = We.text, style = T.body, modifier = Modifier.weight(1f)); Text(l.english, color = We.text2, style = T.small)
                    }
                }
            }
        }
    }
    forwarding?.let { list ->
        ForwardSheet(list.size, { forwarding = null }) { targets ->
            forwarding = null; selected.clear()
            Repo.scope.launch { try { for (t in targets) list.sortedBy { it.ts }.forEach { Repo.forward(ref, it, t) }; toast(if (targets.size == 1) "Forwarded" else "Forwarded to ${targets.size} chats") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: "Could not forward") } }
        }
    }
    if (clearAsk) Sheet({ clearAsk = false }) {
        val both = ref.kind == Kind.DM
        Text("Clear chat history?", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Text(if (both) (if (saved) "All your saved notes will be deleted." else "Every message, photo and voice note is deleted for you and for $title. This can't be undone.") else "Messages are hidden on this phone only.", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        SheetItem(Ic.Trash, if (both) "Clear for everyone" else "Clear for me", danger = true) { clearAsk = false; Repo.scope.launch { try { Repo.clearHistory(ref); cut = System.currentTimeMillis(); toast("Chat history cleared") } catch (e: Exception) { toast("Could not clear: " + (e.message ?: "")) } } }
    }
    if (delChat) DeleteChatDialog(ref, title, { delChat = false }) { Nav.pop() }
    if (menu) Sheet({ menu = false }) {
        when (ref.kind) {
            Kind.DM -> if (!saved) {
                SheetItem(Ic.User, "View profile") { menu = false; Nav.push(RProfile(ref.peer!!)) }
                SheetItem(Ic.Block, if (ref.peer in blocked) "Unblock" else "Block", danger = ref.peer !in blocked) { Repo.block(ref.peer!!, ref.peer !in blocked); menu = false }
                SheetItem(Ic.Trash2, "Clear chat history") { menu = false; clearAsk = true }
                SheetItem(Ic.Trash, "Delete chat", danger = true) { menu = false; delChat = true }
            } else { SheetItem(Ic.Trash2, "Clear all notes") { menu = false; clearAsk = true }; SheetItem(Ic.Info, "Only you can see this chat") { menu = false } }
            Kind.GROUP -> { SheetItem(Ic.Group, "Group info") { menu = false; Nav.push(RGroupInfo(ref.id)) }; SheetItem(Ic.Trash2, "Clear history (only for me)") { menu = false; clearAsk = true } }
            Kind.CHANNEL -> {
                SheetItem(Ic.Info, "Channel info") { menu = false; Nav.push(RChannelInfo(ref.id)) }
                if (isMember && channel?.owner != me) SheetItem(Ic.Logout, "Leave channel", danger = true) { menu = false; scope.launch { Repo.unsubscribe(ref.id); Nav.pop() } }
            }
        }
    }
}

@Composable
fun DayChip(t: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(t, color = We.text2, style = T.tiny, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(We.field).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
fun Bubble(m: Msg, mine: Boolean, ref: ChatRef, quoted: Msg?, showName: Boolean, read: Boolean, lastInRun: Boolean, pinned: Boolean, onClick: () -> Unit, onLong: () -> Unit, onReact: (String) -> Unit, notes: List<BubbleNote> = emptyList(), onQuote: (String) -> Unit = {}) {
    val r = UiPrefs.bubble.intValue.dp
    val shape = RoundedCornerShape(r, r, if (mine && lastInRun) 4.dp else r, if (!mine && lastInRun) 4.dp else r)
    val bStyle = UiPrefs.bubbleStyle.intValue
    val fg = Bubbles.fgFor(bStyle, mine) ?: if (mine) We.onSent else We.onRecv
    val isSticker = (m.type == "sticker" || m.type == "gif") && !m.deleted
    val scale = UiPrefs.textScale.floatValue
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.then(if (!isSticker && m.type != "image" && !m.deleted && (quoted != null || m.rid != null)) Modifier.width(IntrinsicSize.Max) else Modifier).then(if (isSticker) Modifier else Modifier.bubbleLook(mine, shape))
                .combinedClickable(onClick = onClick, onLongClick = onLong).then(if (isSticker || m.type == "image") Modifier else Modifier.padding(start = 13.dp, end = 13.dp, top = 8.dp, bottom = 7.dp))) {
                if (showName) { val u by Repo.user(m.from).collectAsState(); Text(u?.name ?: "", color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = if (m.type == "image") Modifier.padding(10.dp, 6.dp, 10.dp, 0.dp) else Modifier) }
                if (m.fwd != null) Row(Modifier.padding(bottom = 4.dp).then(if (m.type == "image") Modifier.padding(10.dp, 6.dp, 10.dp, 0.dp) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Forward, We.primary, 13.dp); Spacer(Modifier.width(5.dp))
                    Text("Forwarded from " + nameOf(m.fwd, "someone"), color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic), maxLines = 1)
                }
                if ((quoted != null || m.rid != null) && !m.deleted) {
                    Row(Modifier.then(if (m.type == "image") Modifier.width(262.dp).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 2.dp) else Modifier.fillMaxWidth().padding(bottom = 6.dp)).clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .12f)).height(IntrinsicSize.Min)
                        .combinedClickable(onClick = { m.rid?.let(onQuote) }, onLongClick = onLong)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(We.primary))
                        Column(Modifier.padding(8.dp, 5.dp)) {
                            Text(if (quoted == null) "Reply" else if (quoted.from == Repo.uid) "You" else nameOf(quoted.from, ""), color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                            Text(if (quoted == null) "Tap to find the original message" else if (quoted.deleted) "Deleted message" else previewText(quoted.type, quoted.text), color = fg.copy(alpha = .75f), style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                when {
                    m.deleted -> Text("This message was deleted", color = fg.copy(alpha = .6f), style = T.body.copy(fontStyle = FontStyle.Italic))
                    m.unreadable -> Text("🔒 Locked with an older encryption key. Tap for details", color = fg.copy(alpha = .6f), style = T.body.copy(fontStyle = FontStyle.Italic))
                    m.type == "image" -> ImageBody(m, ref, mine, read, fg, pinned, onLong)
                    m.type == "voice" -> VoiceBody(m, ref, fg)
                    m.type == "video" || m.type == "audio" || m.type == "file" -> FileBody(m, ref, mine, fg, onLong)
                    m.type == "sticker" -> StickerBody(m.text)
                    m.type == "gif" -> GifBody(m, ref, onLong)
                    m.type == "call" -> CallBody(m, mine, fg)
                    m.type == "link" && m.link != null -> {
                        Text(linkified(m.text, fg), color = fg, style = T.body.copy(fontSize = (15 * scale).sp, lineHeight = (21 * scale).sp))
                        LinkCard(m.link, fg, onLong)
                    }
                    else -> Text(linkified(m.text, fg), color = fg, style = T.body.copy(fontSize = (15 * scale).sp, lineHeight = (21 * scale).sp))
                }
                if (!isSticker) notes.forEach { n ->
                    Column(Modifier.padding(top = 6.dp).then(if (m.type == "image") Modifier.padding(horizontal = 12.dp) else Modifier)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(fg.copy(alpha = .18f)))
                        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (n.loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(11.dp), color = We.primary, strokeWidth = 1.5.dp, trackColor = Color.Transparent) else Icon(Ic.Globe, We.primary, 12.dp)
                            Spacer(Modifier.width(5.dp)); Text(n.label, color = We.primary, style = T.tiny.copy(fontWeight = FontWeight.SemiBold))
                        }
                        if (n.text.isNotEmpty()) Text(n.text, color = fg, style = T.body.copy(fontSize = (15 * scale).sp, lineHeight = (21 * scale).sp), modifier = Modifier.padding(top = 2.dp))
                    }
                }
                if (m.type != "image" && !isSticker) MetaRow(m, mine, read, fg, pinned)
            }
            if (isSticker) MetaRow(m, mine, read, We.text2, pinned)
            if (m.reactions.isNotEmpty()) {
                val groups = m.reactions.values.groupingBy { it }.eachCount()
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.forEach { (e, n) ->
                        val mineR = m.reactions[Repo.uid] == e
                        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (mineR) We.primary.copy(alpha = .22f) else We.surface).combinedClickable(onClick = { onReact(e) }).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReactionEmoji(e, 18.dp, loop = false); if (n > 1) Text(" $n", color = We.text, style = T.tiny)
                        }
                    }
                }
            }
        }
    }
}

/** Telegram style: drag a message to the right and let go to reply to it. */
@Composable
private fun SwipeReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    val d = LocalDensity.current.density
    var dx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val x by animateFloatAsState(dx, if (dragging) snap() else tween(180), label = "swipe")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val trigger = 64 * d
    Box(Modifier.fillMaxWidth().then(if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { dragging = true },
            onDragEnd = { dragging = false; if (dx >= trigger) onReply(); dx = 0f },
            onDragCancel = { dragging = false; dx = 0f },
        ) { change, amount ->
            change.consume()
            val before = dx
            dx = (dx + amount).coerceIn(0f, 84 * d)
            if (before < trigger && dx >= trigger) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    })) {
        val p = (x / trigger).coerceIn(0f, 1f)
        Box(Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(32.dp).graphicsLayer { alpha = p; scaleX = .6f + .4f * p; scaleY = .6f + .4f * p }
            .clip(CircleShape).background(We.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(Ic.Reply, We.primary, 18.dp) }
        Box(Modifier.graphicsLayer { translationX = x }) { content() }
    }
}

@Composable
private fun ColumnScope.MetaRow(m: Msg, mine: Boolean, read: Boolean, fg: Color, pinned: Boolean) {
    Row(Modifier.align(Alignment.End).padding(top = 3.dp, start = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (pinned) Icon(Ic.Pin, fg.copy(alpha = .55f), 11.dp)
        if (m.edited) Text("edited", color = fg.copy(alpha = .55f), style = T.tiny)
        Text(if (m.ts > 0) clock(m.ts) else "", color = fg.copy(alpha = .6f), style = T.tiny.copy(fontSize = 10.5.sp))
        if (m.pending) { val f = Repo.outgoing.firstOrNull { "out_" + it.id == m.id }?.failed == true; Icon(if (f) Ic.Alert else Ic.Clock, if (f) We.danger else fg.copy(alpha = .55f), 14.dp) }
        else if (mine && !m.deleted) Icon(if (read) Ic.Check2 else Ic.Check, if (read) We.primary else fg.copy(alpha = .55f), 15.dp)
    }
}

@Composable
private fun ImageBody(m: Msg, ref: ChatRef, mine: Boolean, read: Boolean, fg: Color, pinned: Boolean, onLong: () -> Unit) {
    var manual by remember(m.id) { mutableStateOf(false) }
    val allowed = m.pending || manual || (m.mediaId?.let { DataPrefs.hasDisk(it) } == true) || remember { DataPrefs.mayAutoDownload() }
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.mediaId, allowed) { if (allowed) m.mediaId?.let { id -> Repo.loadMedia(ref, id)?.let { value = Pics.decode(it) } } }
    val hasCap = m.text.isNotEmpty()
    Column(Modifier.width(262.dp)) {
        Box(Modifier.padding(6.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { m.mediaId?.let { Nav.push(RImage(ref, it)) } }, onLongClick = onLong)) {
            val b = bmp ?: (if (m.pending) Repo.outgoing.firstOrNull { "out_" + it.id == m.id }?.thumb else null)
            if (b == null && !allowed) Box(Modifier.fillMaxWidth().aspectRatio(1.2f).background(We.field).combinedClickable(onClick = { manual = true }, onLongClick = onLong), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Ic.Download, We.primary, 30.dp); Text("Tap to download", color = We.text2, style = T.small) } }
            else if (b == null) Box(Modifier.fillMaxWidth().aspectRatio(1.2f).background(We.field), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = We.primary, strokeWidth = 2.dp) }
            else Image(b, null, Modifier.fillMaxWidth().aspectRatio((b.width.toFloat() / b.height).coerceIn(.6f, 1.8f)), contentScale = ContentScale.Crop)
            if (m.pending) SendingBadge(Modifier.align(Alignment.Center), Repo.outgoing.firstOrNull { "out_" + it.id == m.id }?.failed == true)
            if (!hasCap) Row(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(m.ts), color = Color.White, style = T.tiny.copy(fontSize = 10.5.sp))
                if (mine) { Spacer(Modifier.width(3.dp)); if (m.pending) Icon(Ic.Clock, Color.White, 13.dp) else Icon(if (read) Ic.Check2 else Ic.Check, if (read) Color(0xFF7DD3FC) else Color.White, 14.dp) }
            }
        }
        if (hasCap) {
            Text(linkified(m.text, fg), color = fg, style = T.body.copy(fontSize = (15 * UiPrefs.textScale.floatValue).sp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 5.dp))
            Box(Modifier.padding(end = 12.dp, bottom = 8.dp).align(Alignment.End)) { Column { MetaRow(m, mine, read, fg, pinned) } }
        }
    }
}

@Composable
private fun CallBody(m: Msg, mine: Boolean, fg: Color) {
    val missed = m.mime != "ended" && m.mime != "declined" && !mine
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (missed) We.danger.copy(alpha = .15f) else fg.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(if (m.w == 1) Ic.Video else Ic.Phone, if (missed) We.danger else fg, 20.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(if (m.w == 1) "Video call" else "Voice call", color = if (missed) We.danger else fg, style = T.body.copy(fontWeight = FontWeight.SemiBold))
            Text(when (m.mime) { "ended" -> "%d:%02d".format(m.dur / 60, m.dur % 60); "declined" -> "Declined"; else -> if (mine) "No answer" else "Missed" }, color = fg.copy(alpha = .65f), style = T.small)
        }
    }
}

@Composable
private fun SendingBadge(modifier: Modifier, failed: Boolean) {
    Box(modifier.size(46.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        if (failed) Icon(Ic.Alert, Color.White, 24.dp) else androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun GifBody(m: Msg, ref: ChatRef, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val bytes by produceState<ByteArray?>(null, m.mediaId) { m.mediaId?.let { value = Repo.loadMedia(ref, it) } }
    val ratio = if (m.w > 0 && m.h > 0) (m.w.toFloat() / m.h).coerceIn(.5f, 2f) else 1f
    val drawable = remember(bytes) {
        bytes?.let { b ->
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(b)))
                else android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.let { android.graphics.drawable.BitmapDrawable(ctx.resources, it) }
            } catch (e: Exception) { null }
        }
    }
    val pendThumb = if (m.pending) Repo.outgoing.firstOrNull { "out_" + it.id == m.id }?.thumb else null
    Box(Modifier.width(if (ratio > 1f) 220.dp else 170.dp).aspectRatio(ratio).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        if (drawable == null && pendThumb != null) { Image(pendThumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit); SendingBadge(Modifier, Repo.outgoing.firstOrNull { "out_" + it.id == m.id }?.failed == true) }
        else if (drawable == null) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = We.primary, strokeWidth = 2.dp)
        else androidx.compose.ui.viewinterop.AndroidView(factory = { c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
            update = { iv ->
                iv.setImageDrawable(drawable)
                if (android.os.Build.VERSION.SDK_INT >= 28 && drawable is android.graphics.drawable.AnimatedImageDrawable) { drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; drawable.start() }
            }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().combinedClickable(onClick = { m.mediaId?.let { Nav.push(RImage(ref, it)) } }, onLongClick = onLong))
    }
}

@Composable
private fun LinkCard(l: LinkPreview, fg: Color, onLong: () -> Unit) {
    val thumb = remember(l.thumb) { Pics.decodeB64(l.thumb) }
    val host = remember(l.url) { try { java.net.URI(l.url).host?.removePrefix("www.") ?: l.url } catch (e: Exception) { l.url } }
    Column(Modifier.padding(top = 6.dp).width(250.dp).clip(RoundedCornerShape(10.dp)).background(fg.copy(alpha = .08f))
        .combinedClickable(onClick = { Nav.push(RBrowser(l.url)) }, onLongClick = onLong)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(We.primary))
            Column(Modifier.padding(10.dp, 8.dp)) {
                Text(host, color = We.primary, style = T.tiny.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                if (l.title.isNotEmpty()) Text(l.title, color = fg, style = T.small.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (l.desc.isNotEmpty()) Text(l.desc, color = fg.copy(alpha = .8f), style = T.small, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        thumb?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio((it.width.toFloat() / it.height).coerceIn(1f, 2f)), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun StickerBody(payload: String) {
    val ctx = LocalContext.current
    when {
        payload.startsWith("a:") -> AnimEmoji(payload.removePrefix("a:"), 150.dp)
        else -> { val bmp = remember(payload) { Pics.asset(ctx, payload.removePrefix("s:")) }; if (bmp != null) Image(bmp, null, Modifier.size(150.dp)) else Text("Sticker", color = We.text2) }
    }
}

// ---------------------------------------------------------------------------------------------------- long-press menu
@Composable
private fun MessageMenu(m: Msg, ref: ChatRef, canReply: Boolean, canPin: Boolean, pinned: Boolean, mine: Boolean, onDismiss: () -> Unit, onReact: (String) -> Unit,
                        speakText: String, onSaveFile: (() -> Unit)?, onReply: () -> Unit, onCopy: () -> Unit, onSpeak: () -> Unit, onPin: () -> Unit, onForward: () -> Unit, onEdit: () -> Unit, onSelect: () -> Unit, onSave: () -> Unit, onTranslate: (() -> Unit)?, onTranslateTo: (() -> Unit)?, onTranscribe: (() -> Unit)?, onHideNotes: (() -> Unit)?, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) }, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 340.dp).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }, horizontalAlignment = Alignment.Start) {
                if (ref.kind != Kind.CHANNEL || true) Row(Modifier.clip(RoundedCornerShape(30.dp)).background(We.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Anim.reactions.forEach { e ->
                        Box(Modifier.size(46.dp).clip(CircleShape).background(if (m.reactions[Repo.uid] == e) We.primary.copy(alpha = .18f) else Color.Transparent).combinedClickable(onClick = { onReact(e) }), contentAlignment = Alignment.Center) { ReactionEmoji(e, 38.dp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(We.surface).width(250.dp).padding(vertical = 6.dp)) {
                    if (canReply) MenuRow(Ic.Reply, "Reply", onClick = onReply)
                    if ((m.type == "text" || m.type == "link" || m.type == "image") && m.text.isNotEmpty()) MenuRow(Ic.Copy, if (m.type == "image") "Copy caption" else "Copy text", onClick = onCopy)
                    if (speakText.isNotEmpty()) MenuRow(Ic.Speaker, if (com.kafisir.wetalks.voice.Speaker.speakingId == m.id) "Stop listening" else "Listen", onClick = onSpeak)
                    onSaveFile?.let { MenuRow(Ic.Download, "Save to Downloads", onClick = it) }
                    if (canPin) MenuRow(Ic.Pin, if (pinned) "Unpin" else "Pin", onClick = onPin)
                    if (m.type == "image" || m.type == "gif") MenuRow(Ic.Download, "Save to gallery", onClick = onSave)
                    MenuRow(Ic.Forward, "Forward", onClick = onForward)
                    if (mine && (m.type == "text" || m.type == "image")) MenuRow(Ic.Edit, if (m.type == "image") (if (m.text.isEmpty()) "Add caption" else "Edit caption") else "Edit", onClick = onEdit)
                    onTranscribe?.let { MenuRow(Ic.Audio, "Transcribe", onClick = it) }
                    onTranslate?.let { MenuRow(Ic.Globe, "Translate", onClick = it) }
                    onTranslateTo?.let { MenuRow(Ic.Globe, "Translate to…", onClick = it) }
                    onHideNotes?.let { MenuRow(Ic.Close, "Hide translation / transcript", onClick = it) }
                    MenuRow(Ic.Select, "Select", onClick = onSelect)
                    MenuRow(Ic.Trash, "Delete", danger = true, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(ic: androidx.compose.ui.graphics.vector.ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, if (danger) We.danger else We.text2, 22.dp); Spacer(Modifier.width(16.dp))
        Text(text, color = if (danger) We.danger else We.text, style = T.body.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ForwardSheet(count: Int, onDismiss: () -> Unit, onSend: (List<ChatRef>) -> Unit) {
    val chats by remember { Repo.chats() }.collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<ChatRef>() }
    val targets = remember(chats) { listOf(Repo.dmRef(Repo.uid)) + chats.map { it.ref }.filter { !(it.kind == Kind.DM && it.peer == Repo.uid) && it.kind != Kind.CHANNEL } }
    Sheet(onDismiss) {
        Text(if (count == 1) "Forward message" else "Forward $count messages", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.field).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, We.text2, 18.dp); Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search chats", color = We.text2, style = T.body)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = T.body.copy(color = We.text), cursorBrush = SolidColor(We.primary))
            }
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            targets.forEach { t ->
                val saved = t.kind == Kind.DM && t.peer == Repo.uid
                val (n0, p, s) = titleOf(t)
                val n = if (saved) "Saved Messages" else n0
                if (q.isBlank() || n.contains(q, true)) {
                    val on = t in picked
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (on) picked.remove(t) else picked.add(t) }).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (saved) Box(Modifier.size(44.dp).clip(CircleShape).background(We.primary), contentAlignment = Alignment.Center) { Icon(Ic.Bookmark, We.onPrimary, 22.dp) } else Avatar(n, p, 44.dp, s)
                        Spacer(Modifier.width(14.dp)); Text(n, color = We.text, style = T.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) We.primary else We.line), contentAlignment = Alignment.Center) { if (on) Icon(Ic.Check, We.onPrimary, 15.dp) }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { PrimaryButton(if (picked.isEmpty()) "Select chats" else "Forward to ${picked.size} chat${if (picked.size > 1) "s" else ""}", { onSend(picked.toList()) }, enabled = picked.isNotEmpty()) }
    }
}

// ---------------------------------------------------------------------------------------------------- stickers
@Composable
fun StickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    // tabs: animated packs first, then the illustrated ones
    val tabs: List<Triple<String, Boolean, List<String>>> =
        Anim.packs.map { Triple(it.name, it.locked, it.codes.map { c -> "a:$c" }) } +
        listOf(Triple("Moods", false, StickerPacks.moods.map { "s:$it" }), Triple("Reactions", false, StickerPacks.reactions.map { "s:$it" }), Triple("Words", false, StickerPacks.words.map { "s:$it" }))
    val (_, locked, items) = tabs[tab]
    Sheet(onDismiss) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { i, (n, lk, _) ->
                Row(Modifier.clip(RoundedCornerShape(18.dp)).background(if (tab == i) We.primary else We.field).combinedClickable(onClick = { tab = i }).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (lk) { Icon(Ic.Lock, if (tab == i) We.onPrimary else We.text2, 13.dp); Spacer(Modifier.width(5.dp)) }
                    Text(n, color = if (tab == i) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
                }
            }
        }
        if (locked) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.primary.copy(alpha = .1f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Lock, We.primary, 18.dp); Spacer(Modifier.width(10.dp))
            Text("This pack is locked. WeTalks Plus is coming soon and will unlock extra animated packs.", color = We.text, style = T.small)
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            items.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { p ->
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(5.dp).combinedClickable(onClick = { if (locked) Nav.say("Locked · coming soon with WeTalks Plus") else onPick(p) }), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize().then(if (locked) Modifier.scale(.92f) else Modifier), contentAlignment = Alignment.Center) {
                                if (p.startsWith("a:")) AnimEmoji(p.removePrefix("a:"), 64.dp, loop = !locked)
                                else { val bmp = remember(p) { Pics.asset(ctx, p.removePrefix("s:")) }; if (bmp != null) Image(bmp, null, Modifier.fillMaxSize()) }
                            }
                            if (locked) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(We.surface.copy(alpha = .55f)), contentAlignment = Alignment.BottomEnd) {
                                Box(Modifier.padding(4.dp).size(22.dp).clip(CircleShape).background(We.text.copy(alpha = .7f)), contentAlignment = Alignment.Center) { Icon(Ic.Lock, We.surface, 12.dp) }
                            }
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
fun ImageViewer(ref: ChatRef, mediaId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bytes by produceState<ByteArray?>(null, mediaId) { value = Repo.loadMedia(ref, mediaId) }
    val bmp = remember(bytes) { bytes?.let { Pics.decode(it) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val animScale by animateFloatAsState(scale, tween(160), label = "z")
    var bars by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    LaunchedEffect(bars, touched) { if (bars) { delay(4000); bars = false } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bmp == null) androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        else Image(bmp, null, Modifier.fillMaxSize().systemBarsPadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { bars = !bars; touched++ }, onDoubleTap = { if (scale > 1.05f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.6f })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (bars && (zoom != 1f)) touched++
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    val maxX = size.width * (scale - 1) / 2; val maxY = size.height * (scale - 1) / 2
                    offset = if (scale <= 1.01f) androidx.compose.ui.geometry.Offset.Zero else androidx.compose.ui.geometry.Offset((offset.x + pan.x * scale).coerceIn(-maxX, maxX), (offset.y + pan.y * scale).coerceIn(-maxY, maxY))
                }
            }.graphicsLayer { scaleX = animScale; scaleY = animScale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
        androidx.compose.animation.AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp).background(Color(0x66000000), RoundedCornerShape(30.dp)), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, Color.White, { Nav.pop() })
            Spacer(Modifier.weight(1f))
            if (bytes != null) {
                IconBtn(Ic.Brush, Color.White, {
                    scope.launch {
                        val f = java.io.File(ctx.cacheDir, "edit_${System.nanoTime()}.jpg"); f.writeBytes(bytes!!)
                        Nav.pendingPhotos = listOf(Uri.fromFile(f)); Nav.pendingRef = ref; Nav.pendingReply = null
                        Nav.pop(); Nav.push(RPhotoEdit)
                    }
                })
                IconBtn(Ic.Download, Color.White, { Nav.say(if (Gallery.save(ctx, bytes!!)) "Saved to gallery" else "Could not save") })
                IconBtn(Ic.Share, Color.White, {
                    val f = java.io.File(ctx.cacheDir, "share_${System.nanoTime()}.jpg"); f.writeBytes(bytes!!)
                    try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) } catch (e: Exception) { Nav.say("Could not share") }
                })
            }
        }
        }
    }
}
