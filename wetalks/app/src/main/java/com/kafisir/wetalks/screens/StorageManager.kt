package com.kafisir.wetalks.screens

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

private const val LARGE = "\u0000large"

private fun typeColor(t: String) = when (t) {
    MediaKinds.PHOTO -> Color(0xFF7C5CFF); MediaKinds.GIF -> Color(0xFF22A7F0); MediaKinds.VIDEO -> Color(0xFFF59E0B); MediaKinds.VOICE -> Color(0xFF12B886)
    MediaKinds.AUDIO -> Color(0xFF8B5CF6); MediaKinds.DOC -> Color(0xFFFF3D71); "db" -> Color(0xFF64748B); else -> Color(0xFF94A3B8)
}
private fun typeIcon(t: String) = when (t) { MediaKinds.PHOTO -> Ic.Image; MediaKinds.GIF -> Ic.Smile; MediaKinds.VIDEO -> Ic.Video; MediaKinds.VOICE -> Ic.Mic; MediaKinds.AUDIO -> Ic.Audio; MediaKinds.DOC -> Ic.File; else -> Ic.Database }
private fun date(ms: Long) = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(ms))

/** The name of a chat from its id: a DM id is "uidA_uidB", groups and channels use push ids. */
@Composable
private fun chatName(chatId: String): String {
    if (chatId.isEmpty()) return "Not linked to a chat yet"
    val parts = chatId.split('_'); val me = Repo.uid
    if (parts.size == 2 && (parts[0] == me || parts[1] == me)) {
        val u by Repo.user(if (parts[0] == me) parts[1] else parts[0]).collectAsState()
        return u?.name?.takeIf { it.isNotEmpty() } ?: "Chat"
    }
    val g by Repo.group(chatId).collectAsState(); val c by Repo.channel(chatId).collectAsState()
    return g?.name?.takeIf { it.isNotEmpty() } ?: c?.name?.takeIf { it.isNotEmpty() } ?: "Group or channel"
}

private class Overview(val byType: Map<String, Long>, val chats: List<MediaLedger.ChatUse>, val other: Long, val db: Long, val free: Long, val device: Long, val files: Int) {
    val media get() = byType.filterKeys { it != MediaKinds.OTHER }.values.sum()
    val total get() = byType.values.sum() + other + db
}

private fun load(): Overview {
    MediaLedger.reconcile()                                     // read the folders again first, so every number is what is really on the phone
    val types = MediaLedger.byType()
    val st = try { StatFs(Environment.getDataDirectory().path) } catch (_: Exception) { null }
    return Overview(types.associate { it.type to it.bytes }, MediaLedger.byChat(), DataPrefs.otherSize(), MediaLedger.databaseBytes(), st?.availableBytes ?: 0L, st?.totalBytes ?: 0L, types.sumOf { it.files })
}

@Composable
private fun Confirm(title: String, text: String, ok: String, onOk: () -> Unit, onNo: () -> Unit) {
    AlertDialog(onDismissRequest = onNo, containerColor = We.surface,
        title = { Text(title, color = We.text, style = T.title) }, text = { Text(text, color = We.text2, style = T.body) },
        confirmButton = { TextButton(onClick = onOk) { Text(ok, color = We.danger, style = T.btn) } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancel", color = We.text2, style = T.btn) } })
}

/** Me -> Data & storage -> Manage storage: where the space goes, per chat, the biggest files, and clearing. Only local copies are removed. */
@Composable
fun StorageManagerScreen() {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<String?>(null) }           // null = overview, LARGE = big files, else a chat id
    var confirmAll by remember { mutableStateOf(false) }
    val data by produceState<Overview?>(null, tick) { value = withContext(Dispatchers.IO) { try { load() } catch (_: Exception) { null } } }
    androidx.activity.compose.BackHandler(enabled = view != null) { view = null }

    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(when (view) { null -> "Manage storage"; LARGE -> "Large files"; else -> "Chat media" }, onBack = { if (view != null) view = null else Nav.pop() })
        val d = data
        if (d == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = We.primary) }; return@Column }
        when (val v = view) {
            null -> OverviewPane(d, onChat = { view = it }, onLarge = { view = LARGE }, onClearAll = { confirmAll = true })
            LARGE -> LargeFiles(onChanged = { tick++ })
            else -> ChatMedia(v, onChanged = { tick++ }, onGone = { view = null })
        }
    }
    if (confirmAll) Confirm("Clear all cache?", "This removes photos, videos, voice messages, audio, documents and temporary files saved on this phone. Your messages stay, and so do the copies on the server. Anything you open again downloads again (if your auto-download choices allow it, otherwise tap it).", "Clear all", {
        confirmAll = false
        scope.launch { withContext(Dispatchers.IO) { MediaLedger.clearAllMedia(); DataPrefs.clearOther() }; tick++; Nav.say("Cache cleared") }
    }, { confirmAll = false })
}

