@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kafisir.wetalks.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.ui.draw.rotate
import com.kafisir.wetalks.data.DataPrefs
import com.kafisir.wetalks.voice.VoiceFx
import com.kafisir.wetalks.voice.Premium
import androidx.compose.ui.graphics.Color
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------------------------------------ app lock
object AppLock {
    var enabled by mutableStateOf(false)
    var locked by mutableStateOf(false)
    private fun sp(c: Context) = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE)
    fun load(c: Context) { enabled = sp(c).getBoolean("lock", false); locked = enabled }
    fun set(c: Context, v: Boolean) { enabled = v; sp(c).edit().putBoolean("lock", v).apply() }
    fun available(c: Context) = BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    fun prompt(a: FragmentActivity, title: String, ok: () -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
        BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { ok() }
        }).authenticate(info)
    }
}

@Composable
fun LockOverlay() {
    val act = LocalContext.current as FragmentActivity
    LaunchedEffect(Unit) { AppLock.prompt(act, "Unlock WeTalks") { AppLock.locked = false } }
    Column(Modifier.fillMaxSize().background(We.bg).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Logo(84.dp); Spacer(Modifier.height(18.dp))
        Text("WeTalks is locked", color = We.text, style = T.title); Spacer(Modifier.height(18.dp))
        Box(Modifier.width(220.dp)) { PrimaryButton("Unlock", { AppLock.prompt(act, "Unlock WeTalks") { AppLock.locked = false } }) }
    }
}

@Composable
fun AppLockScreen() {
    val ctx = LocalContext.current; val act = ctx as FragmentActivity
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("App lock", onBack = { Nav.pop() })
        Row2(Ic.Fingerprint, "Lock WeTalks", "Ask for fingerprint, face or screen lock when you open the app", trailing = {
            Toggle(AppLock.enabled) { want ->
                if (!want) AppLock.prompt(act, "Turn off app lock") { AppLock.set(ctx, false) }
                else if (!AppLock.available(ctx)) Nav.say("Set a screen lock or fingerprint on your phone first")
                else AppLock.prompt(act, "Turn on app lock") { AppLock.set(ctx, true) }
            }
        })
        Text("When on, WeTalks locks every time you leave the app. Your chats stay encrypted either way.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
    }
}

