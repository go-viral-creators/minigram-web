package com.kafisir.wetalks.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.kafisir.wetalks.data.CallEntry
import com.kafisir.wetalks.data.CallLog
import com.kafisir.wetalks.data.CallPhase
import com.kafisir.wetalks.data.Calls
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** When the Calls tab was last looked at: missed calls newer than this show as a badge. */
object CallSeen {
    var seen by mutableLongStateOf(0L); private set
    private fun sp(c: Context) = c.getSharedPreferences("wt_calls", Context.MODE_PRIVATE)
    fun load(c: Context) { seen = sp(c).getLong("seen", 0L) }
    fun mark(c: Context) { seen = System.currentTimeMillis() + 5000; sp(c).edit().putLong("seen", seen).apply() }
}

private val GREEN = Color(0xFF22C55E)

/** Asks for mic (+camera), then rings whoever is chosen later (a row in the call list). */
@Composable
fun rememberAnyCallStarter(): (String, Boolean) -> Unit {
    val ctx = LocalContext.current
    var who by remember { mutableStateOf("") }
    var wantVideo by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val micOk = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = !wantVideo || ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!micOk || !camOk) Nav.say(if (!micOk) "Microphone permission is needed for calls" else "Camera permission is needed for video calls")
        else Repo.scope.launch { try { Calls.start(who, wantVideo) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Nav.say(e.message ?: "Could not start the call") } }
    }
    return { peer, v ->
        if (Calls.phase != CallPhase.IDLE) Nav.say("You're already in a call")
        else {
            who = peer; wantVideo = v
            launcher.launch(buildList { add(Manifest.permission.RECORD_AUDIO); if (v) add(Manifest.permission.CAMERA); if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray())
        }
    }
}

private fun arrowOf(e: CallEntry) = if (e.outgoing) "↗" else "↙"
@Composable private fun toneOf(e: CallEntry) = if (e.missed || (e.outgoing && e.st in setOf("noanswer", "unavailable"))) We.danger else GREEN

/** The Calls tab: history of every call, grouped, with an All / Missed filter and search. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by Repo.callLog.collectAsState()
    var missedOnly by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<CallLog.Group?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val start = rememberAnyCallStarter()
    LaunchedEffect(Unit) { Repo.watchCallLog() }
    LaunchedEffect(log.size) { CallSeen.mark(ctx) }
    val shown = CallLog.filter(log, missedOnly, query) { Repo.user(it).value?.name ?: "" }
    val groups = CallLog.group(shown)
    Column(Modifier.fillMaxSize().background(We.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable fun chip(t: String, on: Boolean, click: () -> Unit) = Text(t, color = if (on) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) We.primary else We.surface).clickable { click() }.padding(horizontal = 14.dp, vertical = 8.dp))
            chip("All", !missedOnly) { missedOnly = false }
            chip("Missed", missedOnly) { missedOnly = true }
            Spacer(Modifier.weight(1f))
            IconBtn(Ic.Search, We.text2, { searching = !searching; if (!searching) query = "" }, 22.dp)
            if (log.isNotEmpty()) IconBtn(Ic.Trash, We.text2, { confirmClear = true }, 22.dp)
        }
        if (searching) Box(Modifier.padding(horizontal = 12.dp)) { Field(query, { query = it }, "Search name", leading = Ic.Search) }
        if (groups.isEmpty()) Empty(Ic.Phone, if (log.isEmpty()) "No calls yet" else "Nothing found", if (log.isEmpty()) "Your voice and video calls will show up here, including calls you missed." else "Try another name or switch the filter.")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(groups, key = { it.first.id }) { g ->
                val e = g.first
                val u by Repo.user(e.peer).collectAsState()
                val name = u?.name?.ifBlank { null } ?: "…"
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Nav.push(RCallInfo(e.peer)) }, onLongClick = { menu = g }).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, u?.photo ?: "", 48.dp, e.peer)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name + if (g.count > 1) " (${g.count})" else "", color = if (e.missed) We.danger else We.text, style = T.name, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(arrowOf(e), color = toneOf(e), style = T.small.copy(fontWeight = FontWeight.Bold))
                            Spacer(Modifier.width(5.dp))
                            Text(CallLog.label(e) + " · " + timeShort(e.ts), color = We.text2, style = T.small, maxLines = 1)
                        }
                    }
                    IconBtn(if (e.video) Ic.Video else Ic.Phone, We.primary, { start(e.peer, e.video) }, 22.dp)
                }
                Divider(start = 78.dp)
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
    menu?.let { g ->
        Sheet({ menu = null }) {
            SheetItem(Ic.Phone, "Voice call") { start(g.first.peer, false); menu = null }
            SheetItem(Ic.Video, "Video call") { start(g.first.peer, true); menu = null }
            SheetItem(Ic.Info, "Call info") { Nav.push(RCallInfo(g.first.peer)); menu = null }
            SheetItem(Ic.Trash, "Delete from call log", danger = true) { val ids = g.all.map { it.id }; menu = null; scope.launch { try { Repo.deleteCallEntries(ids) } catch (e: Exception) { Nav.say(Repo.explain(e, "Deleting the call")) } } }
        }
    }
    if (confirmClear) androidx.compose.ui.window.Dialog(onDismissRequest = { confirmClear = false }) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(We.surface).padding(22.dp)) {
            Text("Clear call log?", color = We.text, style = T.title); Spacer(Modifier.height(8.dp))
            Text("All your call history is removed from this account. Chats are not touched.", color = We.text2, style = T.body)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                Text("Cancel", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { confirmClear = false }.padding(horizontal = 14.dp, vertical = 10.dp))
                Text("Clear", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { confirmClear = false; scope.launch { try { Repo.clearCallLog() } catch (e: Exception) { Nav.say(Repo.explain(e, "Clearing the call log")) } } }.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/** Everything about one person: call back, message, and every call with them. */