@Composable
private fun OverviewPane(d: Overview, onChat: (String) -> Unit, onLarge: () -> Unit, onClearAll: () -> Unit) {
    val parts = MediaKinds.ALL.filter { it != MediaKinds.OTHER }.map { it to (d.byType[it] ?: 0L) } + listOf(MediaKinds.OTHER to ((d.byType[MediaKinds.OTHER] ?: 0L) + d.other), "db" to d.db)
    val shares = MediaKinds.shares(parts)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text(MediaKinds.bytes(d.total), color = We.text, style = T.h1)
                Text("used by WeTalks on this phone", color = We.text2, style = T.small)
                if (d.device > 0) Text(MediaKinds.bytes(d.free) + " free of " + MediaKinds.bytes(d.device) + " on this device", color = We.text2, style = T.small, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(We.field)) {
                    for ((t, f) in shares) Box(Modifier.weight(f.coerceAtLeast(0.004f)).fillMaxHeight().background(typeColor(t)))
                }
            }
        }
        items(parts, key = { it.first }) { (t, b) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(typeColor(t)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (t == "db") "Chat database" else if (t == MediaKinds.OTHER) "Other and temporary files" else MediaKinds.label(t), color = We.text, style = T.body)
                    if (t == "db") Text("Messages kept for offline use. Not removed by clearing.", color = We.text2, style = T.tiny)
                }
                Text(MediaKinds.bytes(b), color = We.text2, style = T.small)
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Row2(Ic.Select, "Large files", "The biggest downloads first, pick and delete", onClick = onLarge)
            Row2(Ic.Sliders, "Auto-download", "What downloads on Wi-Fi, mobile data and roaming", onClick = { Nav.push(RAutoDownload) })
            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Clear all cache (" + MediaKinds.bytes(d.media + (d.byType[MediaKinds.OTHER] ?: 0L) + d.other) + ")", onClearAll) }
            Text("Clearing only removes copies on this phone. Messages and the copies on the server are not touched.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            SectionLabel("Storage by chat")
            if (d.chats.isEmpty()) Text("No downloaded media yet.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
        }
        items(d.chats, key = { "c" + it.chatId }) { c ->
            val name = chatName(c.chatId)
            Row2(if (c.chatId.isEmpty()) Ic.Database else Ic.Chat, name, c.files.toString() + (if (c.files == 1) " file" else " files"), onClick = { onChat(c.chatId) },
                trailing = { Text(MediaKinds.bytes(c.bytes), color = We.text2, style = T.small) })
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

// ------------------------------------------------------------------------------------------------ one chat
@Composable
private fun ChatMedia(chatId: String, onChanged: () -> Unit, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf<Set<String>?>(null) }             // types waiting for a confirm; empty set = everything
    class Page(val types: List<MediaLedger.TypeUse>, val items: List<MediaLedger.Entry>)
    val page by produceState<Page?>(null, chatId, tick) { value = withContext(Dispatchers.IO) { Page(MediaLedger.chatByType(chatId).sortedByDescending { it.bytes }, MediaLedger.ofChat(chatId, null, 100)) } }
    val name = chatName(chatId)
    val p = page ?: run { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = We.primary) }; return }
    LaunchedEffect(p.types.isEmpty()) { if (p.types.isEmpty()) onGone() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(20.dp)) {
                Text(name, color = We.text, style = T.title)
                Text(MediaKinds.bytes(p.types.sumOf { it.bytes }) + " in " + p.types.sumOf { it.files } + " files on this phone", color = We.text2, style = T.small)
            }
            SectionLabel("By type")
        }
        items(p.types, key = { it.type }) { t ->
            Row2(typeIcon(t.type), MediaKinds.label(t.type), t.files.toString() + " files · " + MediaKinds.bytes(t.bytes),
                trailing = { Text("Clear", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable { ask = setOf(t.type) }.padding(8.dp)) })
        }
        item { Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { OutlineButton("Clear all media of this chat", { ask = emptySet() }) }; SectionLabel("Biggest first") }
        items(p.items, key = { "i" + it.mediaId }) { e -> ItemRow(e, showChat = false, selected = null, onTap = null) }
        item { Spacer(Modifier.height(30.dp)) }
    }
    ask?.let { types ->
        Confirm(if (types.isEmpty()) "Clear media of $name?" else "Clear " + MediaKinds.label(types.first()).lowercase() + " of $name?", "Only the copies on this phone are removed. The messages stay in the chat; tap a message to download it again.", "Clear", {
            ask = null
            scope.launch { val freed = withContext(Dispatchers.IO) { MediaLedger.clearChat(chatId, types.ifEmpty { null }) }; tick++; onChanged(); Nav.say("Freed " + MediaKinds.bytes(freed)) }
        }, { ask = null })
    }
}

