@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UserRow(u: User, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(u.name, u.photo, 46.dp, u.uid); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(u.name, color = We.text, style = T.name); Text("@" + u.username, color = We.text2, style = T.small) }
        trailing?.invoke()
    }
}

@Composable
internal fun SearchBox(q: String, onChange: (String) -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth().padding(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(We.field).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.Search, We.text2, 20.dp); Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) Text(hint, color = We.text2, style = T.body)
            androidx.compose.foundation.text.BasicTextField(q, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = T.body.copy(color = We.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(We.primary))
        }
    }
}

@Composable
private fun PhotoPick(name: String, photo: String?, current: String, onPicked: (String) -> Unit, seed: String) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u: Uri? -> if (u != null) Pics.avatarB64(ctx, u)?.let(onPicked) }
    Box(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        Avatar(name.ifEmpty { "?" }, photo ?: current, 96.dp, seed)
        Box(Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(We.primary), contentAlignment = Alignment.Center) { Icon(Ic.Camera, We.onPrimary, 17.dp) }
    }
}

@Composable
fun NewGroupScreen() {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    val picked = remember { mutableStateMapOf<String, User>() }
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) { delay(350); results = try { Repo.searchUsers(q) } catch (e: Exception) { emptyList() } }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(if (step == 0) "Add members" else "New group", onBack = { if (step == 1) step = 0 else Nav.pop() })
        if (step == 0) {
            SearchBox(q, { q = it }, "Search people to add")
            if (picked.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScrollSimple(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                picked.values.forEach { u -> Column(Modifier.clickable { picked.remove(u.uid) }, horizontalAlignment = Alignment.CenterHorizontally) { Avatar(u.name, u.photo, 44.dp, u.uid); Text(u.name.take(8), color = We.text2, style = T.tiny) } }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.uid }) { u ->
                    UserRow(u, { if (picked.containsKey(u.uid)) picked.remove(u.uid) else picked[u.uid] = u }) {
                        if (picked.containsKey(u.uid)) Box(Modifier.size(24.dp).clip(CircleShape).background(We.primary), contentAlignment = Alignment.Center) { Icon(Ic.Check, We.onPrimary, 15.dp) }
                    }
                }
            }
            Box(Modifier.padding(16.dp).navigationBarsPadding()) { PrimaryButton("Next (${picked.size} selected)", { step = 1 }, enabled = picked.isNotEmpty()) }
        } else Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally)) { PhotoPick(name, photo, "", { photo = it }, "grp") }
            Spacer(Modifier.height(22.dp))
            Field(name, { name = it.take(60) }, "Group name", leading = Ic.Group)
            Spacer(Modifier.height(14.dp))
            Field(about, { about = it.take(140) }, "Description (optional)", leading = Ic.Info)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Ic.Lock, We.ok, 16.dp); Spacer(Modifier.width(8.dp)); Text("Group messages are end-to-end encrypted. ${picked.size + 1} members.", color = We.text2, style = T.small) }
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Create group", loading = busy, enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    try { val id = Repo.createGroup(name, about, photo ?: "", picked.keys.toList()); Nav.pop(); Nav.push(RChat(ChatRef(id, Kind.GROUP))) }
                    catch (e: Exception) { Nav.say(e.message ?: "Could not create group") }
                    busy = false
                }
            })
        }
    }
}

private fun Modifier.horizontalScrollSimple(): Modifier = this.then(Modifier.horizontalScroll(androidx.compose.foundation.ScrollState(0)))