// ------------------------------------------------------------------------------------------------ chat settings
@Composable
fun ChatSettingsScreen() {
    val ctx = LocalContext.current
    val scale by UiPrefs.textScale; val radius by UiPrefs.bubble; val wall by UiPrefs.wallpaper; val dim by UiPrefs.dim; val style by UiPrefs.bubbleStyle
    val blur by UiPrefs.blur; val ver by UiPrefs.customVer
    val hasCustom = remember(ver) { UiPrefs.dir?.let { java.io.File(it, "chat_bg.jpg").exists() } == true }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u: Uri? ->
        if (u != null) {
            val r = Pics.jpegFrom(ctx, u, 1440, 82)
            if (r != null && UiPrefs.dir != null) { Walls.saveCustom(UiPrefs.dir!!, r.first); UiPrefs.customVer.intValue++; UiPrefs.setWallpaper(ctx, Walls.CUSTOM) } else Nav.say("Could not use that picture")
        }
    }
    LaunchedEffect(Unit) { while (true) androidx.compose.runtime.withFrameNanos { Clock.t = (it / 1_000_000_000.0 % 3600.0).toFloat() } }
    val locked = { what: String -> Nav.say("$what is locked · coming soon with WeTalks Plus") }
    val vfxCut by VoiceFx.cut; val vfxAi by VoiceFx.ai; val vfxPolish by VoiceFx.polish; val linkPrev by UiPrefs.linkPreview; val compact by UiPrefs.compact
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Chat settings", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // live preview
            Box(Modifier.fillMaxWidth().height(170.dp).wallpaperFor(wall, dim, blur, ver)) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    val shapeR = RoundedCornerShape(radius.dp)
                    Text("Hello! How is the new design?", color = Bubbles.fgFor(style, false) ?: We.onRecv, style = T.body.copy(fontSize = (15 * scale).sp), modifier = Modifier.bubbleLook(false, shapeR).padding(12.dp, 8.dp))
                    Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                    Text("Looks great, love it", color = Bubbles.fgFor(style, true) ?: We.onSent, style = T.body.copy(fontSize = (15 * scale).sp), modifier = Modifier.align(Alignment.End).bubbleLook(true, shapeR).padding(12.dp, 8.dp))
                }
            }
            SectionLabel("Text size")
            Slider(scale, { UiPrefs.setTextScale(ctx, it) }, valueRange = .85f..1.4f, modifier = Modifier.padding(horizontal = 20.dp), colors = SliderDefaults.colors(thumbColor = We.primary, activeTrackColor = We.primary))
            SectionLabel("Bubble corners")
            Slider(radius.toFloat(), { UiPrefs.setBubble(ctx, it.toInt()) }, valueRange = 4f..24f, modifier = Modifier.padding(horizontal = 20.dp), colors = SliderDefaults.colors(thumbColor = We.primary, activeTrackColor = We.primary))
            Row2(Ic.Type, "Compact messages", "Less space between bubbles", trailing = { Toggle(compact) { UiPrefs.setCompact(ctx, it) } })

            SectionLabel("Bubble style")
            @Composable fun bubbleTile(i: Int, mod: Modifier) {
                val b = Bubbles.all[i]
                Column(mod.clip(RoundedCornerShape(14.dp)).clickable { if (b.locked) locked("${b.name} bubbles") else UiPrefs.setBubbleStyle(ctx, i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(14.dp)).background(We.field).border(2.dp, if (style == i) We.primary else Color.Transparent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Box(Modifier.bubbleLook(true, RoundedCornerShape(12.dp), i).size(66.dp, 32.dp), contentAlignment = Alignment.Center) { Text("Hi", color = Bubbles.fgFor(i, true) ?: We.onSent, style = T.small) }
                        if (b.locked) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(We.text.copy(alpha = .7f)), contentAlignment = Alignment.Center) { Icon(Ic.Lock, We.surface, 11.dp) }
                    }
                    Text(b.name + (if (Bubbles.animated(i)) " ▶" else ""), color = if (style == i) We.primary else We.text2, style = T.tiny, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                }
            }
            ExpandableGrid("Classic bubbles", (0 until 12).toList(), 6, 3) { i, m -> bubbleTile(i, m) }
            ExpandableGrid("Animated bubbles", (12 until Bubbles.all.size).toList(), 6, 3) { i, m -> bubbleTile(i, m) }

            SectionLabel("Chat background")
            @Composable fun wallTile(id: Int, mod: Modifier) {
                val locked0 = id >= 0 && Walls.isLocked(id)
                Column(mod, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(.74f).clip(RoundedCornerShape(14.dp)).then(if (id >= 0) Modifier.wallpaperFor(id, 0f, 0, ver) else Modifier.background(We.field))
                        .border(2.dp, if (wall == id) We.primary else We.line, RoundedCornerShape(14.dp))
                        .clickable { when { id == -1 -> pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)); locked0 -> locked("${Walls.nameOf(id)} background"); else -> UiPrefs.setWallpaper(ctx, id) } },
                        contentAlignment = Alignment.Center) {
                        if (id == -1) Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Ic.Image, We.primary, 26.dp); Text("My photo", color = We.primary, style = T.tiny) }
                        if (locked0) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(Color(0xB0000000)), contentAlignment = Alignment.Center) { Icon(Ic.Lock, Color.White, 11.dp) }
                        if (wall == id) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(We.primary), contentAlignment = Alignment.Center) { Icon(Ic.Check, We.onPrimary, 13.dp) }
                    }
                    Text(when (id) { -1 -> "Gallery"; Walls.CUSTOM -> "Mine"; else -> Walls.nameOf(id) + (if (id >= AWalls.BASE) " ▶" else "") }, color = if (wall == id) We.primary else We.text2, style = T.tiny, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
            ExpandableGrid("Classic backgrounds", buildList { add(-1); if (hasCustom) add(Walls.CUSTOM); addAll(Walls.all.indices) }, 7, 4) { id, m -> wallTile(id, m) }
            ExpandableGrid("Animated backgrounds", AWalls.all.indices.map { AWalls.BASE + it }, 8, 4) { id, m -> wallTile(id, m) }
            SectionLabel("Dim background")
            Slider(dim, { UiPrefs.setDim(ctx, it) }, valueRange = 0f..0.6f, modifier = Modifier.padding(horizontal = 20.dp), colors = SliderDefaults.colors(thumbColor = We.primary, activeTrackColor = We.primary))
            if (wall == Walls.CUSTOM) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp).clickable { locked("Blur") }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Lock, We.text2, 16.dp); Spacer(Modifier.width(8.dp)); Text("Blur my photo", color = We.text2, style = T.body, modifier = Modifier.weight(1f)); Text("Plus", color = We.primary, style = T.tiny)
                }
            }

            SectionLabel("Voice notes")
            if (VoiceFx.crashedBefore && Premium.unlocked) Row2(Ic.Info, "AI noise removal was switched off", "The app closed unexpectedly while it was running on this phone. Tap to try again.", tint = We.danger, onClick = { VoiceFx.retryAfterCrash(ctx) })
            Row2(Ic.Lock, "AI background-noise remover", "DeepFilterNet3 cleans noise and echo while you record · WeTalks Plus", onClick = { locked("AI noise remover") }, trailing = { Text("Plus", color = We.primary, style = T.tiny.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp)) })
            Row2(Ic.Lock, "Auto-cut silence", "Pauses longer than 0.3 s are shortened so voice notes are tighter · WeTalks Plus", onClick = { locked("Auto-cut silence") }, trailing = { Text("Plus", color = We.primary, style = T.tiny.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(We.primary.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp)) })
            Row2(Ic.Audio, "Voice enhance", "Clearer, evenly loud speech: speech EQ, level balancing and a peak limiter. Free.", trailing = { Toggle(vfxPolish) { VoiceFx.setPolish(ctx, it) } })
            SectionLabel("Links")
            Row2(Ic.Link, "Link previews", "Your phone fetches the page to build the preview card. Turn off to send plain links.", trailing = { Toggle(linkPrev) { UiPrefs.setLinkPreview(ctx, it) } })
            val md by UiPrefs.markdown
            Row2(Ic.Type, "Text formatting (Markdown)", "**bold**, *italic*, ~~strike~~, `code`, lists, tables and ``` code blocks ``` look formatted in chats", trailing = { Toggle(md) { UiPrefs.setMarkdown(ctx, it) } })
            var sfxOn by remember { mutableStateOf(com.kafisir.wetalks.ui.Sfx.enabled) }
            Row2(Ic.Speaker, "Sound effects", "Small sounds for sent / received messages, uploads, voice play, delete and the end of a call (silent when the phone is silent)", trailing = { Toggle(sfxOn) { sfxOn = it; com.kafisir.wetalks.ui.Sfx.set(ctx, it) } })
            SectionLabel("More with WeTalks Plus")
            listOf("A different background for every chat", "Animated backgrounds", "Blur & parallax effects").forEach { t ->
                Row2(Ic.Lock, t, "Coming soon", onClick = { locked(t) })
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}