@Composable
private fun ItemRow(e: MediaLedger.Entry, showChat: Boolean, selected: Boolean?, onTap: (() -> Unit)?) {
    val sub = (if (showChat) chatName(e.chatId) + " · " else "") + MediaKinds.label(e.type) + " · " + date(e.opened)
    Row2(typeIcon(e.type), MediaKinds.bytes(e.bytes), sub, onClick = onTap, trailing = {
        if (selected != null) Box(Modifier.size(24.dp).clip(CircleShape).background(if (selected) We.primary else We.field), contentAlignment = Alignment.Center) { if (selected) Icon(Ic.Check, We.onPrimary, 16.dp) }
    })
}

// ------------------------------------------------------------------------------------------------ biggest files, many at once
@Composable
private fun LargeFiles(onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<MediaLedger.Entry>() }
    val picked = remember { mutableStateMapOf<String, Long>() }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    suspend fun next() {
        loading = true
        val got = withContext(Dispatchers.IO) { MediaLedger.largest(100, items.size) }
        items.addAll(got); more = got.size == 100; loading = false
    }
    LaunchedEffect(Unit) { next() }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item { Text("The biggest items saved on this phone. Tap to pick, then delete the copies you don't need.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp)) }
            items(items, key = { it.mediaId }) { e -> ItemRow(e, showChat = true, selected = picked.containsKey(e.mediaId), onTap = { if (picked.containsKey(e.mediaId)) picked.remove(e.mediaId) else picked[e.mediaId] = e.bytes }) }
            item {
                if (loading) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = We.primary, strokeWidth = 2.dp) }
                else if (items.isEmpty()) Text("Nothing is saved on this phone.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
                else if (more) Box(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) { OutlineButton("Show more", { scope.launch { next() } }) }
            }
        }
        if (picked.isNotEmpty()) Box(Modifier.fillMaxWidth().background(We.surface).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            PrimaryButton("Delete " + picked.size + " (" + MediaKinds.bytes(picked.values.sum()) + ")", { confirm = true })
        }
    }
    if (confirm) Confirm("Delete " + picked.size + " items?", "Only the copies on this phone are removed. Messages and the server copies stay. Open a message later to download it again.", "Delete", {
        confirm = false
        val ids = picked.keys.toList()
        scope.launch {
            val freed = withContext(Dispatchers.IO) { MediaLedger.deleteItems(ids) }
            items.removeAll { it.mediaId in ids }; picked.clear(); onChanged(); Nav.say("Freed " + MediaKinds.bytes(freed))
        }
    }, { confirm = false })
}
