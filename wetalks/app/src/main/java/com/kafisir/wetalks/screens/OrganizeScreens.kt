package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.kafisir.wetalks.data.ChatItem
import com.kafisir.wetalks.data.ChatLabel
import com.kafisir.wetalks.data.ChatLock
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Kind
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch

/** The 12 label colours (saved as an index 0-11). */
private val LABEL_COLORS = listOf(0xFF1F6FEB, 0xFF2EA043, 0xFFD29922, 0xFFDA3633, 0xFF8957E5, 0xFFDB61A2, 0xFF0EA5A5, 0xFFE3702D, 0xFF6E7681, 0xFF57606A, 0xFF9A6700, 0xFF116329)
fun labelColor(i: Int) = Color(LABEL_COLORS[i.coerceIn(0, LABEL_COLORS.lastIndex)])

private fun tabKind(tab: Int) = when (tab) { 0 -> Kind.DM; 1 -> Kind.GROUP; else -> Kind.CHANNEL }

/** Locks / unlocks a chat on this phone. Needs the phone's fingerprint or screen lock. */
fun toggleChatLock(act: FragmentActivity, c: ChatItem) {
    val locked = ChatLock.isLocked(c.ref.id)
    if (!AppLock.available(act)) { Nav.say("Set a screen lock or fingerprint on your phone first"); return }
    AppLock.prompt(act, if (locked) "Unlock this chat" else "Lock this chat") {
        ChatLock.set(act, c.ref.id, !locked)
        Nav.say(if (locked) "Chat unlocked" else "Chat locked. Find it under Locked chats")
    }
}

/** Filter chips above the chat list: All + every label. */
@Composable
fun LabelBar(selected: String?, onSelect: (String?) -> Unit) {
    val labels by Repo.labels.collectAsState()
    if (labels.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable fun chip(text: String, color: Color?, on: Boolean, click: () -> Unit) {
            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) We.primary else We.surface).clickable { click() }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (color != null) { Box(Modifier.size(9.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)) }
                Text(text, color = if (on) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }
        chip("All", null, selected == null) { onSelect(null) }
        labels.forEach { l -> chip(l.name, labelColor(l.color), selected == l.id) { onSelect(if (selected == l.id) null else l.id) } }
    }
}

/** "Archived" and "Locked chats" rows shown at the top of a tab. */
@Composable
fun FolderRows(tab: Int, chats: List<ChatItem>) {
    val kind = tabKind(tab)
    val mine = chats.filter { it.ref.kind == kind }
    val archived = mine.filter { it.archived && !ChatLock.isLocked(it.ref.id) }
    val locked = mine.filter { ChatLock.isLocked(it.ref.id) }
    val ctx = LocalContext.current
    if (archived.isNotEmpty()) {
        val unread = archived.sumOf { it.unread }
        Row2(Ic.Archive, "Archived", archived.size.toString() + (if (unread > 0) " · $unread unread" else ""), onClick = { Nav.push(RFolder(false, tab)) })
    }
    if (locked.isNotEmpty()) {
        Row2(Ic.Lock, "Locked chats", locked.size.toString() + " · needs your fingerprint or screen lock", onClick = {
            val act = ctx as FragmentActivity
            if (ChatLock.folderOpen) Nav.push(RFolder(true, tab))
            else AppLock.prompt(act, "Open locked chats") { ChatLock.folderOpen = true; Nav.push(RFolder(true, tab)) }
        })
    }
}

/** Sheet that ticks / unticks labels for one chat. */
@Composable
fun LabelPickerSheet(c: ChatItem, onDismiss: () -> Unit) {
    val labels by Repo.labels.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    if (creating) { LabelEditor(null, { creating = false }) { name, color -> scope.launch { try { val id = Repo.createLabel(name, color); Repo.setChatLabel(c.ref.id, id, true) } catch (e: Exception) { Nav.say(Repo.explain(e, "Label save")) } } }; return }
    Sheet(onDismiss) {
        Text("Labels", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        if (labels.isEmpty()) Text("You have no labels yet.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        labels.forEach { l ->
            val on = l.id in c.labels
            Row(Modifier.fillMaxWidth().clickable { scope.launch { try { Repo.setChatLabel(c.ref.id, l.id, !on) } catch (e: Exception) { Nav.say(Repo.explain(e, "Label change")) } } }.padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(labelColor(l.color))); Spacer(Modifier.width(14.dp))
                Text(l.name, color = We.text, style = T.body, modifier = Modifier.weight(1f))
                if (on) Icon(Ic.Check, We.primary, 22.dp)
            }
        }
        SheetItem(Ic.Plus, "New label") { creating = true }
    }
}

