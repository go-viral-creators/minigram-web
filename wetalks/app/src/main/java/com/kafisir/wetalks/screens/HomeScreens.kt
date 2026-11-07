@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.ChatItem
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Kind
import com.kafisir.wetalks.data.Msg
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val tab by Nav.tab
    val chats by remember { Repo.chats() }.collectAsState(initial = emptyList())
    val blocked by Repo.blockedUsers.collectAsState()
    val visible = chats.filter { !(it.ref.kind == Kind.DM && it.ref.peer in blocked) }.sortedWith(compareByDescending<ChatItem> { it.pinned }.thenByDescending { c -> Repo.outgoing.lastOrNull { it.ref.id == c.ref.id }?.ts ?: c.lastTs })
    var labelPicked by remember { mutableStateOf<String?>(null) }
    val allLabels by Repo.labels.collectAsState()
    val labelFilter = labelPicked?.takeIf { f -> allLabels.any { it.id == f } }          // a deleted label cannot leave the list filtered to nothing
    // archived and locked chats leave the main list (they sit in their own rows); a label filter narrows what is left
    fun main(kind: Kind) = visible.filter { it.ref.kind == kind && !it.archived && !com.kafisir.wetalks.data.ChatLock.isLocked(it.ref.id) && (labelFilter == null || labelFilter in it.labels) }
    val titles = listOf("WeTalks", "Groups", "Channels", "Calls", "Me")
    val callLog by Repo.callLog.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(titles[tab], actions = {
            if (tab == 0) { IconBtn(Ic.Scan, We.onAppBar, { scanQr(ctx) }); IconBtn(Ic.Search, We.onAppBar, { Nav.push(RNewChat) }) }
            if (tab == 2) IconBtn(Ic.Search, We.onAppBar, { Nav.push(RDiscover) })
        })
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatList(main(Kind.DM), Ic.Chat, "No chats yet", "Tap the + button to start a private conversation.", header = { RulesBanner(); ServicesRow(); LabelBar(labelFilter) { labelPicked = it }; FolderRows(0, visible) })
                1 -> ChatList(main(Kind.GROUP), Ic.Group, "No groups yet", "Create a group to chat with several people at once.", header = { LabelBar(labelFilter) { labelPicked = it }; FolderRows(1, visible) })
                2 -> ChatList(main(Kind.CHANNEL), Ic.Mega, "No channels yet", "Tap the search icon to find public channels, or + to start your own.", header = { LabelBar(labelFilter) { labelPicked = it }; FolderRows(2, visible) })
                3 -> CallsTab()
                else -> MeScreen()
            }
            if (tab < 4) Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(56.dp).clip(CircleShape).background(We.primary)
                .combinedClickable(onClick = { Nav.push(when (tab) { 0 -> RNewChat; 1 -> RNewGroup; 3 -> RNewChat; else -> RNewChannel }) }), contentAlignment = Alignment.Center) {
                Icon(if (tab == 0) Ic.Edit else if (tab == 3) Ic.Phone else Ic.Plus, We.onPrimary, 26.dp)
            }
        }
        BottomNav(tab, chats.filter { it.ref.kind == Kind.DM }.sumOf { it.unread }, chats.filter { it.ref.kind == Kind.GROUP }.sumOf { it.unread }, callLog.count { it.missed && it.ts > CallSeen.seen }.toLong())
    }
}

