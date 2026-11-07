package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import com.kafisir.wetalks.ui.Text
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------------------------ report sheet
/**
 * "Report" sheet for a person, message, chat, group or channel. Nothing is sent until the person presses the button.
 * [what] is the short name shown in the title ("this message", "Rahul", "this group").
 */
@Composable
fun ReportSheet(target: ReportTarget, what: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var note by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val person = ReportLogic.reportedPerson(target)
    val personUser = person?.let { Repo.user(it).collectAsState().value }
    val blocked by Repo.blockedUsers.collectAsState()
    val canBlock = person != null && person !in blocked
    val isMsg = target is ReportTarget.Message
    val copyTo = ReportLogic.adminCopyChat(target)
    Sheet({ if (!sending) onDismiss() }) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            Text("Report $what", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
            Text("Why are you reporting this?", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            ReportReason.values().forEach { r ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !sending) { reason = r }.padding(horizontal = 24.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(r.label, color = We.text, style = T.name); Text(r.hint, color = We.text2, style = T.small) }
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (reason == r) We.primary else We.line), contentAlignment = Alignment.Center) { if (reason == r) Icon(Ic.Check, We.onPrimary, 14.dp) }
                }
            }
            Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.field).border(1.dp, We.line, RoundedCornerShape(12.dp)).padding(14.dp)) {
                if (note.isEmpty()) Text("Add a note (optional)", color = We.text2, style = T.body)
                BasicTextField(note, { if (it.length <= ReportLogic.NOTE_MAX) note = it }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !sending,
                    textStyle = T.body.copy(color = We.text), cursorBrush = SolidColor(We.primary))
            }
            Text("${note.length}/${ReportLogic.NOTE_MAX}", color = We.text2, style = T.tiny, modifier = Modifier.padding(horizontal = 26.dp))
            if (canBlock) Row2(Ic.Block, "Also block ${personUser?.name ?: "this person"}", "They won't be able to message you", trailing = { Toggle(alsoBlock) { alsoBlock = it } })
            Text(
                buildString {
                    append("What is sent: ")
                    append(when (target) {
                        is ReportTarget.Message -> "your reason and note, who sent the message and a copy of this one message as text. Messages are end-to-end encrypted, so your phone opens it and sends that plain copy. Nothing else from the chat is sent."
                        is ReportTarget.Chat -> "your reason and note and who the chat is with. No messages are sent. Add the message itself by reporting it from its menu."
                        else -> "your reason and note and who or what you report."
                    })
                    if (copyTo != null) append(" The admins of this ${if (target is ReportTarget.Channel || (target is ReportTarget.Message && target.ref.kind == Kind.CHANNEL) || (target is ReportTarget.User && target.context?.kind == Kind.CHANNEL)) "channel" else "group"} can see it too.")
                    append(" Reports are manual: nothing is sent unless you press the button.")
                },
                color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (isMsg && (target as ReportTarget.Message).snapshot.isNotEmpty())
                Text("“" + target.snapshot.take(160) + (if (target.snapshot.length > 160) "…" else "") + "”", color = We.text, style = T.small,
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(We.field).padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            error?.let { Text(it, color = We.danger, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(if (alsoBlock && canBlock) "Report and block" else "Send report", {
                val r = reason ?: return@PrimaryButton
                sending = true; error = null
                scope.launch {
                    try { Reports.submit(target, r, note, alsoBlock && canBlock); Nav.say(if (alsoBlock && canBlock) "Reported and blocked. Thank you." else "Report sent. Thank you."); onDismiss() }
                    catch (e: ReportException) { error = e.message }
                    catch (e: Exception) { error = Repo.explain(e, "Report") }
                    sending = false
                }
            }, Modifier.padding(horizontal = 24.dp).fillMaxWidth(), enabled = reason != null && !sending, loading = sending)
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------ admin screen
@Composable
private fun Act(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(text, color = if (danger) We.danger else We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp))
}

@Composable
private fun NameOf(uid: String): String { val u by Repo.user(uid).collectAsState(); return if (uid == Repo.uid) "You" else u?.name?.ifEmpty { null } ?: "…" }

/** Reports, people and bans of one group or channel, for its admins. */
@Composable
fun ModerationScreen(id: String, kind: Kind) {
    val scope = rememberCoroutineScope()
    val g by Repo.group(id).collectAsState()
    val c by Repo.channel(id).collectAsState()
    val me = Repo.uid
    val owner = if (kind == Kind.GROUP) g?.owner else c?.owner
    val staff = if (kind == Kind.GROUP) g?.members?.get(me) == "admin" else (c?.owner == me || c?.admins?.contains(me) == true)
    val chatRef = ChatRef(id, kind)
    var tab by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Triple<String, String, suspend () -> Unit>?>(null) }
    val reports by remember(id) { Reports.adminReports(id) }.collectAsState(emptyList())
    val bans by produceState<List<BanEntry>>(emptyList(), id, tick, staff) { value = if (staff) try { Reports.bans(kind, id) } catch (_: Exception) { emptyList() } else emptyList() }
    val followers by produceState<List<String>>(emptyList(), id, tick, staff) { value = if (kind == Kind.CHANNEL && staff) try { Repo.subscribers(id) } catch (_: Exception) { emptyList() } else emptyList() }
    val people: List<String> = if (kind == Kind.GROUP) (g?.members?.keys?.toList() ?: emptyList()) else followers
    fun run(what: String, ok: String, block: suspend () -> Unit) { scope.launch { try { block(); tick++; Nav.say(ok) } catch (e: Exception) { Nav.say(Repo.explain(e, what)) } } }
    fun protectedUid(uid: String) = uid == me || uid == owner || (kind == Kind.CHANNEL && c?.admins?.contains(uid) == true && owner != me)

    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Moderation", onBack = { Nav.pop() }, subtitle = if (kind == Kind.GROUP) g?.name else c?.name)
        if (!staff) { Empty(Ic.Lock, "Admins only", "Only admins can see reports and manage people."); return@Column }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Reports" + if (reports.isNotEmpty()) " (${reports.size})" else "", if (kind == Kind.GROUP) "Members" else "Followers", "Banned" + if (bans.isNotEmpty()) " (${bans.size})" else "").forEachIndexed { i, t ->
                Text(t, color = if (tab == i) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (tab == i) We.primary else We.field).clickable { tab = i }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        when (tab) {
            0 -> if (reports.isEmpty()) Empty(Ic.Shield, "No reports", "When someone reports a message, person or this ${if (kind == Kind.GROUP) "group" else "channel"}, it shows up here.")
            else LazyColumn { items(reports, key = { it.rid }) { rp ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(We.surface).padding(14.dp)) {
                    val reason = ReportReason.values().firstOrNull { it.key == rp.reason }?.label ?: rp.reason
                    val what = when (rp.kind) { "message" -> "Message"; "user" -> "Person"; "group" -> "Group"; "channel" -> "Channel"; else -> rp.kind }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$what · $reason", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        Text(timeShort(rp.ts), color = We.text2, style = T.tiny)
                    }
                    Text("Reported by ${NameOf(rp.by)}" + if (rp.tid.isNotEmpty()) " · about ${NameOf(rp.tid)}" else "", color = We.text2, style = T.small, modifier = Modifier.padding(top = 4.dp))
                    if (rp.snap.isNotEmpty()) Text(rp.snap, color = We.text, style = T.body, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(We.field).padding(12.dp), maxLines = 8, overflow = TextOverflow.Ellipsis)
                    if (rp.note.isNotEmpty()) Text("Note: " + rp.note, color = We.text2, style = T.small, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (rp.kind == "message" && rp.mid.isNotEmpty()) Act("Remove message", true) {
                            confirm = Triple("Remove this message?", "It is deleted for everybody in the chat.", { Reports.removeMessage(chatRef, rp.mid); Reports.dismiss(id, rp.rid) })
                        }
                        if (rp.tid.isNotEmpty() && !protectedUid(rp.tid) && rp.tid in (if (kind == Kind.GROUP) g?.members?.keys ?: emptySet() else followers.toSet())) Act("Ban sender", true) {
                            confirm = Triple("Ban ${"this person"}?", "They are removed and can't join again until you unban them.", { banUser(kind, id, rp.tid); Reports.dismiss(id, rp.rid) })
                        }
                        Act("Dismiss") { run("Dismiss", "Report closed") { Reports.dismiss(id, rp.rid) } }
                    }
                }
            } }
            1 -> if (people.isEmpty()) Empty(Ic.Group, "No one here", "") else LazyColumn { items(people.filter { it != me }, key = { it }) { uid ->
                val u by Repo.user(uid).collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u?.name ?: "", u?.photo ?: "", 42.dp, uid); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u?.name ?: "…", color = We.text, style = T.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (uid == owner) "Owner" else if (kind == Kind.GROUP && g?.members?.get(uid) == "admin" || kind == Kind.CHANNEL && c?.admins?.contains(uid) == true) "Admin" else "@" + (u?.username ?: ""), color = We.text2, style = T.small)
                    }
                    if (!protectedUid(uid)) {
                        Act("Remove") { confirm = Triple("Remove ${u?.name ?: "this person"}?", if (kind == Kind.GROUP) "They leave the group and the group key is changed. They can be added again." else "They stop following. They can follow again.", { removeUser(kind, id, uid) }) }
                        Act("Ban", true) { confirm = Triple("Ban ${u?.name ?: "this person"}?", "They are removed and can't join again until you unban them.", { banUser(kind, id, uid) }) }
                    }
                }
            } }
            else -> if (bans.isEmpty()) Empty(Ic.Block, "No one is banned", "People you ban can't join again until you unban them.") else LazyColumn { items(bans, key = { it.uid }) { b ->
                val u by Repo.user(b.uid).collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u?.name ?: "", u?.photo ?: "", 42.dp, b.uid); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(u?.name ?: "…", color = We.text, style = T.name); Text("Banned " + timeShort(b.ts), color = We.text2, style = T.small) }
                    Act("Unban") { run("Unban", "Unbanned") { Reports.unban(kind, id, b.uid) } }
                }
            } }
        }
    }
    confirm?.let { (title, sub, action) ->
        Sheet({ confirm = null }) {
            Text(title, color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
            Text(sub, color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            SheetItem(Ic.Trash, "Yes, do it", danger = true) { confirm = null; run("Moderation", "Done") { action() } }
        }
    }
}

private suspend fun banUser(kind: Kind, id: String, uid: String) { if (kind == Kind.GROUP) Reports.banFromGroup(id, uid) else Reports.banFromChannel(id, uid) }
private suspend fun removeUser(kind: Kind, id: String, uid: String) { if (kind == Kind.GROUP) Repo.removeMember(id, uid) else Reports.removeFollower(id, uid) }

/** Lets a small composable deep inside a bubble (the scam banner) ask the chat screen to open the report sheet. */
object ReportBus {
    var pending by androidx.compose.runtime.mutableStateOf<Pair<ReportTarget, String>?>(null)
}
