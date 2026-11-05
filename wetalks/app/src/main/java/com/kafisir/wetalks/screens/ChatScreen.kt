@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun clock(ts: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
private fun dayLabel(ts: Long): String {
    val f = SimpleDateFormat("yyyyMMdd", Locale.US)
    val now = System.currentTimeMillis()
    return when {
        f.format(Date(ts)) == f.format(Date(now)) -> "Today"
        f.format(Date(ts)) == f.format(Date(now - 86400000L)) -> "Yesterday"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ts))
    }
}

@Composable
fun ChatScreen(ref: ChatRef) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = Repo.uid
    val (title, photo, seed) = titleOf(ref)
    val blocked by Repo.blockedUsers.collectAsState()
    var limit by remember { mutableIntStateOf(50) }
    val msgsRaw by produceState<List<Msg>>(emptyList(), ref, limit) {
        Repo.rawMessages(ref, limit).collect { raws -> value = raws.map { Repo.decrypt(ref, it) } }
    }
    var hideTick by remember { mutableIntStateOf(0) }
    val msgs = msgsRaw.filter { hideTick >= 0 && !Repo.isHidden(it.id) && it.from !in blocked }
    val typing by remember(ref) { Repo.typing(ref) }.collectAsState(emptyList())
    val peerUser = if (ref.kind == Kind.DM) Repo.user(ref.peer!!).collectAsState().value else null
    val group = if (ref.kind == Kind.GROUP) Repo.group(ref.id).collectAsState().value else null
    val channel = if (ref.kind == Kind.CHANNEL) Repo.channel(ref.id).collectAsState().value else null
    val peerRead by (if (ref.kind == Kind.DM) remember(ref) { Repo.readTs(ref, ref.peer!!) } else remember { kotlinx.coroutines.flow.flowOf(0L) }).collectAsState(0L)
    val subscribed by produceState(true, ref, channel?.id) { if (ref.kind == Kind.CHANNEL) value = Repo.isSubscribed(ref.id) }
    var joined by remember(ref) { mutableStateOf<Boolean?>(null) }
    val isMember = joined ?: subscribed
    val canPost = when (ref.kind) { Kind.CHANNEL -> channel != null && (channel.owner == me || me in channel.admins); else -> true }

    var reply by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var input by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<Msg?>(null) }
    var attach by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(msgs.size, ref) { Repo.markRead(ref) }
    LaunchedEffect(input) {
        if (ref.kind != Kind.CHANNEL) { if (input.isNotEmpty()) { Repo.setTyping(ref, true); delay(3000) }; Repo.setTyping(ref, false) }
    }
    DisposableEffect(ref) { onDispose { Repo.setTyping(ref, false) } }

    val subtitle: String? = when {
        typing.isNotEmpty() -> if (ref.kind == Kind.DM) "typing…" else "someone is typing…"
        ref.kind == Kind.DM -> when {
            peerUser == null -> null
            peerUser.online && peerUser.showLastSeen -> "online"
            peerUser.showLastSeen && peerUser.lastSeen > 0 -> "last seen " + timeShort(peerUser.lastSeen)
            else -> "encrypted chat"
        }
        ref.kind == Kind.GROUP -> group?.let { "${it.members.size} members" }
        else -> channel?.let { "${it.subCount} subscribers" }
    }

    fun toast(m: String) = Nav.say(m)
    fun sendText() {
        val t = input.trim(); if (t.isEmpty()) return
        val e = editing; val r = reply
        input = ""; editing = null; reply = null
        scope.launch {
            try { if (e != null) Repo.editMessage(ref, e.id, t) else Repo.send(ref, "text", t, r?.id, t.take(90)) }
            catch (ex: Exception) { toast(ex.message ?: "Could not send"); input = t }
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u: Uri? ->
        if (u != null) scope.launch {
            sending = true
            try {
                val r = Pics.jpegFrom(ctx, u, 1280, 72) ?: throw IllegalStateException("Could not read image")
                val id = Repo.uploadMedia(ref, r.first, "image/jpeg")
                Repo.send(ref, "image", JSONObject().put("m", id).put("w", r.second).put("h", r.third).toString(), reply?.id, "Photo"); reply = null
            } catch (e: Exception) { toast(e.message ?: "Photo failed") }
            sending = false
        }
    }

    Column(Modifier.fillMaxSize().background(We.bg).imePadding()) {
        TopBar(title, onBack = { Nav.pop() }, subtitle = subtitle,
            leading = { Avatar(title, photo, 38.dp, seed) },
            titleClick = { when (ref.kind) { Kind.DM -> Nav.push(RProfile(ref.peer!!)); Kind.GROUP -> Nav.push(RGroupInfo(ref.id)); Kind.CHANNEL -> Nav.push(RChannelInfo(ref.id)) } },
            actions = { IconBtn(Ic.Dots, We.onAppBar, { menu = true }) })
        if (sending) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = We.primary, trackColor = Color.Transparent)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(We.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) { Icon(Ic.Lock, We.primary, 28.dp) }
                Spacer(Modifier.height(12.dp))
                Text(if (ref.kind == Kind.CHANNEL) "No posts yet" else "No messages yet", color = We.text, style = T.name)
                Text(if (ref.kind == Kind.CHANNEL) "Posts from admins show up here." else "Messages are end-to-end encrypted. Only the people in this chat can read them.", color = We.text2, style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            val rev = msgs.reversed()
            LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
                itemsIndexed(rev, key = { _, m -> m.id }) { i, m ->
                    val older = rev.getOrNull(i + 1)
                    val newer = rev.getOrNull(i - 1)
                    if (i == rev.size - 1 && msgs.size >= limit) LaunchedEffect(limit) { limit += 40 }
                    Column {
                        if (older == null || dayLabel(older.ts) != dayLabel(m.ts)) DayChip(dayLabel(m.ts))
                        val mine = m.from == me
                        val sameAsOlder = older != null && older.from == m.from && dayLabel(older.ts) == dayLabel(m.ts)
                        Bubble(m, mine, ref, rev.firstOrNull { it.id == m.rid }, showName = ref.kind == Kind.GROUP && !mine && !sameAsOlder,
                            read = ref.kind == Kind.DM && peerRead >= m.ts && m.ts > 0, lastInRun = newer == null || newer.from != m.from,
                            onLong = { if (!m.deleted) action = m }, onReplyJump = { /* quote tap */ })
                        Spacer(Modifier.height(if (newer != null && newer.from == m.from) 2.dp else 8.dp))
                    }
                }
            }
        }
        // ------- input
        if (ref.kind == Kind.CHANNEL && !canPost) {
            Column(Modifier.background(We.surface)) {
                Divider()
                if (isMember) Text("Only admins can post in this channel", color = We.text2, style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
                else Box(Modifier.navigationBarsPadding().padding(12.dp)) { PrimaryButton("Join channel", { scope.launch { try { Repo.subscribe(ref.id); joined = true } catch (e: Exception) { toast("Could not join") } } }) }
            }
        } else Column(Modifier.background(We.surface)) {
            Divider()
            if (reply != null || editing != null) {
                val q = editing ?: reply!!
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(34.dp).clip(CircleShape).background(We.primary)); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editing != null) "Edit message" else "Reply to " + (if (q.from == me) "yourself" else nameOf(q.from, title)), color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                        Text(previewText(q.type, q.text), color = We.text2, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(Ic.Close, We.text2, { reply = null; if (editing != null) { editing = null; input = "" } }, 20.dp)
                }
            }
            var rec by remember { mutableStateOf<Recorder?>(null) }
            var secs by remember { mutableIntStateOf(0) }
            val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { rec = Recorder(ctx).also { it.start() }; secs = 0 } else toast("Microphone permission is needed for voice messages") }
            LaunchedEffect(rec) { while (rec != null) { delay(1000); secs++; if (secs >= 120) { /* auto stop handled by send */ } } }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                if (rec != null) {
                    IconBtn(Ic.Trash, We.danger, { rec?.cancel(); rec = null })
                    Row(Modifier.weight(1f).height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(We.danger)); Spacer(Modifier.width(10.dp))
                        Text("Recording  %d:%02d".format(secs / 60, secs % 60), color = We.text, style = T.body)
                    }
                    Box(Modifier.size(46.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = {
                        val r = rec; rec = null
                        val dur = secs
                        scope.launch {
                            sending = true
                            try {
                                val bytes = r?.finish() ?: throw IllegalStateException("Recording failed")
                                if (dur < 1) throw IllegalStateException("Hold on a little longer")
                                val id = Repo.uploadMedia(ref, bytes, "audio/mp4")
                                Repo.send(ref, "voice", JSONObject().put("m", id).put("d", dur).toString(), reply?.id, "Voice message"); reply = null
                            } catch (e: Exception) { toast(e.message ?: "Voice failed") }
                            sending = false
                        }
                    }), contentAlignment = Alignment.Center) { Icon(Ic.Send, We.onPrimary, 22.dp) }
                } else {
                    IconBtn(Ic.Plus, We.text2, { attach = true })
                    Row(Modifier.weight(1f).heightIn(min = 46.dp).padding(vertical = 2.dp).clip(RoundedCornerShape(23.dp)).background(We.field).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                            if (input.isEmpty()) Text("Message", color = We.text2, style = T.body)
                            BasicTextField(input, { input = it }, Modifier.fillMaxWidth(), textStyle = T.body.copy(color = We.text), cursorBrush = SolidColor(We.primary), maxLines = 5,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                        }
                        IconBtn(Ic.Smile, We.text2, { stickers = true }, 22.dp, Modifier.size(40.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(46.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = {
                        if (input.isNotBlank()) sendText()
                        else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { rec = Recorder(ctx).also { it.start() }; secs = 0 }
                        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
                    }), contentAlignment = Alignment.Center) { Icon(if (input.isNotBlank()) Ic.Send else Ic.Mic, We.onPrimary, 22.dp) }
                }
            }
        }
    }

    // ------- sheets
    if (attach) Sheet({ attach = false }) {
        SheetItem(Ic.Image, "Photo") { attach = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        SheetItem(Ic.Smile, "Sticker") { attach = false; stickers = true }
    }
    if (stickers) StickerSheet({ stickers = false }) { name ->
        stickers = false
        scope.launch { try { Repo.send(ref, "sticker", "s:$name", reply?.id, "Sticker"); reply = null } catch (e: Exception) { toast(e.message ?: "Could not send") } }
    }
    action?.let { m ->
        Sheet({ action = null }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { e ->
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (m.reactions[me] == e) We.primary.copy(alpha = .15f) else Color.Transparent)
                        .combinedClickable(onClick = { Repo.react(ref, m.id, if (m.reactions[me] == e) null else e); action = null }), contentAlignment = Alignment.Center) { Text(e, fontSize = 26.sp) }
                }
            }
            Divider()
            if (canPost) SheetItem(Ic.Reply, "Reply") { reply = m; editing = null; action = null }
            if (m.text.isNotEmpty() && m.type == "text") SheetItem(Ic.Copy, "Copy") {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("message", m.text)); action = null; toast("Copied")
            }
            if (m.from == me && m.type == "text") SheetItem(Ic.Edit, "Edit") { editing = m; reply = null; input = m.text; action = null }
            SheetItem(Ic.Trash2, "Delete for me") { Repo.hideForMe(m.id); hideTick++; action = null }
            if (m.from == me || (ref.kind == Kind.GROUP && group?.members?.get(me) == "admin")) SheetItem(Ic.Trash, "Delete for everyone", danger = true) { Repo.deleteForAll(ref, m.id); action = null }
        }
    }
    if (menu) Sheet({ menu = false }) {
        when (ref.kind) {
            Kind.DM -> {
                SheetItem(Ic.User, "View profile") { menu = false; Nav.push(RProfile(ref.peer!!)) }
                SheetItem(Ic.Block, if (ref.peer in blocked) "Unblock" else "Block", danger = ref.peer !in blocked) { Repo.block(ref.peer!!, ref.peer !in blocked); menu = false }
            }
            Kind.GROUP -> SheetItem(Ic.Group, "Group info") { menu = false; Nav.push(RGroupInfo(ref.id)) }
            Kind.CHANNEL -> {
                SheetItem(Ic.Info, "Channel info") { menu = false; Nav.push(RChannelInfo(ref.id)) }
                if (isMember && channel?.owner != me) SheetItem(Ic.Logout, "Leave channel", danger = true) { menu = false; scope.launch { Repo.unsubscribe(ref.id); Nav.pop() } }
            }
        }
    }
}