@Composable
fun CallInfoScreen(peer: String) {
    val scope = rememberCoroutineScope()
    val log by Repo.callLog.collectAsState()
    val u by Repo.user(peer).collectAsState()
    val name = u?.name?.ifBlank { null } ?: "…"
    val mine = log.filter { it.peer == peer }
    val start = rememberAnyCallStarter()
    val fmt = remember { java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Call info", onBack = { Nav.pop() })
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name, u?.photo ?: "", 64.dp, peer); Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).clickable { Nav.push(RProfile(peer)) }) { Text(name, color = We.text, style = T.title, maxLines = 1); Text("@" + (u?.username ?: ""), color = We.text2, style = T.small) }
                }
                Row2(Ic.Chat, "Message", null, onClick = { Nav.push(RChat(Repo.dmRef(peer))) })
                Row2(Ic.Phone, "Voice call", null, onClick = { start(peer, false) })
                Row2(Ic.Video, "Video call", null, onClick = { start(peer, true) })
                SectionLabel("Calls")
            }
            if (mine.isEmpty()) item { Text("No calls with this person.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp)) }
            items(mine, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(arrowOf(e), color = toneOf(e), style = T.body.copy(fontWeight = FontWeight.Bold)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text((if (e.video) "Video call" else "Voice call") + " · " + (if (e.outgoing) "Outgoing" else "Incoming"), color = if (e.missed) We.danger else We.text, style = T.body)
                        Text(fmt.format(java.util.Date(e.ts)), color = We.text2, style = T.small)
                    }
                    Text(CallLog.label(e), color = We.text2, style = T.small)
                }
                Divider(start = 52.dp)
            }
            if (mine.isNotEmpty()) item { Row2(Ic.Trash, "Delete call log", "Removes these calls from your history", tint = We.danger, onClick = { val ids = mine.map { it.id }; scope.launch { try { Repo.deleteCallEntries(ids); Nav.pop() } catch (e: Exception) { Nav.say(Repo.explain(e, "Deleting the calls")) } } }) }
        }
    }
}

/** Opened by the "Call back" button of a missed-call notification: asks for permission, rings, then gets out of the way. */
@Composable
fun CallBackScreen(uid: String, video: Boolean) {
    val start = rememberAnyCallStarter()
    LaunchedEffect(Unit) { start(uid, video) }
    LaunchedEffect(Calls.phase) { if (Calls.phase != CallPhase.IDLE && Nav.stack.lastOrNull() is RCallBack) Nav.pop() }
    LaunchedEffect(Unit) { delay(20_000); if (Nav.stack.lastOrNull() is RCallBack) Nav.pop() }
    Box(Modifier.fillMaxSize().background(We.bg), contentAlignment = Alignment.Center) { Text("Starting the call…", color = We.text2, style = T.body) }
}