@Composable
fun BottomNav(tab: Int, dmUnread: Long, grpUnread: Long, missedCalls: Long = 0L) {
    val items = listOf(Triple(Ic.Chat, "Chats", dmUnread), Triple(Ic.Group, "Groups", grpUnread), Triple(Ic.Mega, "Channels", 0L), Triple(Ic.Phone, "Calls", missedCalls), Triple(Ic.User, "Me", 0L))
    Column(Modifier.background(We.surface)) {
        Divider()
        Row(Modifier.navigationBarsPadding().height(60.dp).fillMaxWidth()) {
            items.forEachIndexed { i, (ic, label, n) ->
                val on = i == tab
                Column(Modifier.weight(1f).fillMaxSize().combinedClickable(onClick = { Nav.tab.intValue = i }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box { Icon(ic, if (on) We.primary else We.text2, 24.dp); if (n > 0) Box(Modifier.align(Alignment.TopEnd).padding(start = 18.dp)) { Badge(n) } }
                    Text(label, color = if (on) We.primary else We.text2, style = T.tiny.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatList(items: List<ChatItem>, emptyIcon: ImageVector, emptyTitle: String, emptySub: String, header: (@Composable () -> Unit)? = null) {
    var menu by remember { mutableStateOf<ChatItem?>(null) }
    var delete by remember { mutableStateOf<ChatItem?>(null) }
    var labelsFor by remember { mutableStateOf<ChatItem?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize()) { header?.invoke(); Box(Modifier.weight(1f)) { Empty(emptyIcon, emptyTitle, emptySub) } }
    } else LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        items(items, key = { it.ref.id }) { it ->
            ChatRow(it, onClick = { Nav.push(RChat(it.ref)) }, onLong = { menu = it }); Divider(start = 76.dp)
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
    menu?.let { c ->
        Sheet({ menu = null }) {
            SheetItem(Ic.Pin, if (c.pinned) "Unpin from top" else "Pin to top") { if (!Repo.setPinned(c.ref.id, !c.pinned)) Nav.say("You can pin up to ${Repo.MAX_PINNED} chats"); menu = null }
            SheetItem(Ic.Archive, if (c.archived) "Unarchive" else "Archive") { Repo.scope.launch { try { Repo.setArchived(c.ref.id, !c.archived) } catch (e: Exception) { Nav.say(Repo.explain(e, "Archive")) } }; menu = null }
            SheetItem(Ic.Tag, "Labels") { labelsFor = c; menu = null }
            SheetItem(Ic.Lock, if (com.kafisir.wetalks.data.ChatLock.isLocked(c.ref.id)) "Unlock chat" else "Lock chat") { toggleChatLock(ctx as androidx.fragment.app.FragmentActivity, c); menu = null }
            if (c.ref.kind != Kind.CHANNEL || true) SheetItem(if (c.muted) Ic.Bell else Ic.BellOff, if (c.muted) "Unmute" else "Mute") { Repo.setMuted(c.ref.id, !c.muted); menu = null }
            SheetItem(Ic.Trash, if (c.ref.kind == Kind.DM) "Delete chat" else "Remove from list", danger = true) { delete = c; menu = null }
        }
    }
    delete?.let { c -> val (n, _, _) = titleOf(c.ref); DeleteChatDialog(c.ref, n, { delete = null }) }
    labelsFor?.let { c -> LabelPickerSheet(items.firstOrNull { it.ref.id == c.ref.id } ?: c) { labelsFor = null } }
}

@Composable
fun titleOf(ref: ChatRef): Triple<String, String, String> {   // name, photo, seed
    return when (ref.kind) {
        Kind.DM -> { val u by Repo.user(ref.peer!!).collectAsState(); if (ref.peer == Repo.uid) Triple("Saved Messages", "", ref.peer) else Triple(u?.name ?: "…", u?.photo ?: "", ref.peer) }
        Kind.GROUP -> { val g by Repo.group(ref.id).collectAsState(); Triple(g?.name ?: "Group", g?.photo ?: "", ref.id) }
        Kind.CHANNEL -> { val c by Repo.channel(ref.id).collectAsState(); Triple(c?.name ?: "Channel", c?.photo ?: "", ref.id) }
    }
}

fun previewText(type: String, text: String) = when (type) {
    "image" -> "Photo"; "voice" -> "Voice message"; "sticker" -> "Sticker"; "gif" -> "GIF"; "call" -> "Call"; "video" -> "Video"; "audio" -> "Audio"; "file" -> "Document"; else -> if (UiPrefs.markdown.value) Markdown.preview(text) else text
}

/** Telegram-style dialog: optionally tick "Also delete for X" to wipe the chat on both phones. */
@Composable
fun DeleteChatDialog(ref: ChatRef, name: String, onDismiss: () -> Unit, onDone: () -> Unit = {}) {
    var both by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val dm = ref.kind == Kind.DM && ref.peer != Repo.uid
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(We.surface).padding(22.dp)) {
            Text(if (ref.kind == Kind.DM) "Delete chat" else "Remove from list", color = We.text, style = T.title)
            Spacer(Modifier.height(8.dp))
            Text(when (ref.kind) { Kind.DM -> "Are you sure you want to delete the chat with $name? This clears it from this phone."; Kind.GROUP -> "Remove $name from your chat list? You stay a member and can open it again."; else -> "Remove $name from your list?" }, color = We.text2, style = T.body)
            if (dm) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).tap { both = !both }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(if (both) We.primary else We.line), contentAlignment = Alignment.Center) { if (both) Icon(Ic.Check, We.onPrimary, 14.dp) }
                    Spacer(Modifier.width(12.dp))
                    Text("Also delete for $name", color = We.text, style = T.body)
                }
                if (both) Text("Every message and the chat entry will be removed on $name's phone too. This can't be undone.", color = We.danger, style = T.small, modifier = Modifier.padding(top = 4.dp, start = 34.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                Text("Cancel", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).tap { if (!busy) onDismiss() }.padding(horizontal = 14.dp, vertical = 10.dp))
                Text(if (busy) "Deleting…" else "Delete", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).tap {
                    if (busy) return@tap
                    busy = true
                    com.kafisir.wetalks.ui.Sfx.play("delete")
                    Repo.scope.launch { try { Repo.deleteChat(ref, both); onDismiss(); onDone() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { busy = false; Nav.say("Could not delete: " + (e.message ?: "try again")) } }
                }.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(c: ChatItem, onClick: () -> Unit, onLong: () -> Unit) {
    val (name, photo, seed) = titleOf(c.ref)
    val chan = if (c.ref.kind == Kind.CHANNEL) Repo.channel(c.ref.id).collectAsState().value else null
    val saved = c.ref.kind == Kind.DM && c.ref.peer == Repo.uid
    val ob = Repo.outgoing.lastOrNull { it.ref.id == c.ref.id }
    val shownTs = if (ob != null) ob.ts else if (chan != null && chan.lastTs > c.lastTs) chan.lastTs else c.lastTs
    val preview by produceState("", c.lastBody, c.lastType, chan?.lastPreview, chan?.lastTs) {
        if (chan != null && c.lastBody.isEmpty()) { value = if (chan.priv) Repo.privPreview(chan.id) else chan.lastPreview; return@produceState }
        value = if (c.lastBody.isEmpty()) "" else {
            val m = Repo.decrypt(c.ref, Repo.Raw("", c.lastFrom, c.lastTs, c.lastType, c.lastBody, 0, null, false, false, emptyMap()))
            if (m.unreadable) "Encrypted message" else (if (m.type == "gif") (if (m.mime.contains("gif")) "GIF" else "Sticker") else if (m.type == "call") callLabel(m, c.lastFrom == Repo.uid) else previewText(m.type, m.text))
        }
    }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (saved) Box(Modifier.size(52.dp).clip(CircleShape).background(We.primary), contentAlignment = Alignment.Center) { Icon(Ic.Bookmark, We.onPrimary, 26.dp) }
        else Avatar(name, photo, 52.dp, seed)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.ref.kind == Kind.GROUP) { Icon(Ic.Group, We.text2, 15.dp); Spacer(Modifier.width(4.dp)) }
                if (c.ref.kind == Kind.CHANNEL) { Icon(Ic.Mega, We.text2, 15.dp); Spacer(Modifier.width(4.dp)) }
                Text(name, color = We.text, style = T.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.pinned) { Icon(Ic.Pin, We.text2, 14.dp); Spacer(Modifier.width(6.dp)) }
                Text(if (shownTs > 0) timeShort(shownTs) else "", color = if (c.unread > 0 && !c.muted) We.primary else We.text2, style = T.tiny)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val mine = c.lastFrom == Repo.uid
                if (ob != null) { Icon(if (ob.failed) Ic.Alert else Ic.Clock, if (ob.failed) We.danger else We.text2, 14.dp); Spacer(Modifier.width(4.dp)) }
                Text(if (ob != null) "You: " + when (ob.type) { "voice" -> "Voice message"; "image" -> "Photo"; "gif" -> "GIF"; else -> ob.text.ifEmpty { "Sending…" } } else (if (mine && preview.isNotEmpty()) "You: " else "") + preview, color = We.text2, style = T.body.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.muted) { Icon(Ic.BellOff, We.text2, 15.dp); Spacer(Modifier.width(6.dp)) }
                Badge(c.unread, c.muted)
            }
        }
    }
}

@Composable
fun MeScreen() {
    val me by Repo.user(Repo.uid).collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirmOut by remember { mutableStateOf(false) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(We.appBar, We.bg))).padding(top = 22.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(CircleShape).background(We.bg).padding(4.dp).tap { Nav.push(REditProfile) }) { Avatar(me?.name ?: "", me?.photo ?: "", 104.dp, Repo.uid) }
                Spacer(Modifier.height(12.dp))
                Text(me?.name ?: "", color = We.text, style = T.title)
                Text("@" + (me?.username ?: ""), color = We.primary, style = T.body)
                if (!me?.about.isNullOrEmpty()) Text(me!!.about, color = We.text2, style = T.small, modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).background(We.primary.copy(alpha = .12f)).tap { Nav.push(REditProfile) }.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Edit, We.primary, 16.dp); Spacer(Modifier.width(8.dp)); Text("Edit profile", color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            Divider()
            Row2(Ic.Bookmark, "Saved Messages", "Notes, photos and voice for yourself", onClick = { Nav.push(RChat(Repo.dmRef(Repo.uid))) })
            Row2(Ic.Qr, "My QR code", "Let friends scan to message you", onClick = { Repo.user(Repo.uid).value?.let { Nav.push(RQr(it.name, "@" + it.username, it.photo, Repo.uid, Repo.Links.user(it.username))) } })
            Row2(Ic.UserPlus, "New group", "Chat with several people", onClick = { Nav.push(RNewGroup) })
            Row2(Ic.Mega, "New channel", "Broadcast to followers", onClick = { Nav.push(RNewChannel) })
            SectionLabel("Settings")
            Row2(Ic.Shield, "Privacy & security", "Last seen, read receipts, blocked users", onClick = { Nav.push(RPrivacy) })
            var stay by remember { mutableStateOf(com.kafisir.wetalks.data.ConnectService.enabled(ctx)) }
            Row2(Ic.Phone, "Stay connected for calls", "Keeps a small notification so calls can ring even when the app is closed", trailing = { Toggle(stay) { stay = it; com.kafisir.wetalks.data.ConnectService.setEnabled(ctx, it) } })
            var msgN by remember { mutableStateOf(com.kafisir.wetalks.data.Notifier.enabled(ctx)) }
            Row2(Ic.Speaker, "Natural voices", if (com.kafisir.wetalks.voice.NeuralTts.usable()) "On · human-like reading aloud" else "Download a human-like voice for Listen", onClick = { Nav.push(RNaturalVoices) })
            Row2(Ic.Chat, "Message notifications", "Show new messages in the notification bar with Reply, Mark as read and Mute", trailing = { Toggle(msgN) { msgN = it; com.kafisir.wetalks.data.Notifier.setEnabled(ctx, it) } })
            if (android.os.Build.VERSION.SDK_INT >= 34 && !(ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent())
                Row2(Ic.Phone, "Allow full-screen calls", "Android needs this so incoming calls can open over the lock screen", onClick = { try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {} })
            Row2(Ic.Bell, "Allow background activity", "Open the battery settings: choose 'No restrictions' (and allow Autostart on Xiaomi / Oppo / Vivo phones)", onClick = {
                try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))) } catch (_: Exception) {}
            })
            Row2(Ic.Fingerprint, "App lock", if (AppLock.enabled) "On · fingerprint or screen lock" else "Off", onClick = { Nav.push(RAppLock) })
            Row2(Ic.Key, "Two-step verification", "Ask for a 6-digit PIN when this account is opened on a new phone", onClick = { Nav.push(RTwoStep) })
            Row2(Ic.Phone, "Linked devices", "Use WeTalks on more than one phone", onClick = { Nav.push(RDevices) })
            Row2(Ic.Tag, "Labels", "Name and colour your chats, then filter by them", onClick = { Nav.push(RLabels) })
            Row2(Ic.Type, "Chat settings", "Text size, bubble shape, wallpaper", onClick = { Nav.push(RChatSettings) })
            Row2(Ic.Globe, "Language", I18n.byCode(I18n.lang).native + " · translate to " + I18n.byCode(I18n.translateTo).english, onClick = { Nav.push(RLanguage) })
            Row2(Ic.Palette, "Appearance", (when (ThemeMode.mode.intValue) { 1 -> "Light"; 2 -> "Dark"; else -> "Follow system" }) + " · " + UiPrefs.accents[UiPrefs.accent.intValue].first, onClick = { Nav.push(RAppearance) })
            Row2(Ic.Database, "Data & storage", "Cache, downloaded media", onClick = { Nav.push(RStorage) })
            SectionLabel("WeTalks")
            Row2(Ic.Invite, "Invite friends", "Tell your friends about WeTalks", onClick = {
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, "Join me on WeTalks: private messaging with end-to-end encrypted chats, groups and channels."), "Invite friends"))
            })
            Row2(Ic.Info, "About WeTalks", "Version, encryption, licences", onClick = { Nav.push(RAbout) })
            Divider()
            Row2(Ic.Logout, "Sign out", tint = We.danger, onClick = { confirmOut = true })
            Spacer(Modifier.height(30.dp))
        }
    }
    if (confirmOut) Sheet({ confirmOut = false }) {
        Text("Sign out of WeTalks?", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        Text("Your encryption key stays on this phone, so chats remain readable when you sign back in here.", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        SheetItem(Ic.Logout, "Sign out", danger = true) { confirmOut = false; Nav.reset(); Repo.signOut() }
    }
}

fun Modifier.combinedClickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.tap(onClick))


fun callLabel(m: Msg, mine: Boolean): String {
    val kind = if (m.w == 1) "Video call" else "Voice call"
    return when (m.mime) {
        "ended" -> kind + " · " + "%d:%02d".format(m.dur / 60, m.dur % 60)
        "declined" -> "$kind · declined"
        else -> if (mine) "$kind · no answer" else "Missed " + kind.lowercase()
    }
}
