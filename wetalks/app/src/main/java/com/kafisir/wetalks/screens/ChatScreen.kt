@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
private fun isMedia(m: Msg) = m.type == "image" || m.type == "sticker" || m.type == "voice"

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
    val msgs = msgsRaw.filter { hideTick >= 0 && !Repo.isHidden(it.id) && it.from !in blocked }
    val typing by remember(ref) { Repo.typing(ref) }.collectAsState(emptyList())
    val pinIds by remember(ref) { Repo.pins(ref) }.collectAsState(emptyList())
    val peerUser = if (ref.kind == Kind.DM && !saved) Repo.user(ref.peer!!).collectAsState().value else null
    val group = if (ref.kind == Kind.GROUP) Repo.group(ref.id).collectAsState().value else null
    val channel = if (ref.kind == Kind.CHANNEL) Repo.channel(ref.id).collectAsState().value else null
    val peerRead by (if (ref.kind == Kind.DM && !saved) remember(ref) { Repo.readTs(ref, ref.peer!!) } else remember { kotlinx.coroutines.flow.flowOf(0L) }).collectAsState(0L)
    val subscribed by produceState(true, ref, channel?.id) { if (ref.kind == Kind.CHANNEL) value = Repo.isSubscribed(ref.id) }
    var joined by remember(ref) { mutableStateOf<Boolean?>(null) }
    val isMember = joined ?: subscribed
    val canPost = when (ref.kind) { Kind.CHANNEL -> channel != null && (channel.owner == me || me in channel.admins); else -> true }
    val canPin = when (ref.kind) { Kind.DM -> true; Kind.GROUP -> group?.members?.get(me) == "admin"; Kind.CHANNEL -> canPost }

    var reply by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<Msg?>(null) }
    var input by remember { mutableStateOf("") }
    var menuMsg by remember { mutableStateOf<Msg?>(null) }
    var attach by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<List<Msg>?>(null) }
    var forwarding by remember { mutableStateOf<List<Msg>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    var pinCursor by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    fun toast(m: String) = Nav.say(m)

    LaunchedEffect(msgs.size, ref) { Repo.markRead(ref) }
    LaunchedEffect(input) { if (ref.kind != Kind.CHANNEL && !saved) { if (input.isNotEmpty()) { Repo.setTyping(ref, true); delay(3000) }; Repo.setTyping(ref, false) } }
    DisposableEffect(ref) { onDispose { Repo.setTyping(ref, false) } }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.clear() }

    val subtitle: String? = when {
        selecting -> null
        saved -> "Notes to yourself · encrypted"
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

    fun sendText() {
        val t = input.trim(); if (t.isEmpty()) return
        val e = editing; val r = reply
        input = ""; editing = null; reply = null
        scope.launch { try { if (e != null) Repo.editMessage(ref, e.id, t) else Repo.send(ref, "text", t, r?.id, t.take(90)) } catch (ex: Exception) { toast(ex.message ?: "Could not send"); input = t } }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { us: List<Uri> ->
        if (us.isNotEmpty()) { Nav.pendingPhotos = us; Nav.pendingRef = ref; Nav.pendingReply = reply?.id; reply = null; Nav.push(RPhotoEdit) }
    }

    // ---------------- voice recording (hold to record, slide left to cancel, slide up to lock)
    var rec by remember { mutableStateOf<Recorder?>(null) }
    var locked by remember { mutableStateOf(false) }
    var willCancel by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var secs by remember { mutableIntStateOf(0) }
    LaunchedEffect(rec) { while (rec != null) { val r = rec!!; level = r.level(); secs = ((System.currentTimeMillis() - r.startedAt) / 1000).toInt(); delay(90) } }
    fun startRec() { rec = Recorder(ctx).also { try { it.start() } catch (e: Exception) { rec = null; toast("Could not start recording") } }; locked = false; willCancel = false }
    fun endRec(send: Boolean) {
        val r = rec ?: return; rec = null; locked = false
        if (!send) { r.cancel(); return }
        val replyId = reply?.id; reply = null
        scope.launch {
            sending = true
            try {
                val res = r.finish()
                if (res == null || res.seconds < 1) { toast("Hold the mic and release to send"); sending = false; return@launch }
                val id = Repo.uploadMedia(ref, res.bytes, "audio/mp4")
                Repo.send(ref, "voice", JSONObject().put("m", id).put("d", res.seconds).put("w", JSONArray(res.wave)).toString(), replyId, "Voice message")
            } catch (e: Exception) { toast(e.message ?: "Voice failed") }
            sending = false
        }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) toast("Microphone permission is needed for voice messages") }

    val wall = UiPrefs.wallpaper.intValue
    val bgBrush = when (wall) { 1 -> Brush.verticalGradient(listOf(We.primary.copy(alpha = .10f), We.bg)); 2 -> Brush.verticalGradient(listOf(We.primary.copy(alpha = .22f), We.primary.copy(alpha = .06f))); else -> SolidColor(We.bg) }

    Column(Modifier.fillMaxSize().background(bgBrush).imePadding()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().background(We.appBar).statusBarsPadding().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Close, We.onAppBar, { selected.clear() })
                Text("${selected.size} selected", color = We.onAppBar, style = T.title, modifier = Modifier.weight(1f))
                IconBtn(Ic.Copy, We.onAppBar, {
                    val t = msgs.filter { it.id in selected && it.type == "text" }.joinToString("\n") { it.text }
                    if (t.isNotEmpty()) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("messages", t)); toast("Copied") }
                    selected.clear()
                })
                IconBtn(Ic.Forward, We.onAppBar, { forwarding = msgs.filter { it.id in selected } })
                IconBtn(Ic.Trash, We.onAppBar, { deleting = msgs.filter { it.id in selected } })
            }
        } else TopBar(title, onBack = { Nav.pop() }, subtitle = subtitle,
            leading = { if (saved) Box(Modifier.size(38.dp).clip(CircleShape).background(We.onAppBar.copy(alpha = .2f)), contentAlignment = Alignment.Center) { Icon(Ic.Bookmark, We.onAppBar, 20.dp) } else Avatar(title, photo, 38.dp, seed) },
            titleClick = { if (!saved) when (ref.kind) { Kind.DM -> Nav.push(RProfile(ref.peer!!)); Kind.GROUP -> Nav.push(RGroupInfo(ref.id)); Kind.CHANNEL -> Nav.push(RChannelInfo(ref.id)) } },
            actions = { IconBtn(Ic.Dots, We.onAppBar, { menu = true }) })
        if (sending) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = We.primary, trackColor = Color.Transparent)

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
                        Row(Modifier.fillMaxWidth().background(if (sel) We.primary.copy(alpha = .14f) else Color.Transparent).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) Box(Modifier.padding(start = 4.dp, end = 6.dp).size(22.dp).clip(CircleShape).background(if (sel) We.primary else We.line), contentAlignment = Alignment.Center) { if (sel) Icon(Ic.Check, We.onPrimary, 14.dp) }
                            Box(Modifier.weight(1f)) {
                                Bubble(m, mine, ref, rev.firstOrNull { it.id == m.rid }, showName = ref.kind == Kind.GROUP && !mine && !sameAsOlder,
                                    read = ref.kind == Kind.DM && !saved && peerRead >= m.ts && m.ts > 0, lastInRun = newer == null || newer.from != m.from, pinned = m.id in pinIds,
                                    onClick = { if (selecting) { if (sel) selected.remove(m.id) else selected.add(m.id) } },
                                    onLong = { if (!m.deleted) { if (selecting) { if (sel) selected.remove(m.id) else selected.add(m.id) } else menuMsg = m } },
                                    onReact = { e -> Repo.react(ref, m.id, if (m.reactions[me] == e) null else e) })
                            }
                        }
                        val gap = if (newer == null) 4.dp else if (newer.from == m.from && !isMedia(m) && !isMedia(newer)) 3.dp else if (newer.from == m.from) 7.dp else 12.dp
                        Spacer(Modifier.height(gap))
                    }
                }
            }
        }

        // ---------------- input
        if (ref.kind == Kind.CHANNEL && !canPost) {
            Column(Modifier.background(We.surface)) {
                Divider()
                if (isMember) Text("Only admins can post in this channel", color = We.text2, style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
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
                    IconBtn(Ic.Close, We.text2, { reply = null; if (editing != null) { editing = null; input = "" } }, 20.dp)
                }
            }
            Box {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                    if (rec != null) {
                        if (locked) IconBtn(Ic.Trash, We.danger, { endRec(false) }) else Spacer(Modifier.width(10.dp))
                        Row(Modifier.weight(1f).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            val pulse by animateFloatAsState(if (secs % 2 == 0) 1f else .35f, tween(500), label = "p")
                            Box(Modifier.size(11.dp).clip(CircleShape).background(We.danger.copy(alpha = pulse))); Spacer(Modifier.width(10.dp))
                            Text("%d:%02d".format(secs / 60, secs % 60), color = We.text, style = T.name)
                            Spacer(Modifier.width(12.dp))
                            if (locked) Row(Modifier.weight(1f).height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                                    val n = 28; val w = size.width / n
                                    repeat(n) { i -> val h = (size.height * (0.15f + level * ((i * 37 % 10) / 10f + .2f))).coerceIn(3f, size.height); drawRoundRect(primaryC, androidx.compose.ui.geometry.Offset(i * w + 1f, (size.height - h) / 2), androidx.compose.ui.geometry.Size(w - 3f, h), androidx.compose.ui.geometry.CornerRadius(3f)) }
                                }
                            } else Text(if (willCancel) "Release to cancel" else "‹  Slide to cancel", color = if (willCancel) We.danger else We.text2, style = T.body, modifier = Modifier.weight(1f))
                        }
                        if (locked) Box(Modifier.size(48.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { endRec(true) }), contentAlignment = Alignment.Center) { Icon(Ic.Send, We.onPrimary, 22.dp) }
                        else MicGesture(true, willCancel,
                            onMove = { dx, dy -> willCancel = dx < -with(ctx.resources.displayMetrics) { 110 * density }; if (dy < -with(ctx.resources.displayMetrics) { 90 * density }) locked = true },
                            onUp = { dx -> if (!locked) endRec(!willCancel && dx > -with(ctx.resources.displayMetrics) { 110 * density }) })
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
                        if (input.isNotBlank()) Box(Modifier.size(46.dp).clip(CircleShape).background(We.primary).combinedClickable(onClick = { sendText() }), contentAlignment = Alignment.Center) { Icon(Ic.Send, We.onPrimary, 22.dp) }
                        else MicGesture(false, false, onStart = {
                            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { startRec(); true }
                            else { micPerm.launch(Manifest.permission.RECORD_AUDIO); false }
                        })
                    }
                }
                if (rec != null && !locked) Column(Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = (-92).dp).clip(RoundedCornerShape(26.dp)).background(We.surface).padding(vertical = 12.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Ic.Lock, We.text2, 18.dp); Spacer(Modifier.height(6.dp)); Text("︿", color = We.text2, fontSize = 14.sp)
                }
            }
        }
    }

    // ---------------- popups / sheets
    if (attach) Sheet({ attach = false }) {
        SheetItem(Ic.Image, "Photos") { attach = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        SheetItem(Ic.Smile, "Sticker") { attach = false; stickers = true }
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
            onPin = { Repo.setPin(ref, m.id, m.id !in pinIds); menuMsg = null },
            onForward = { forwarding = listOf(m); menuMsg = null },
            onEdit = { editing = m; reply = null; input = m.text; menuMsg = null },
            onSelect = { selected.add(m.id); menuMsg = null },
            onDelete = { deleting = listOf(m); menuMsg = null })
    }
    deleting?.let { list ->
        val allMine = list.all { it.from == me } || (ref.kind == Kind.GROUP && group?.members?.get(me) == "admin")
        Sheet({ deleting = null }) {
            Text(if (list.size == 1) "Delete message?" else "Delete ${list.size} messages?", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
            SheetItem(Ic.Trash2, "Delete for me") { list.forEach { Repo.hideForMe(it.id) }; hideTick++; selected.clear(); deleting = null }
            if (allMine) SheetItem(Ic.Trash, "Delete for everyone", danger = true) { list.forEach { Repo.deleteForAll(ref, it.id) }; selected.clear(); deleting = null }
        }
    }
    forwarding?.let { list ->
        ForwardSheet({ forwarding = null }) { target ->
            forwarding = null; selected.clear()
            scope.launch { try { list.sortedBy { it.ts }.forEach { Repo.forward(ref, it, target) }; toast("Forwarded") } catch (e: Exception) { toast(e.message ?: "Could not forward") } }
        }
    }
    if (menu) Sheet({ menu = false }) {
        when (ref.kind) {
            Kind.DM -> if (!saved) {
                SheetItem(Ic.User, "View profile") { menu = false; Nav.push(RProfile(ref.peer!!)) }
                SheetItem(Ic.Block, if (ref.peer in blocked) "Unblock" else "Block", danger = ref.peer !in blocked) { Repo.block(ref.peer!!, ref.peer !in blocked); menu = false }
            } else SheetItem(Ic.Info, "Only you can see this chat") { menu = false }
            Kind.GROUP -> SheetItem(Ic.Group, "Group info") { menu = false; Nav.push(RGroupInfo(ref.id)) }
            Kind.CHANNEL -> {
                SheetItem(Ic.Info, "Channel info") { menu = false; Nav.push(RChannelInfo(ref.id)) }
                if (isMember && channel?.owner != me) SheetItem(Ic.Logout, "Leave channel", danger = true) { menu = false; scope.launch { Repo.unsubscribe(ref.id); Nav.pop() } }
            }
        }
    }
}