@Composable private fun nameOf(uid: String, fallback: String): String { val u by Repo.user(uid).collectAsState(); return u?.name ?: fallback }

@Composable
fun DayChip(t: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(t, color = We.text2, style = T.tiny, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(We.field).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
fun Bubble(m: Msg, mine: Boolean, ref: ChatRef, quoted: Msg?, showName: Boolean, read: Boolean, lastInRun: Boolean, onLong: () -> Unit, onReplyJump: () -> Unit) {
    val shape = RoundedCornerShape(16.dp, 16.dp, if (mine && lastInRun) 4.dp else 16.dp, if (!mine && lastInRun) 4.dp else 16.dp)
    val fg = if (mine) We.onSent else We.onRecv
    val isSticker = m.type == "sticker" && !m.deleted
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.then(if (isSticker) Modifier else Modifier.clip(shape).background(if (mine) We.sent else We.recv))
                .combinedClickable(onClick = onLong, onLongClick = onLong).then(if (isSticker || m.type == "image") Modifier else Modifier.padding(horizontal = 12.dp, vertical = 8.dp))) {
                if (showName) { val u by Repo.user(m.from).collectAsState(); Text(u?.name ?: "", color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = if (m.type == "image") Modifier.padding(10.dp, 6.dp, 10.dp, 0.dp) else Modifier) }
                if (quoted != null && !m.deleted) {
                    Row(Modifier.padding(bottom = 6.dp).then(if (m.type == "image") Modifier.padding(8.dp) else Modifier).clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .12f)).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(We.primary))
                        Column(Modifier.padding(8.dp, 5.dp)) {
                            Text(if (quoted.from == Repo.uid) "You" else nameOf(quoted.from, ""), color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                            Text(if (quoted.deleted) "Deleted message" else previewText(quoted.type, quoted.text), color = fg.copy(alpha = .75f), style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                when {
                    m.deleted -> Text("This message was deleted", color = fg.copy(alpha = .6f), style = T.body.copy(fontStyle = FontStyle.Italic))
                    m.unreadable -> Text("Can't decrypt this message on this device", color = fg.copy(alpha = .6f), style = T.body.copy(fontStyle = FontStyle.Italic))
                    m.type == "image" -> ImageBody(m, ref, mine, read)
                    m.type == "voice" -> VoiceBody(m, ref, fg)
                    m.type == "sticker" -> StickerBody(m.text)
                    else -> Text(m.text, color = fg, style = T.body)
                }
                if (m.type != "image" && !isSticker) MetaRow(m, mine, read, fg)
            }
            if (isSticker) MetaRow(m, mine, read, We.text2)
            if (m.reactions.isNotEmpty()) {
                val groups = m.reactions.values.groupingBy { it }.eachCount()
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.forEach { (e, n) ->
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(We.surface).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(e, fontSize = 13.sp); if (n > 1) Text(" $n", color = We.text2, style = T.tiny)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(m: Msg, mine: Boolean, read: Boolean, fg: Color, overImage: Boolean = false) {
    Row(Modifier.padding(top = if (overImage) 0.dp else 2.dp).align2(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (m.edited) Text("edited", color = fg.copy(alpha = .55f), style = T.tiny)
        Text(if (m.ts > 0) clock(m.ts) else "", color = fg.copy(alpha = .6f), style = T.tiny.copy(fontSize = 10.5.sp))
        if (mine && !m.deleted) Icon(if (read) Ic.Check2 else Ic.Check, if (read) We.primary else fg.copy(alpha = .55f), 15.dp)
    }
}

private fun Modifier.align2(): Modifier = this.then(Modifier.wrapContentWidth(Alignment.End))

@Composable
private fun ImageBody(m: Msg, ref: ChatRef, mine: Boolean, read: Boolean) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.mediaId) { m.mediaId?.let { id -> Repo.loadMedia(ref, id)?.let { value = Pics.decode(it) } } }
    Box(Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { m.mediaId?.let { Nav.push(RImage(ref, it)) } })) {
        val b = bmp
        if (b == null) Box(Modifier.size(200.dp, 150.dp).background(We.field), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = We.primary, strokeWidth = 2.dp) }
        else Image(b, null, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).heightIn(max = 340.dp), contentScale = ContentScale.Crop)
        Row(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(m.ts), color = Color.White, style = T.tiny.copy(fontSize = 10.5.sp))
            if (mine) { Spacer(Modifier.width(3.dp)); Icon(if (read) Ic.Check2 else Ic.Check, if (read) Color(0xFF7DD3FC) else Color.White, 14.dp) }
        }
    }
}

@Composable
private fun StickerBody(id: String) {
    val ctx = LocalContext.current
    val bmp = remember(id) { Pics.asset(ctx, id.removePrefix("s:")) }
    if (bmp != null) Image(bmp, null, Modifier.size(150.dp)) else Text("Sticker", color = We.text2)
}

@Composable
private fun VoiceBody(m: Msg, ref: ChatRef, fg: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(m.dur * 1000) }
    var loading by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    LaunchedEffect(playing) { while (playing) { pos = player?.currentPosition ?: 0; delay(150) } }
    fun toggle() {
        val p = player
        if (p != null) { if (playing) { p.pause(); playing = false } else { p.start(); playing = true }; return }
        scope.launch {
            loading = true
            val bytes = m.mediaId?.let { Repo.loadMedia(ref, it) }
            loading = false
            if (bytes == null) { Nav.say("Could not load voice message"); return@launch }
            val f = File(ctx.cacheDir, "v_${m.id}.m4a"); f.writeBytes(bytes)
            val mp = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); setOnCompletionListener { playing = false; pos = 0; seekTo(0) } }
            total = mp.duration; player = mp; mp.start(); playing = true
        }
    }
    Row(Modifier.width(210.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { toggle() }), contentAlignment = Alignment.Center) {
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = We.onPrimary, strokeWidth = 2.dp) else Icon(if (playing) Ic.Pause else Ic.Play, We.onPrimary, 20.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(fg.copy(alpha = .2f))) {
                Box(Modifier.fillMaxWidth(if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(We.primary))
            }
            val shown = if (playing || pos > 0) pos / 1000 else m.dur
            Text("%d:%02d".format(shown / 60, shown % 60), color = fg.copy(alpha = .65f), style = T.tiny, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Minimal AAC voice recorder (mono, 32 kbps - small enough to store encrypted in the database). */
class Recorder(private val ctx: Context) {
    private val file = File(ctx.cacheDir, "rec_${System.nanoTime()}.m4a")
    private var rec: MediaRecorder? = null
    @Suppress("DEPRECATION")
    fun start() {
        rec = (if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1); setAudioSamplingRate(16000); setAudioEncodingBitRate(32000); setMaxDuration(120_000)
            setOutputFile(file.absolutePath); prepare(); start()
        }
    }
    fun finish(): ByteArray? = try { rec?.stop(); rec?.release(); rec = null; file.readBytes().also { file.delete() } } catch (e: Exception) { rec?.release(); rec = null; file.delete(); null }
    fun cancel() { try { rec?.stop() } catch (_: Exception) {}; rec?.release(); rec = null; file.delete() }
}

@Composable
fun StickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val packs = listOf("Moods" to StickerPacks.moods, "Reactions" to StickerPacks.reactions, "Words" to StickerPacks.words)
    Sheet(onDismiss) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            packs.forEachIndexed { i, (n, _) ->
                Text(n, color = if (tab == i) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (tab == i) We.primary else We.field).combinedClickable(onClick = { tab = i }).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        val items = packs[tab].second
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).heightIn(max = 340.dp).verticalScrollSimple()) {
            items.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { n ->
                        val bmp = remember(n) { Pics.asset(ctx, n) }
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(4.dp).combinedClickable(onClick = { onPick(n) }), contentAlignment = Alignment.Center) { if (bmp != null) Image(bmp, null, Modifier.fillMaxSize()) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollSimple(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
fun ImageViewer(ref: ChatRef, mediaId: String) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, mediaId) { Repo.loadMedia(ref, mediaId)?.let { value = Pics.decode(it) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize().systemBarsPadding(), contentScale = ContentScale.Fit) }
            ?: androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        IconBtn(Ic.Close, Color.White, { Nav.pop() }, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart))
    }
}