/** Create / rename a label and pick its colour. [existing] null = new. */
@Composable
fun LabelEditor(existing: ChatLabel?, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: 0) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(We.surface).padding(22.dp)) {
            Text(if (existing == null) "New label" else "Edit label", color = We.text, style = T.title)
            Spacer(Modifier.height(12.dp))
            Field(name, { name = it.take(24) }, "Name")
            Spacer(Modifier.height(14.dp))
            for (rowStart in listOf(0, 6)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (i in rowStart until rowStart + 6) Box(Modifier.size(36.dp).clip(CircleShape).background(labelColor(i)).clickable { color = i }, contentAlignment = Alignment.Center) { if (color == i) Icon(Ic.Check, Color.White, 20.dp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                Text("Cancel", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onDismiss() }.padding(horizontal = 14.dp, vertical = 10.dp))
                Text("Save", color = if (name.isBlank()) We.text2 else We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (name.isNotBlank()) { onSave(name.trim(), color); onDismiss() } }.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/** Manage labels: add, rename, recolour, delete. */
@Composable
fun LabelsScreen() {
    val labels by Repo.labels.collectAsState()
    val chats by remember { Repo.chats() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<ChatLabel?>(null) }
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<ChatLabel?>(null) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Labels", onBack = { Nav.pop() }, actions = { IconBtn(Ic.Plus, We.onAppBar, { creating = true }) })
        if (labels.isEmpty()) Empty(Ic.Tag, "No labels yet", "Make labels like Work or Family, then add them to a chat from its long-press menu. They show as filters above your chats.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(labels, key = { it.id }) { l ->
                val n = chats.count { l.id in it.labels }
                Row(Modifier.fillMaxWidth().clickable { edit = l }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(labelColor(l.color))); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(l.name, color = We.text, style = T.body); Text("$n chat" + if (n == 1) "" else "s", color = We.text2, style = T.small) }
                    IconBtn(Ic.Trash, We.danger, { del = l }, 20.dp)
                }
                Divider(start = 50.dp)
            }
        }
    }
    if (creating) LabelEditor(null, { creating = false }) { n, c -> scope.launch { try { Repo.createLabel(n, c) } catch (e: Exception) { Nav.say(Repo.explain(e, "Label save")) } } }
    edit?.let { l -> LabelEditor(l, { edit = null }) { n, c -> scope.launch { try { Repo.editLabel(l.id, n, c) } catch (e: Exception) { Nav.say(Repo.explain(e, "Label save")) } } } }
    del?.let { l ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { del = null }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(We.surface).padding(22.dp)) {
                Text("Delete label", color = We.text, style = T.title); Spacer(Modifier.height(8.dp))
                Text("\"${l.name}\" is removed from all chats. The chats themselves are not touched.", color = We.text2, style = T.body)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    Text("Cancel", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { del = null }.padding(horizontal = 14.dp, vertical = 10.dp))
                    Text("Delete", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { scope.launch { try { Repo.deleteLabel(l.id) } catch (e: Exception) { Nav.say(Repo.explain(e, "Label delete")) } }; del = null }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** The Archived / Locked chats list for one tab. */
@Composable
fun FolderScreen(locked: Boolean, tab: Int) {
    val ctx = LocalContext.current
    val chats by remember { Repo.chats() }.collectAsState(initial = emptyList())
    val kind = tabKind(tab)
    if (locked && !ChatLock.folderOpen) {
        val act = ctx as FragmentActivity
        LaunchedEffect(Unit) { AppLock.prompt(act, "Open locked chats") { ChatLock.folderOpen = true } }
        Column(Modifier.fillMaxSize().background(We.bg)) {
            TopBar("Locked chats", onBack = { Nav.pop() })
            Empty(Ic.Lock, "Locked", "Use your fingerprint or screen lock to open these chats.", action = { PrimaryButton("Unlock", { AppLock.prompt(act, "Open locked chats") { ChatLock.folderOpen = true } }) })
        }
        return
    }
    val items = chats.filter { it.ref.kind == kind && (if (locked) ChatLock.isLocked(it.ref.id) else (it.archived && !ChatLock.isLocked(it.ref.id))) }.sortedByDescending { it.lastTs }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(if (locked) "Locked chats" else "Archived", onBack = { Nav.pop() })
        Box(Modifier.weight(1f)) {
            ChatList(items, if (locked) Ic.Lock else Ic.Archive, if (locked) "No locked chats" else "No archived chats", if (locked) "Lock a chat from its long-press menu." else "Archive a chat from its long-press menu to tidy your list.")
        }
    }
}

/** Shown instead of a locked chat until the phone's lock is passed. */
@Composable
fun ChatLockGate(ref: ChatRef) {
    val act = LocalContext.current as FragmentActivity
    fun ask() = AppLock.prompt(act, "Open locked chat") { ChatLock.markOpen(ref.id) }
    LaunchedEffect(ref.id) { ask() }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Locked chat", onBack = { Nav.pop() })
        Empty(Ic.Lock, "This chat is locked", "Use your fingerprint or screen lock to open it.", action = { PrimaryButton("Unlock", { ask() }) })
    }
}

/** Shown when the server refused a write the app needs: the database rules on the server are older than this app. */
@Composable
fun RulesBanner() {
    val outdated by Repo.rulesOutdated.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    if (!outdated || hidden) return
    Row(Modifier.fillMaxWidth().background(We.surface).padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Alert, We.danger, 20.dp); Spacer(Modifier.width(10.dp))
        Text("Some new features (labels, linked phones, 2-step) cannot save yet: the server's database rules are older than this app. The owner has to deploy the latest rules.", color = We.text2, style = T.small, modifier = Modifier.weight(1f))
        IconBtn(Ic.Close, We.text2, { hidden = true }, 18.dp, Modifier.size(38.dp))
    }
    Divider()
}