/** Mic button: press and hold to record. */
@Composable
private fun MicGesture(recording: Boolean, cancel: Boolean, onStart: (() -> Boolean)? = null, onMove: ((Float, Float) -> Unit)? = null, onUp: ((Float) -> Unit)? = null) {
    val sc by animateFloatAsState(if (recording) 1.6f else 1f, tween(160), label = "mic")
    val onStartS by rememberUpdatedState(onStart); val onMoveS by rememberUpdatedState(onMove); val onUpS by rememberUpdatedState(onUp)
    Box(Modifier.size(46.dp).scale(sc).clip(CircleShape).background(if (cancel) We.danger else We.primary).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val started = onStartS?.invoke() ?: true
            if (!started) { return@awaitEachGesture }
            while (true) {
                val ev = awaitPointerEvent(); val ch = ev.changes.first()
                val dx = ch.position.x - down.position.x; val dy = ch.position.y - down.position.y
                if (ch.changedToUp()) { onUpS?.invoke(dx); break }
                onMoveS?.invoke(dx, dy); ch.consume()
            }
        }
    }, contentAlignment = Alignment.Center) { Icon(Ic.Mic, We.onPrimary, 22.dp) }
}

@Composable
fun DayChip(t: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(t, color = We.text2, style = T.tiny, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(We.field).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun linkified(text: String, color: Color): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val m = android.util.Patterns.WEB_URL.matcher(text); var last = 0
    while (m.find()) {
        append(text.substring(last, m.start()))
        val url = m.group().let { if (it.startsWith("http")) it else "https://$it" }
        withLink(LinkAnnotation.Clickable("u", TextLinkStyles(SpanStyle(color = We.primary, textDecoration = TextDecoration.Underline))) { Nav.push(RBrowser(url)) }) { append(m.group()) }
        last = m.end()
    }
    append(text.substring(last))
}

@Composable
fun Bubble(m: Msg, mine: Boolean, ref: ChatRef, quoted: Msg?, showName: Boolean, read: Boolean, lastInRun: Boolean, pinned: Boolean, onClick: () -> Unit, onLong: () -> Unit, onReact: (String) -> Unit) {
    val r = UiPrefs.bubble.intValue.dp
    val shape = RoundedCornerShape(r, r, if (mine && lastInRun) 4.dp else r, if (!mine && lastInRun) 4.dp else r)
    val fg = if (mine) We.onSent else We.onRecv
    val isSticker = m.type == "sticker" && !m.deleted
    val scale = UiPrefs.textScale.floatValue
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.then(if (isSticker) Modifier else Modifier.clip(shape).background(if (mine) We.sent else We.recv))
                .combinedClickable(onClick = onClick, onLongClick = onLong).then(if (isSticker || m.type == "image") Modifier else Modifier.padding(horizontal = 12.dp, vertical = 8.dp))) {
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
                    m.type == "image" -> ImageBody(m, ref, mine, read, fg, pinned)
                    m.type == "voice" -> VoiceBody(m, ref, fg)
                    m.type == "sticker" -> StickerBody(m.text)
                    else -> Text(linkified(m.text, fg), color = fg, style = T.body.copy(fontSize = (15 * scale).sp, lineHeight = (21 * scale).sp))
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

@Composable
private fun MetaRow(m: Msg, mine: Boolean, read: Boolean, fg: Color, pinned: Boolean) {
    Row(Modifier.padding(top = 2.dp).wrapContentWidth(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (pinned) Icon(Ic.Pin, fg.copy(alpha = .55f), 11.dp)
        if (m.edited) Text("edited", color = fg.copy(alpha = .55f), style = T.tiny)
        Text(if (m.ts > 0) clock(m.ts) else "", color = fg.copy(alpha = .6f), style = T.tiny.copy(fontSize = 10.5.sp))
        if (mine && !m.deleted) Icon(if (read) Ic.Check2 else Ic.Check, if (read) We.primary else fg.copy(alpha = .55f), 15.dp)
    }
}

@Composable
private fun ImageBody(m: Msg, ref: ChatRef, mine: Boolean, read: Boolean, fg: Color, pinned: Boolean) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.mediaId) { m.mediaId?.let { id -> Repo.loadMedia(ref, id)?.let { value = Pics.decode(it) } } }
    val hasCap = m.text.isNotEmpty()
    Column(Modifier.width(254.dp)) {
        Box(Modifier.padding(3.dp).clip(RoundedCornerShape(13.dp)).combinedClickable(onClick = { m.mediaId?.let { Nav.push(RImage(ref, it)) } })) {
            val b = bmp
            if (b == null) Box(Modifier.fillMaxWidth().aspectRatio(1.2f).background(We.field), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(26.dp), color = We.primary, strokeWidth = 2.dp) }
            else Image(b, null, Modifier.fillMaxWidth().aspectRatio((b.width.toFloat() / b.height).coerceIn(.6f, 1.8f)), contentScale = ContentScale.Crop)
            if (!hasCap) Row(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock(m.ts), color = Color.White, style = T.tiny.copy(fontSize = 10.5.sp))
                if (mine) { Spacer(Modifier.width(3.dp)); Icon(if (read) Ic.Check2 else Ic.Check, if (read) Color(0xFF7DD3FC) else Color.White, 14.dp) }
            }
        }
        if (hasCap) {
            Text(linkified(m.text, fg), color = fg, style = T.body.copy(fontSize = (15 * UiPrefs.textScale.floatValue).sp), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            Box(Modifier.fillMaxWidth().padding(end = 10.dp, bottom = 6.dp)) { MetaRow(m, mine, read, fg, pinned) }
        }
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
                        onReply: () -> Unit, onCopy: () -> Unit, onPin: () -> Unit, onForward: () -> Unit, onEdit: () -> Unit, onSelect: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); val u = awaitPointerEvent(); if (u.changes.all { it.changedToUp() }) onDismiss() } }, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 20.dp).widthIn(max = 340.dp).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } }, horizontalAlignment = Alignment.Start) {
                if (ref.kind != Kind.CHANNEL || true) Row(Modifier.clip(RoundedCornerShape(30.dp)).background(We.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Anim.reactions.forEach { e ->
                        Box(Modifier.size(46.dp).clip(CircleShape).background(if (m.reactions[Repo.uid] == e) We.primary.copy(alpha = .18f) else Color.Transparent).combinedClickable(onClick = { onReact(e) }), contentAlignment = Alignment.Center) { ReactionEmoji(e, 38.dp) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(We.surface).width(250.dp).padding(vertical = 6.dp)) {
                    if (canReply) MenuRow(Ic.Reply, "Reply", onClick = onReply)
                    if (m.type == "text" && m.text.isNotEmpty()) MenuRow(Ic.Copy, "Copy text", onClick = onCopy)
                    if (canPin) MenuRow(Ic.Pin, if (pinned) "Unpin" else "Pin", onClick = onPin)
                    MenuRow(Ic.Forward, "Forward", onClick = onForward)
                    if (mine && m.type == "text") MenuRow(Ic.Edit, "Edit", onClick = onEdit)
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
private fun ForwardSheet(onDismiss: () -> Unit, onPick: (ChatRef) -> Unit) {
    val chats by remember { Repo.chats() }.collectAsState(emptyList())
    Sheet(onDismiss) {
        Text("Forward to…", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onPick(Repo.dmRef(Repo.uid)) }).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(We.primary), contentAlignment = Alignment.Center) { Icon(Ic.Bookmark, We.onPrimary, 22.dp) }
                Spacer(Modifier.width(14.dp)); Text("Saved Messages", color = We.text, style = T.name)
            }
            chats.filter { it.ref.kind != Kind.CHANNEL && !(it.ref.kind == Kind.DM && it.ref.peer == Repo.uid) }.forEach { c ->
                val (n, p, s) = titleOf(c.ref)
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onPick(c.ref) }).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(n, p, 44.dp, s); Spacer(Modifier.width(14.dp)); Text(n, color = We.text, style = T.name)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- stickers
@Composable
fun StickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val names = listOf("Animated", "Moods", "Reactions", "Words")
    Sheet(onDismiss) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEachIndexed { i, n ->
                Text(n, color = if (tab == i) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (tab == i) We.primary else We.field).combinedClickable(onClick = { tab = i }).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        val items: List<String> = when (tab) { 0 -> Anim.stickers.map { "a:$it" }; 1 -> StickerPacks.moods.map { "s:$it" }; 2 -> StickerPacks.reactions.map { "s:$it" }; else -> StickerPacks.words.map { "s:$it" } }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            items.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { p ->
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(5.dp).combinedClickable(onClick = { onPick(p) }), contentAlignment = Alignment.Center) {
                            if (p.startsWith("a:")) AnimEmoji(p.removePrefix("a:"), 64.dp)
                            else { val bmp = remember(p) { Pics.asset(ctx, p.removePrefix("s:")) }; if (bmp != null) Image(bmp, null, Modifier.fillMaxSize()) }
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
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, mediaId) { Repo.loadMedia(ref, mediaId)?.let { value = Pics.decode(it) } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize().systemBarsPadding(), contentScale = ContentScale.Fit) }
            ?: androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        IconBtn(Ic.Close, Color.White, { Nav.pop() }, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart))
    }
}