// ------------------------------------------------------------------------------------------------ data & storage
@Composable
fun StorageScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val media by produceState(0L, tick) { value = withContext(Dispatchers.IO) { DataPrefs.diskSize() } }
    val other by produceState(0L, tick) { value = withContext(Dispatchers.IO) { DataPrefs.otherSize() } }
    fun mb(b: Long) = if (b < 1048576) "%.0f KB".format(b / 1024.0) else "%.1f MB".format(b / 1048576.0)
    @Composable fun chips(options: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, (n, on) -> Text(n, color = if (on) We.onPrimary else We.text, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) We.primary else We.field).clickable { pick(i) }.padding(horizontal = 16.dp, vertical = 9.dp)) }
        }
    }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Data & storage", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row2(Ic.Database, "Chat backup", "Back up your messages, restore them on a new phone", onClick = { Nav.push(com.kafisir.wetalks.ui.RBackup) })
            SectionLabel("Storage on this phone")
            // usage bar
            val total = (media + other).coerceAtLeast(1L)
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(We.field)) {
                Box(Modifier.weight(media.coerceAtLeast(1L).toFloat()).fillMaxHeight().background(We.primary)); Box(Modifier.weight(other.coerceAtLeast(1L).toFloat()).fillMaxHeight().background(We.primary.copy(alpha = .35f)))
            }
            Row2(Ic.Image, "Downloaded media", mb(media) + " · photos, GIFs and voice notes you opened", trailing = { Text("Clear", color = We.danger, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.clickable { DataPrefs.clearDisk(); tick++; Nav.say("Media cache cleared") }.padding(8.dp)) })
            Row2(Ic.Database, "Temporary files", mb(other) + " · recordings, previews, edits", trailing = { Text("Clear", color = We.danger, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.clickable { DataPrefs.clearOther(); tick++; Nav.say("Temporary files cleared") }.padding(8.dp)) })
            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Clear everything (" + mb(media + other) + ")", { DataPrefs.clearDisk(); DataPrefs.clearOther(); tick++; Nav.say("Storage cleared") }) }

            SectionLabel("Keep media for")
            val limits = listOf(100 to "100 MB", 250 to "250 MB", 500 to "500 MB", 1024 to "1 GB", 0 to "No limit")
            chips(limits.map { it.second to (DataPrefs.cacheLimitMb == it.first) }) { DataPrefs.setLimit(limits[it].first) }
            Text("When the limit is reached the oldest downloads are removed first. They download again when you open them.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            SectionLabel("Auto-delete downloads after")
            val days = listOf(0 to "Never", 7 to "7 days", 30 to "30 days", 90 to "90 days")
            chips(days.map { it.second to (DataPrefs.cleanDays == it.first) }) { DataPrefs.setClean(days[it].first) }

            SectionLabel("Auto-download photos & GIFs")
            val modes = listOf("always" to "Always", "wifi" to "Wi-Fi only", "never" to "Never")
            chips(modes.map { it.second to (DataPrefs.autoPhotos == it.first) }) { DataPrefs.setAuto(modes[it].first) }
            Text("Photos that don't download automatically show a tap-to-download tile.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Row2(Ic.Camera, "Data saver for sending", "Send photos smaller (about 900 px) so they upload faster on slow networks", trailing = { Toggle(DataPrefs.saver) { DataPrefs.updateSaver(it) } })

            SectionLabel("Data used by media")
            Row2(Ic.Download, "Downloaded", mb(DataPrefs.down))
            Row2(Ic.Share, "Uploaded", mb(DataPrefs.up))
            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Reset counters", { DataPrefs.resetUsage() }) }

            com.kafisir.wetalks.data.CrashLog.last(ctx)?.let { rep ->
                SectionLabel("Support")
                Row2(Ic.Info, "Last crash report", "Tap to share it so the problem can be fixed", onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, rep), "Share crash report")) })
            }
            Text("Messages and media live in your encrypted chats on the server. Clearing storage only removes copies kept on this phone.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
        }
    }
}