@Composable
fun NewChannelScreen() {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    var free by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(handle) {
        free = null
        if (!Regex("^[a-z0-9_]{3,32}$").matches(handle.lowercase())) return@LaunchedEffect
        delay(400); free = try { Repo.handleFree(handle.lowercase()) } catch (e: Exception) { false }
    }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("New channel", onBack = { Nav.pop() })
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally)) { PhotoPick(name, photo, "", { photo = it }, "ch") }
            Spacer(Modifier.height(22.dp))
            Field(name, { name = it.take(60) }, "Channel name", leading = Ic.Mega)
            Spacer(Modifier.height(14.dp))
            Field(handle, { handle = it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(32) }, "Public link", prefix = "@",
                error = if (handle.isNotEmpty() && !Regex("^[a-z0-9_]{3,32}$").matches(handle.lowercase())) "3-32 letters, numbers or _" else if (free == false) "This link is taken" else null,
                trailing = { if (free == true) Icon(Ic.Check, We.ok, 20.dp) })
            Spacer(Modifier.height(14.dp))
            Field(about, { about = it.take(200) }, "Description", leading = Ic.Info)
            Spacer(Modifier.height(10.dp))
            Text("Channels are public: anyone can find and follow them, and only admins can post.", color = We.text2, style = T.small)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Create channel", loading = busy, enabled = name.isNotBlank() && free == true, onClick = {
                scope.launch {
                    busy = true
                    val (id, err) = Repo.createChannel(name, handle, about, photo ?: "")
                    busy = false
                    if (id == null) Nav.say(err ?: "Could not create channel") else { Nav.pop(); Nav.push(RChat(ChatRef(id, Kind.CHANNEL))) }
                }
            })
        }
    }
}

@Composable
fun DiscoverScreen() {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(q) { loading = true; delay(300); results = try { Repo.searchChannels(q) } catch (e: Exception) { emptyList() }; loading = false }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Discover channels", onBack = { Nav.pop() })
        SearchBox(q, { q = it }, "Search channels")
        if (results.isEmpty()) Box(Modifier.weight(1f)) { Empty(Ic.Mega, if (loading) "Loading…" else "No channels found", "Search by channel name, @handle or a channel link.") }
        else LazyColumn(Modifier.weight(1f)) {
            item { SectionLabel(if (q.isEmpty()) "Popular channels" else "Results") }
            items(results, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { Nav.push(RChannelInfo(c.id)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.name, c.photo, 50.dp, c.id); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(c.name, color = We.text, style = T.name); Text("@${c.handle} · ${c.subCount} followers", color = We.text2, style = T.small); if (c.about.isNotEmpty()) Text(c.about, color = We.text2, style = T.small, maxLines = 1) }
                }
                Divider(start = 80.dp)
            }
        }
    }
}

@Composable
fun AddMembersScreen(gid: String) {
    val g by Repo.group(gid).collectAsState()
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    val picked = remember { mutableStateMapOf<String, User>() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) { delay(350); results = try { Repo.searchUsers(q).filter { it.uid !in (g?.members?.keys ?: emptySet()) } } catch (e: Exception) { emptyList() } }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Add members", onBack = { Nav.pop() })
        SearchBox(q, { q = it }, "Search people")
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.uid }) { u -> UserRow(u, { if (picked.containsKey(u.uid)) picked.remove(u.uid) else picked[u.uid] = u }) { if (picked.containsKey(u.uid)) Icon(Ic.Check, We.primary, 22.dp) } }
        }
        Box(Modifier.padding(16.dp).navigationBarsPadding()) {
            PrimaryButton("Add ${picked.size}", loading = busy, enabled = picked.isNotEmpty(), onClick = {
                scope.launch { busy = true; try { Repo.addMembers(gid, picked.keys.toList()); Nav.pop() } catch (e: Exception) { Nav.say(e.message ?: "Could not add") }; busy = false }
            })
        }
    }
}

@Composable
fun EditNameScreen(title: String, name0: String, about0: String, photo0: String, seed: String, save: suspend (String, String, String?) -> Unit, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(name0) }
    var about by remember { mutableStateOf(about0) }
    var photo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(title, onBack = done)
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally)) { PhotoPick(name, photo, photo0, { photo = it }, seed) }
            Spacer(Modifier.height(22.dp))
            Field(name, { name = it.take(60) }, "Name", leading = Ic.User)
            Spacer(Modifier.height(14.dp))
            Field(about, { about = it.take(200) }, "Description", leading = Ic.Info)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Save", loading = busy, enabled = name.isNotBlank(), onClick = { scope.launch { busy = true; try { save(name, about, photo); done() } catch (e: Exception) { Nav.say("Could not save") }; busy = false } })
        }
    }
}
