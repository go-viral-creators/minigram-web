package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch

private val slowChoices = listOf(0 to "Off", 10 to "10s", 30 to "30s", 60 to "1 min", 300 to "5 min", 900 to "15 min", 3600 to "1 hour")

@Composable
private fun SlowModeRow(current: Int, set: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        slowChoices.forEach { (s, n) ->
            Text(n, color = if (current == s) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (current == s) We.primary else We.field).clickable { set(s) }.padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
    Text("Each person must wait this long between messages. Admins are exempt.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

@Composable
private fun Choice(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = We.text, style = T.name); Text(sub, color = We.text2, style = T.small) }
        Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (on) We.primary else We.line), contentAlignment = Alignment.Center) { if (on) Icon(Ic.Check, We.onPrimary, 14.dp) }
    }
}

private fun fail(e: Exception) = Nav.say("Could not save: " + (e.message ?: "try again"))

/** What members may do in a group, slow mode, and who the admins are. */
@Composable
fun GroupSettingsScreen(id: String) {
    val g by Repo.group(id).collectAsState()
    val scope = rememberCoroutineScope()
    val group = g ?: run { Column(Modifier.fillMaxSize().background(We.bg)) { TopBar("Group settings", onBack = { Nav.pop() }) }; return }
    val admin = group.members[Repo.uid] == "admin"
    fun on(k: String, def: Boolean = true) = group.perms[k] ?: def
    fun toggle(k: String, v: Boolean) { scope.launch { try { Repo.setPerm(Kind.GROUP, id, k, v) } catch (e: Exception) { fail(e) } } }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Group settings", onBack = { Nav.pop() })
        if (!admin) { Empty(Ic.Lock, "Admins only", "Only group admins can change these settings."); return@Column }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Who can send messages")
            Row2(Ic.Chat, "Members can send", "Turn off so only admins can write", trailing = { Toggle(on("send")) { toggle("send", it) } })
            if (on("send")) {
                Row2(Ic.Text, "Text messages", null, trailing = { Toggle(on("text")) { toggle("text", it) } })
                Row2(Ic.Image, "Photos", null, trailing = { Toggle(on("image")) { toggle("image", it) } })
                Row2(Ic.Smile, "Stickers & GIFs", null, trailing = { Toggle(on("sticker")) { toggle("sticker", it) } })
                Row2(Ic.Mic, "Voice messages", null, trailing = { Toggle(on("voice")) { toggle("voice", it) } })
            }
            SectionLabel("Members can also")
            Row2(Ic.Pin, "Pin messages", "Let any member pin and unpin", trailing = { Toggle(on("pin", false)) { toggle("pin", it) } })
            SectionLabel("Slow mode")
            SlowModeRow(group.slow) { s -> scope.launch { try { Repo.setSlow(Kind.GROUP, id, s) } catch (e: Exception) { fail(e) } } }
            if (group.owner == Repo.uid) ExportSwitchRows(id, Kind.GROUP)
            SectionLabel("Group type")
            Row2(Ic.Lock, "Private group", "Only admins can add people. Messages are end-to-end encrypted.")
            SectionLabel("Admins")
            group.members.entries.sortedBy { if (it.value == "admin") 0 else 1 }.forEach { (uid, role) ->
                val u by Repo.user(uid).collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u?.name ?: "", u?.photo ?: "", 42.dp, uid); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(if (uid == Repo.uid) "You" else u?.name ?: "…", color = We.text, style = T.name); Text(if (uid == group.owner) "Owner" else "@" + (u?.username ?: ""), color = We.text2, style = T.small) }
                    if (uid != group.owner && uid != Repo.uid) Toggle(role == "admin") { v -> scope.launch { try { Repo.setAdmin(id, uid, v) } catch (e: Exception) { fail(e) } } }
                    else Text("Admin", color = We.primary, style = T.small)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** Channel type (public / private), who may post, what followers may send, slow mode, admins and the invite link. */
