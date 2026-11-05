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
import androidx.compose.material3.Text
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
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*

@Composable
fun HomeScreen() {
    val tab by Nav.tab
    val chats by remember { Repo.chats() }.collectAsState(initial = emptyList())
    val blocked by Repo.blockedUsers.collectAsState()
    val visible = chats.filter { !(it.ref.kind == Kind.DM && it.ref.peer in blocked) }
    val titles = listOf("WeTalks", "Groups", "Channels", "Me")
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(titles[tab], actions = {
            if (tab == 0) IconBtn(Ic.Search, We.onAppBar, { Nav.push(RNewChat) })
            if (tab == 2) IconBtn(Ic.Search, We.onAppBar, { Nav.push(RDiscover) })
        })
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ChatList(visible.filter { it.ref.kind == Kind.DM }, Ic.Chat, "No chats yet", "Tap the + button to start a private conversation.")
                1 -> ChatList(visible.filter { it.ref.kind == Kind.GROUP }, Ic.Group, "No groups yet", "Create a group to chat with several people at once.")
                2 -> ChatList(visible.filter { it.ref.kind == Kind.CHANNEL }, Ic.Mega, "No channels yet", "Follow public channels or start your own broadcast.", header = {
                    Row2(Ic.Search, "Discover channels", "Find public channels to follow", We.primary, { Nav.push(RDiscover) }); Divider()
                })
                else -> MeScreen()
            }
            if (tab < 3) Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(56.dp).clip(CircleShape).background(We.primary)
                .combinedClickable(onClick = { Nav.push(when (tab) { 0 -> RNewChat; 1 -> RNewGroup; else -> RNewChannel }) }), contentAlignment = Alignment.Center) {
                Icon(if (tab == 0) Ic.Edit else Ic.Plus, We.onPrimary, 26.dp)
            }
        }
        BottomNav(tab, chats.filter { it.ref.kind == Kind.DM }.sumOf { it.unread }, chats.filter { it.ref.kind == Kind.GROUP }.sumOf { it.unread })
    }
}

@Composable
fun BottomNav(tab: Int, dmUnread: Long, grpUnread: Long) {
    val items = listOf(Triple(Ic.Chat, "Chats", dmUnread), Triple(Ic.Group, "Groups", grpUnread), Triple(Ic.Mega, "Channels", 0L), Triple(Ic.User, "Me", 0L))
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
            SheetItem(Ic.Pin, if (c.pinned) "Unpin from top" else "Pin to top") { Repo.setPinned(c.ref.id, !c.pinned); menu = null }
            if (c.ref.kind != Kind.CHANNEL || true) SheetItem(if (c.muted) Ic.Bell else Ic.BellOff, if (c.muted) "Unmute" else "Mute") { Repo.setMuted(c.ref.id, !c.muted); menu = null }
            SheetItem(Ic.Trash, if (c.ref.kind == Kind.DM) "Delete chat" else "Remove from list", danger = true) { Repo.removeChat(c.ref.id); menu = null }
        }
    }
}

@Composable
fun titleOf(ref: ChatRef): Triple<String, String, String> {   // name, photo, seed
    return when (ref.kind) {
        Kind.DM -> { val u by Repo.user(ref.peer!!).collectAsState(); Triple(u?.name ?: "…", u?.photo ?: "", ref.peer) }
        Kind.GROUP -> { val g by Repo.group(ref.id).collectAsState(); Triple(g?.name ?: "Group", g?.photo ?: "", ref.id) }
        Kind.CHANNEL -> { val c by Repo.channel(ref.id).collectAsState(); Triple(c?.name ?: "Channel", c?.photo ?: "", ref.id) }
    }
}

fun previewText(type: String, text: String) = when (type) {
    "image" -> "Photo"; "voice" -> "Voice message"; "sticker" -> "Sticker"; else -> text
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(c: ChatItem, onClick: () -> Unit, onLong: () -> Unit) {
    val (name, photo, seed) = titleOf(c.ref)
    val preview by produceState("", c.lastBody, c.lastType) {
        value = if (c.lastBody.isEmpty()) "" else {
            val m = Repo.decrypt(c.ref, Repo.Raw("", c.lastFrom, c.lastTs, c.lastType, c.lastBody, 0, null, false, false, emptyMap()))
            if (m.unreadable) "Encrypted message" else previewText(m.type, m.text)
        }
    }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, photo, 52.dp, seed)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.ref.kind == Kind.GROUP) { Icon(Ic.Group, We.text2, 15.dp); Spacer(Modifier.width(4.dp)) }
                if (c.ref.kind == Kind.CHANNEL) { Icon(Ic.Mega, We.text2, 15.dp); Spacer(Modifier.width(4.dp)) }
                Text(name, color = We.text, style = T.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.pinned) { Icon(Ic.Pin, We.text2, 14.dp); Spacer(Modifier.width(6.dp)) }
                Text(timeShort(c.lastTs), color = if (c.unread > 0 && !c.muted) We.primary else We.text2, style = T.tiny)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val mine = c.lastFrom == Repo.uid
                Text((if (mine && preview.isNotEmpty()) "You: " else "") + preview, color = We.text2, style = T.body.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
            Row(Modifier.fillMaxWidth().combinedClickableNoRipple { Nav.push(REditProfile) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(me?.name ?: "", me?.photo ?: "", 72.dp, Repo.uid)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(me?.name ?: "", color = We.text, style = T.title)
                    Text("@" + (me?.username ?: ""), color = We.primary, style = T.body)
                    if (!me?.about.isNullOrEmpty()) Text(me!!.about, color = We.text2, style = T.small, maxLines = 2)
                }
                Icon(Ic.Chevron, We.text2, 20.dp)
            }
            Divider()
            SectionLabel("Account")
            Row2(Ic.Edit, "Edit profile", "Name, username, photo, about", onClick = { Nav.push(REditProfile) })
            Row2(Ic.Shield, "Privacy & security", "Last seen, read receipts, blocked users", onClick = { Nav.push(RPrivacy) })
            SectionLabel("App")
            Row2(Ic.Palette, "Appearance", when (ThemeMode.mode.intValue) { 1 -> "Light"; 2 -> "Dark"; else -> "Follow system" }, onClick = { Nav.push(RAppearance) })
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