private fun scopeClear(ctx: Context) { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }

// ------------------------------------------------------------------------------------------------ contacts
class DeviceContact(val name: String, val number: String, val key: String)

@Composable
fun NewChatScreen() {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    var onApp by remember { mutableStateOf<List<Pair<DeviceContact, User>>>(emptyList()) }
    var invite by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(q) { if (q.trim().length >= 2) { busy = true; delay(350); results = try { Repo.searchUsers(q) } catch (e: Exception) { emptyList() }; busy = false } else results = emptyList() }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        loading = true
        val contacts = withContext(Dispatchers.IO) {
            val out = ArrayList<DeviceContact>()
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                while (c.moveToNext()) { val n = c.getString(0) ?: continue; val num = c.getString(1) ?: continue; Repo.phoneKey(num)?.let { out.add(DeviceContact(n, num, it)) } }
            }
            out.distinctBy { it.key }.take(600)
        }
        val map = try { Repo.lookupPhones(contacts.map { it.key }) } catch (e: Exception) { emptyMap() }
        val users = HashMap<String, User?>()
        map.values.distinct().forEach { id -> users[id] = try { Repo.userOnce(id) } catch (e: Exception) { null } }
        val on = contacts.mapNotNull { c -> map[c.key]?.let { id -> users[id]?.takeIf { it.uid != Repo.uid }?.let { c to it } } }
        onApp = on.distinctBy { it.second.uid }
        invite = contacts.filter { it.key !in map.keys }.distinctBy { it.name }
        loading = false
    }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("New chat", onBack = { Nav.pop() })
        Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(We.field).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, We.text2, 20.dp); Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search by name or @username", color = We.text2, style = T.body)
                androidx.compose.foundation.text.BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = T.body.copy(color = We.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(We.primary))
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (q.trim().length >= 2) {
                if (results.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.6f)) { Empty(Ic.Search, if (busy) "Searching…" else "No one found", "Try a different name or @username.") } }
                items(results, key = { it.uid }) { u -> UserRow(u, { Nav.pop(); Nav.push(RChat(Repo.dmRef(u.uid))) }) }
            } else {
                item {
                    Row2(Ic.Group, "New group", "Chat with several people", We.primary, { Nav.pop(); Nav.push(RNewGroup) })
                    Row2(Ic.Mega, "New channel", "Broadcast to followers", We.primary, { Nav.pop(); Nav.push(RNewChannel) })
                    Row2(Ic.Bookmark, "Saved Messages", "Notes to yourself", We.primary, { Nav.pop(); Nav.push(RChat(Repo.dmRef(Repo.uid))) })
                    Divider()
                }
                if (!granted) item {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(We.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) { Icon(Ic.Book, We.primary, 34.dp) }
                        Spacer(Modifier.height(12.dp)); Text("Find friends from your contacts", color = We.text, style = T.name)
                        Text("See who is already on WeTalks and invite the rest. Numbers are hashed on your phone before they are compared; nothing is stored.", color = We.text2, style = T.small, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                        PrimaryButton("Allow contacts", { perm.launch(Manifest.permission.READ_CONTACTS) })
                    }
                } else {
                    if (loading) item { Text("Looking for your friends on WeTalks…", color = We.text2, style = T.body, modifier = Modifier.padding(20.dp)) }
                    if (onApp.isNotEmpty()) { item { SectionLabel("Contacts on WeTalks") }
                        items(onApp, key = { it.second.uid }) { (c, u) ->
                            Row(Modifier.fillMaxWidth().clickable { Nav.pop(); Nav.push(RChat(Repo.dmRef(u.uid))) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(u.name, u.photo, 46.dp, u.uid); Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) { Text(c.name, color = We.text, style = T.name); Text("@${u.username}" + if (c.name != u.name) " · ${u.name}" else "", color = We.text2, style = T.small) }
                                Text("Message", color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                            }
                        } }
                    if (!loading && invite.isNotEmpty()) { item { SectionLabel("Invite to WeTalks") }
                        items(invite.take(200), key = { it.key }) { c ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(c.name, "", 42.dp, c.name); Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) { Text(c.name, color = We.text, style = T.name); Text(c.number, color = We.text2, style = T.small) }
                                Text("Invite", color = We.onPrimary, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(We.primary).clickable {
                                    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + c.number)).putExtra("sms_body", "Hey ${c.name.substringBefore(' ')}, I'm on WeTalks, a private messenger with end-to-end encrypted chats. Join me!")
                                    try { ctx.startActivity(i) } catch (e: Exception) { Nav.say("No messaging app found") }
                                }.padding(horizontal = 16.dp, vertical = 7.dp))
                            }
                        } }
                    if (!loading && onApp.isEmpty() && invite.isEmpty()) item { Text("No contacts found on this phone.", color = We.text2, style = T.body, modifier = Modifier.padding(20.dp)) }
                }
            }
        }
    }
}


/** A titled grid that shows the first [initial] items and expands with "Show more" (and back with "Show less"). */
@Composable
private fun ExpandableGrid(title: String, ids: List<Int>, initial: Int, columns: Int, item: @Composable (Int, Modifier) -> Unit) {
    var open by remember(title) { mutableStateOf(false) }
    val shown = if (open) ids else ids.take(initial)
    Text(title, color = We.text2, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp))
    shown.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { id -> item(id, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (ids.size > initial) Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(if (open) "Show less" else "Show more (${ids.size - initial})", color = We.primary, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        Spacer(Modifier.width(4.dp)); Icon(Ic.Chevron, We.primary, 16.dp, Modifier.rotate(if (open) -90f else 90f))
    }
}