@Composable
fun ChannelSettingsScreen(id: String) {
    val c by Repo.channel(id).collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    val ch = c ?: run { Column(Modifier.fillMaxSize().background(We.bg)) { TopBar("Channel settings", onBack = { Nav.pop() }) }; return }
    val owner = ch.owner == Repo.uid
    fun on(k: String, def: Boolean = true) = ch.perms[k] ?: def
    fun toggle(k: String, v: Boolean) { scope.launch { try { Repo.setPerm(Kind.CHANNEL, id, k, v) } catch (e: Exception) { fail(e) } } }
    val link by produceState(Repo.Links.channel(ch.handle), ch.priv, ch.handle) { value = if (ch.priv && owner) try { Repo.inviteLink(id, ch.handle) } catch (_: Exception) { Repo.Links.channel(ch.handle) } else Repo.Links.channel(ch.handle) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Channel settings", onBack = { Nav.pop() })
        if (!owner) { Empty(Ic.Lock, "Owner only", "Only the channel owner can change these settings."); return@Column }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Channel type")
            Choice("Public", "Anyone can find it in search and join", !ch.priv) { scope.launch { try { Repo.setChannelPrivate(id, false) } catch (e: Exception) { fail(e) } } }
            Choice("Private", "Hidden from search. People join with the invite link", ch.priv) { scope.launch { try { Repo.setChannelPrivate(id, true) } catch (e: Exception) { fail(e) } } }
            Row2(Ic.Link, "Invite link", link, onClick = { shareText(ctx, "Join ${ch.name} on WeTalks: $link") })
            if (ch.priv) Row2(Ic.Lock, "Reset invite links", "Old links stop working", onClick = { scope.launch { try { Repo.revokeInvites(id); Nav.say("Invite links reset") } catch (e: Exception) { fail(e) } } })
            SectionLabel("Who can post")
            Choice("Only admins", "Followers can read and react", !on("followers", false)) { toggle("followers", false) }
            Choice("Followers can send messages", "Turns the channel into a discussion", on("followers", false)) { toggle("followers", true) }
            if (on("followers", false)) {
                SectionLabel("Followers may send")
                Row2(Ic.Text, "Text messages", null, trailing = { Toggle(on("text")) { toggle("text", it) } })
                Row2(Ic.Image, "Photos", null, trailing = { Toggle(on("image")) { toggle("image", it) } })
                Row2(Ic.Smile, "Stickers & GIFs", null, trailing = { Toggle(on("sticker")) { toggle("sticker", it) } })
                Row2(Ic.Mic, "Voice messages", null, trailing = { Toggle(on("voice")) { toggle("voice", it) } })
                SectionLabel("Slow mode")
                SlowModeRow(ch.slow) { s -> scope.launch { try { Repo.setSlow(Kind.CHANNEL, id, s) } catch (e: Exception) { fail(e) } } }
            }
            ExportSwitchRows(id, Kind.CHANNEL)
            SectionLabel("Admins")
            ch.admins.forEach { uid ->
                val u by Repo.user(uid).collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u?.name ?: "", u?.photo ?: "", 42.dp, uid); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(if (uid == Repo.uid) "You" else u?.name ?: "…", color = We.text, style = T.name); Text(if (uid == ch.owner) "Owner" else "Admin", color = We.text2, style = T.small) }
                    if (uid != ch.owner) Text("Remove", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { scope.launch { try { Repo.setChannelAdmin(id, uid, false) } catch (e: Exception) { fail(e) } } }.padding(8.dp))
                }
            }
            Row2(Ic.UserPlus, "Add admin", "Choose from your followers", tint = We.primary, onClick = { picking = true })
            Spacer(Modifier.height(30.dp))
        }
    }
    if (picking) Sheet({ picking = false }) {
        val subs by produceState<List<String>>(emptyList()) { value = try { Repo.subscribers(id) } catch (e: Exception) { emptyList() } }
        Text("Make admin", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        val candidates = subs.filter { it !in ch.admins }
        if (candidates.isEmpty()) Text("No followers to promote yet.", color = We.text2, style = T.body, modifier = Modifier.padding(24.dp))
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            candidates.forEach { uid ->
                val u by Repo.user(uid).collectAsState()
                Row(Modifier.fillMaxWidth().clickable { picking = false; scope.launch { try { Repo.setChannelAdmin(id, uid, true); Nav.say("${u?.name ?: "They"} is now an admin") } catch (e: Exception) { fail(e) } } }.padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u?.name ?: "", u?.photo ?: "", 42.dp, uid); Spacer(Modifier.width(12.dp)); Text(u?.name ?: "…", color = We.text, style = T.name)
                }
            }
        }
    }
}
