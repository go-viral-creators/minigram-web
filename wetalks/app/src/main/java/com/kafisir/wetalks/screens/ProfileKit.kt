@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------------------------------------ building blocks
@Composable
fun GradientHeader(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(We.appBar, We.bg))).padding(top = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
fun AvatarRing(size: Dp, content: @Composable () -> Unit) {
    Box(Modifier.clip(CircleShape).background(We.bg).padding(4.dp)) { content() }
}

class Tile(val icon: ImageVector, val label: String, val tint: Color? = null, val onClick: () -> Unit)

@Composable
fun ActionTiles(tiles: List<Tile>) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { t ->
            val c = t.tint ?: We.primary
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = t.onClick), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(c.copy(alpha = .22f), c.copy(alpha = .09f)))), contentAlignment = Alignment.Center) { Icon(t.icon, c, 26.dp) }
                Text(t.label, color = We.text2, style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, bottom = 2.dp))
            }
        }
    }
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(We.field), content = content)
}

private fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text)); Nav.say("Copied")
}

/** One line of a card: small label above the value, optional icon button on the right. Tap to copy. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = We.text, copy: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().then(if (copy) Modifier.combinedClickable(onClick = { copyText(ctx, label, value) }) else Modifier).padding(start = 18.dp, end = 6.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = We.text2, style = T.tiny)
            Text(value, color = valueColor, style = T.body.copy(fontWeight = FontWeight.Medium))
        }
        trailing?.invoke()
    }
}

@Composable
fun CardDivider() { Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(We.line)) }

fun shareText(ctx: Context, text: String) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")) }

// ------------------------------------------------------------------------------------------------ shared media (images / voice / links)
private val dayFmt get() = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

@Composable
fun SharedMedia(ref: ChatRef, membersTab: (@Composable () -> Unit)? = null) {
    var tab by remember { mutableIntStateOf(0) }
    var more by remember(tab) { mutableIntStateOf(1) }
    val msgs by produceState<List<Msg>>(emptyList(), ref) {
        Repo.rawMessages(ref, 600).collect { raws ->
            value = raws.filter { !it.deleted && it.type in setOf("image", "gif", "voice", "link", "text") }.map { Repo.decrypt(ref, it) }.filter { !it.unreadable }.sortedByDescending { it.ts }
        }
    }
    val media = msgs.filter { it.type == "image" || it.type == "gif" }
    val voice = msgs.filter { it.type == "voice" }
    val links = msgs.filter { it.type == "link" || (it.type == "text" && LinkPreviews.firstUrl(it.text) != null) }
    val tabs = buildList {
        if (membersTab != null) add("Members")
        add("Media ${media.size}"); add("Voice ${voice.size}"); add("Links ${links.size}")
    }
    val off = if (membersTab != null) 1 else 0
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { i, n ->
                Text(n, color = if (tab == i) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (tab == i) We.primary else We.field).combinedClickable(onClick = { tab = i }).padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        val kind = when { membersTab != null && tab == 0 -> "members"; tab - off == 0 -> "media"; tab - off == 1 -> "voice"; else -> "links" }
        when (kind) {
            "members" -> membersTab?.invoke()
            "media" -> if (media.isEmpty()) NoItems(Ic.Image, "No photos yet") else Column(Modifier.padding(horizontal = 2.dp)) {
                media.take(30 * more).chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { m -> MediaCell(m, ref, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (media.size > 30 * more) ShowMore { more++ }
            }
            "voice" -> if (voice.isEmpty()) NoItems(Ic.Audio, "No voice messages yet") else Column {
                voice.take(25 * more).forEach { m -> VoiceRow(m, ref) }
                if (voice.size > 25 * more) ShowMore { more++ }
            }
            else -> if (links.isEmpty()) NoItems(Ic.Link, "No links yet") else Column {
                links.take(25 * more).forEach { m -> LinkRow(m, ref) }
                if (links.size > 25 * more) ShowMore { more++ }
            }
        }
    }
}

@Composable
private fun ShowMore(onClick: () -> Unit) {
    Text("Show more", color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(14.dp))
}

@Composable
private fun NoItems(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, We.text2.copy(alpha = .6f), 30.dp); Spacer(Modifier.height(8.dp)); Text(text, color = We.text2, style = T.small)
    }
}

@Composable
private fun MediaCell(m: Msg, ref: ChatRef, modifier: Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.mediaId) { m.mediaId?.let { id -> Repo.loadMedia(ref, id)?.let { value = Pics.decodeSampled(it, 240) } } }
    Box(modifier.padding(1.dp).aspectRatio(1f).background(We.field).combinedClickable(onClick = { Nav.openChatAt(ref, m.id) }), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (m.type == "gif") Text("GIF", color = Color.White, style = T.tiny, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

@Composable
private fun VoiceRow(m: Msg, ref: ChatRef) {
    val who by Repo.user(m.from).collectAsState()
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Nav.openChatAt(ref, m.id) }).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(We.primary), contentAlignment = Alignment.Center) { Icon(Ic.Play, We.onPrimary, 20.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Voice message", color = We.text, style = T.name.copy(fontSize = 15.sp))
            Text((if (m.from == Repo.uid) "You" else who?.name ?: "") + " · " + dayFmt.format(Date(m.ts)), color = We.text2, style = T.small)
        }
        Text("%d:%02d".format(m.dur / 60, m.dur % 60), color = We.text2, style = T.small)
    }
}

@Composable
private fun LinkRow(m: Msg, ref: ChatRef) {
    val ctx = LocalContext.current
    val url = m.link?.url ?: LinkPreviews.firstUrl(m.text) ?: ""
    val host = remember(url) { try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url } }
    val thumb = remember(m.link?.thumb) { m.link?.thumb?.let { Pics.decodeB64(it) } }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Nav.openChatAt(ref, m.id) }).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(We.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Ic.Link, We.primary, 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.link?.title?.ifEmpty { null } ?: host, color = We.text, style = T.name.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, color = We.primary, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(dayFmt.format(Date(m.ts)), color = We.text2, style = T.tiny)
        }
        IconBtn(Ic.Globe, We.text2, { Nav.push(RBrowser(url)) }, 20.dp)
    }
}

// ------------------------------------------------------------------------------------------------ QR
@Composable
fun QrImage(text: String, size: Dp, fg: Color = Color(0xFF0B1220)) {
    val m = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q)) }
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val n = m.width; val cell = this.size.width / n
            for (y in 0 until n) for (x in 0 until n) if (m.get(x, y)) drawRoundRect(fg, Offset(x * cell, y * cell), Size(cell + .6f, cell + .6f), CornerRadius(cell * .28f))
        }
        Box(Modifier.size(size * .2f).clip(CircleShape).background(Color.White).padding(3.dp)) { Logo(size * .2f) }
    }
}

fun scanQr(ctx: Context) {
    try {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { b -> Repo.scope.launch { val r = try { Repo.resolveLink(b.rawValue ?: "") } catch (e: Exception) { null }; if (r != null) Nav.push(r) else Nav.say("That's not a WeTalks code") } }
            .addOnFailureListener { Nav.say("Scanner isn't available on this phone") }
    } catch (e: Exception) { Nav.say("Scanner isn't available on this phone") }
}

@Composable
fun QrScreen(r: RQr) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("QR code", onBack = { Nav.pop() })
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(We.appBar, We.bg))).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(r.title, r.photo, 64.dp, r.seed)
                Spacer(Modifier.height(10.dp))
                Text(r.title, color = Color(0xFF0B1220), style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.subtitle, color = Color(0xFF6B7A90), style = T.body)
                Spacer(Modifier.height(18.dp))
                QrImage(r.link, 230.dp)
                Spacer(Modifier.height(14.dp))
                Text("Scan with WeTalks to open", color = Color(0xFF6B7A90), style = T.small)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Share link", { shareText(ctx, r.link) })
            Spacer(Modifier.height(10.dp))
            OutlineButton("Scan a code", { scanQr(ctx) })
            Spacer(Modifier.height(10.dp))
            Text("Copy link", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = { copyText(ctx, "link", r.link) }).padding(12.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------ people
@Composable
fun ProfileScreen(uid: String) {
    val ctx = LocalContext.current
    val u by Repo.user(uid).collectAsState()
    val blocked by Repo.blockedUsers.collectAsState()
    val chats by remember { Repo.chats() }.collectAsState(emptyList())
    val muted = chats.firstOrNull { it.ref.id == Repo.dmId(uid) }?.muted == true
    val safety by produceState<String?>(null, uid) { value = try { Repo.safetyNumber(uid) } catch (e: Exception) { null } }
    var showSafety by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val startCall = rememberCallStarter(uid)
    val me = uid == Repo.uid
    val link = "wetalks://u/" + (u?.username ?: "")
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(u?.name ?: "Profile", onBack = { Nav.pop() }, actions = { if ((u?.username ?: "").isNotEmpty()) IconBtn(Ic.Qr, We.onAppBar, { Nav.push(RQr(u!!.name, "@" + u!!.username, u!!.photo, uid, link)) }) })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GradientHeader {
                AvatarRing(112.dp) { Avatar(u?.name ?: "", u?.photo ?: "", 104.dp, uid) }
                Spacer(Modifier.height(12.dp))
                Text(u?.name ?: "", color = We.text, style = T.title.copy(fontSize = 23.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                val status = when { me -> "This is you"; u?.online == true && u?.showLastSeen == true -> "online"; u?.showLastSeen == true && (u?.lastSeen ?: 0) > 0 -> "last seen " + timeShort(u!!.lastSeen); else -> "last seen recently" }
                Text(status, color = if (status == "online") We.primary else We.text2, style = T.small, modifier = Modifier.padding(top = 2.dp))
            }
            if (!me) {
                ActionTiles(listOf(
                    Tile(Ic.Chat, "Message") { Nav.pop(); Nav.push(RChat(Repo.dmRef(uid))) },
                    Tile(Ic.Phone, "Voice call") { startCall(false) },
                    Tile(Ic.Video, "Video call") { startCall(true) },
                    Tile(if (muted) Ic.Bell else Ic.BellOff, if (muted) "Unmute" else "Mute") { Repo.muteDm(uid, !muted) },
                    Tile(Ic.Dots, "More") { more = true },
                ))
                Spacer(Modifier.height(14.dp))
            }
            InfoCard {
                if ((u?.username ?: "").isNotEmpty()) {
                    InfoRow("Username", "@" + u!!.username, We.primary, trailing = { IconBtn(Ic.Qr, We.primary, { Nav.push(RQr(u!!.name, "@" + u!!.username, u!!.photo, uid, link)) }) })
                    CardDivider()
                    InfoRow("Link", link, We.text, trailing = { IconBtn(Ic.Share, We.text2, { shareText(ctx, link) }, 20.dp) })
                }
                if (!u?.about.isNullOrEmpty()) { CardDivider(); InfoRow("About", u!!.about, copy = false) }
                CardDivider()
                InfoRow("Encryption", "End-to-end encrypted · tap to verify", We.text, copy = false, trailing = { IconBtn(Ic.Shield, We.ok, { showSafety = true }) })
            }
            Spacer(Modifier.height(14.dp))
            SharedMedia(Repo.dmRef(uid))
            Spacer(Modifier.height(24.dp))
        }
    }
    if (more) Sheet({ more = false }) {
        SheetItem(Ic.Shield, "Verify encryption") { more = false; showSafety = true }
        if ((u?.username ?: "").isNotEmpty()) SheetItem(Ic.Share, "Share profile link") { more = false; shareText(ctx, link) }
        SheetItem(Ic.Block, if (uid in blocked) "Unblock" else "Block", danger = uid !in blocked) { Repo.block(uid, uid !in blocked); more = false }
    }
    if (showSafety) Sheet({ showSafety = false }) {
        Text("Verify safety number", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Text("Compare this number with ${u?.name ?: "your contact"} in person or on a call. If it matches on both phones, nobody is intercepting your chat.", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp))
        Text(safety ?: "Not available yet: the other person must open WeTalks first.", color = We.text, style = T.title.copy(fontFamily = FontFamily.Monospace, fontSize = 18.sp, lineHeight = 30.sp),
            modifier = Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(We.field).padding(20.dp))
    }
}

// ------------------------------------------------------------------------------------------------ channel
@Composable
fun ChannelInfoScreen(id: String) {
    val ctx = LocalContext.current
    val c by Repo.channel(id).collectAsState()
    val scope = rememberCoroutineScope()
    val chats by remember { Repo.chats() }.collectAsState(emptyList())
    val muted = chats.firstOrNull { it.ref.id == id }?.muted == true
    val subscribed by produceState(false, id) { value = Repo.isSubscribed(id) }
    var joined by remember { mutableStateOf<Boolean?>(null) }
    val isSub = joined ?: subscribed
    var editing by remember { mutableStateOf(false) }
    val ch = c ?: run { Column(Modifier.fillMaxSize().background(We.bg)) { TopBar("Channel", onBack = { Nav.pop() }) }; return }
    val owner = ch.owner == Repo.uid
    if (editing) { EditNameScreen("Edit channel", ch.name, ch.about, ch.photo, ch.id, { n, a, p -> Repo.updateChannel(id, n, a, p) }) { editing = false }; return }
    val link = "wetalks://c/" + ch.handle
    val qr = RQr(ch.name, "@" + ch.handle, ch.photo, ch.id, link)
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Channel", onBack = { Nav.pop() }, actions = {
            IconBtn(Ic.Qr, We.onAppBar, { Nav.push(qr) })
            if (owner) IconBtn(Ic.Edit, We.onAppBar, { editing = true })
        })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GradientHeader {
                AvatarRing(112.dp) { Avatar(ch.name, ch.photo, 104.dp, ch.id) }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Icon(Ic.Mega, We.primary, 20.dp); Spacer(Modifier.width(6.dp))
                    Text(ch.name, color = We.text, style = T.title.copy(fontSize = 23.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("${ch.subCount} ${if (ch.subCount == 1L) "follower" else "followers"}", color = We.text2, style = T.small, modifier = Modifier.padding(top = 2.dp))
            }
            ActionTiles(buildList {
                add(Tile(Ic.Chat, if (isSub || owner) "Open" else "Preview") { Nav.pop(); Nav.push(RChat(ChatRef(id, Kind.CHANNEL))) })
                if (!owner) add(if (isSub) Tile(if (muted) Ic.Bell else Ic.BellOff, if (muted) "Unmute" else "Mute") { Repo.setMuted(id, !muted) }
                    else Tile(Ic.Plus, "Join") { scope.launch { try { Repo.subscribe(id); joined = true } catch (e: Exception) { Nav.say("Could not join") } } })
                add(Tile(Ic.Share, "Share") { shareText(ctx, "Join ${ch.name} on WeTalks: $link") })
                add(Tile(Ic.Qr, "QR code") { Nav.push(qr) })
                if (owner) add(Tile(Ic.Sliders, "Settings") { Nav.push(RChannelSettings(id)) })
                if (!owner && isSub) add(Tile(Ic.Logout, "Leave", We.danger) { scope.launch { Repo.unsubscribe(id); joined = false; Nav.say("You left the channel") } })
            })
            Spacer(Modifier.height(14.dp))
            InfoCard {
                InfoRow("Channel link", "@" + ch.handle, We.primary, trailing = { IconBtn(Ic.Qr, We.primary, { Nav.push(qr) }) })
                CardDivider()
                InfoRow("Invite link", link, trailing = { IconBtn(Ic.Share, We.text2, { shareText(ctx, "Join ${ch.name} on WeTalks: $link") }, 20.dp) })
                if (ch.about.isNotEmpty()) { CardDivider(); InfoRow("About", ch.about, copy = false) }
            }
            Spacer(Modifier.height(14.dp))
            SharedMedia(ChatRef(id, Kind.CHANNEL))
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------ group
@Composable
fun GroupInfoScreen(id: String) {
    val g by Repo.group(id).collectAsState()
    val scope = rememberCoroutineScope()
    val chats by remember { Repo.chats() }.collectAsState(emptyList())
    val muted = chats.firstOrNull { it.ref.id == id }?.muted == true
    var editing by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val group = g ?: run { Column(Modifier.fillMaxSize().background(We.bg)) { TopBar("Group", onBack = { Nav.pop() }) }; return }
    val me = Repo.uid
    val admin = group.members[me] == "admin"
    if (editing) { EditNameScreen("Edit group", group.name, group.about, group.photo, group.id, { n, a, p -> Repo.updateGroup(id, n, a, p) }) { editing = false }; return }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Group", onBack = { Nav.pop() }, actions = { if (admin) IconBtn(Ic.Edit, We.onAppBar, { editing = true }) })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            GradientHeader {
                AvatarRing(112.dp) { Avatar(group.name, group.photo, 104.dp, group.id) }
                Spacer(Modifier.height(12.dp))
                Text(group.name, color = We.text, style = T.title.copy(fontSize = 23.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                Text("${group.members.size} members", color = We.text2, style = T.small, modifier = Modifier.padding(top = 2.dp))
            }
            ActionTiles(buildList {
                add(Tile(Ic.Chat, "Message") { Nav.pop(); Nav.push(RChat(ChatRef(id, Kind.GROUP))) })
                add(Tile(if (muted) Ic.Bell else Ic.BellOff, if (muted) "Unmute" else "Mute") { Repo.setMuted(id, !muted) })
                if (admin) add(Tile(Ic.UserPlus, "Add") { Nav.push(RAddMembers(id)) })
                if (admin) add(Tile(Ic.Sliders, "Settings") { Nav.push(RGroupSettings(id)) })
                add(Tile(Ic.Logout, "Leave", We.danger) { confirmLeave = true })
            })
            Spacer(Modifier.height(14.dp))
            InfoCard {
                InfoRow("Group", group.name)
                if (group.about.isNotEmpty()) { CardDivider(); InfoRow("About", group.about, copy = false) }
                CardDivider()
                InfoRow("Encryption", "End-to-end encrypted. The key changes whenever someone is removed.", copy = false, trailing = { Icon(Ic.Lock, We.ok, 20.dp); Spacer(Modifier.width(12.dp)) })
            }
            Spacer(Modifier.height(14.dp))
            SharedMedia(ChatRef(id, Kind.GROUP), membersTab = {
                Column {
                    if (admin) Row2(Ic.UserPlus, "Add members", tint = We.primary, onClick = { Nav.push(RAddMembers(id)) })
                    group.members.entries.sortedBy { if (it.value == "admin") 0 else 1 }.forEach { (uid, role) ->
                        val u by Repo.user(uid).collectAsState()
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (uid != me) { if (admin) memberMenu = uid else Nav.push(RProfile(uid)) } }).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u?.name ?: "", u?.photo ?: "", 44.dp, uid); Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) { Text(if (uid == me) "You" else u?.name ?: "…", color = We.text, style = T.name); Text("@" + (u?.username ?: ""), color = We.text2, style = T.small) }
                            if (role == "admin") Text("Admin", color = We.primary, style = T.tiny, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .1f)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            })
            Spacer(Modifier.height(30.dp))
        }
    }
    memberMenu?.let { uid ->
        val role = group.members[uid]
        Sheet({ memberMenu = null }) {
            SheetItem(Ic.User, "View profile") { memberMenu = null; Nav.push(RProfile(uid)) }
            SheetItem(Ic.Shield, if (role == "admin") "Remove admin" else "Make admin") { scope.launch { Repo.setAdmin(id, uid, role != "admin") }; memberMenu = null }
            SheetItem(Ic.Trash, "Remove from group", danger = true) { scope.launch { try { Repo.removeMember(id, uid); Nav.say("Removed. The group key was rotated.") } catch (e: Exception) { Nav.say("Could not remove") } }; memberMenu = null }
        }
    }
    if (confirmLeave) Sheet({ confirmLeave = false }) {
        Text("Leave this group?", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        SheetItem(Ic.Logout, "Leave group", danger = true) { confirmLeave = false; scope.launch { Repo.leaveGroup(id); Nav.reset() } }
    }
}
