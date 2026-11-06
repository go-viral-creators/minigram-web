package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch

@Composable
fun PrivacyScreen() {
    val me by Repo.user(Repo.uid).collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var backupTick by remember { mutableIntStateOf(0) }
    val fp = remember { Crypto.b64(java.security.MessageDigest.getInstance("SHA-256").digest(Crypto.keys(Repo.app).pub)).take(16) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Privacy & security", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Who can see")
            val pv by Repo.privacy.collectAsState()
            fun label(v: String) = when (v) { "all" -> "Everybody"; "contacts" -> "My contacts"; else -> "Nobody" }
            Row2(Ic.Phone, "Phone number", label(pv["phone"] ?: "nobody"), onClick = { choose = Triple("phone", "Who can see my phone number", "nobody") })
            Row2(Ic.Clock, "Last seen", label(pv["lastSeen"] ?: "all"), onClick = { choose = Triple("lastSeen", "Who can see my last seen", "all") })
            Row2(Ic.Eye, "Online status", label(pv["online"] ?: "all"), onClick = { choose = Triple("online", "Who can see when I'm online", "all") })
            Row2(Ic.Search, "Find me by phone number", if ((pv["find"] ?: "all") == "nobody") "Nobody" else "Everybody", onClick = { choose = Triple("find", "Who can find me by my phone number", "all") })
            Row2(Ic.User, "Show profile on web links", "Name, photo and bio appear on your wetalks link page", trailing = { Toggle(me?.webProfile ?: true) { scope.launch { try { Repo.setWebProfile(it) } catch (_: Exception) { Nav.say("Could not save") } } } })
            Row2(Ic.Check2, "Read receipts", "Show blue ticks when you read messages", trailing = { Toggle(me?.readReceipts ?: true) { Repo.setPrivacy("readReceipts", it) } })
            SectionLabel("Key backup")
            val backedUp by produceState<Boolean?>(null, backupTick) { value = Repo.hasKeyBackup() }
            Row2(Ic.Key, "Back up encryption key", if (backedUp == true) "Backed up · tap to update the passphrase" else "Protects your old chats if you reinstall or change phone", onClick = { showBackup = true })
            SectionLabel("Blocking")
            Row2(Ic.Block, "Blocked users", "People you blocked can't reach you", onClick = { Nav.push(RBlocked) })
            SectionLabel("Encryption")
            Row2(Ic.Key, "Your key fingerprint", fp, onClick = null)
            Text("Private chats and groups are encrypted on your phone before they are sent. The private key never leaves this device, so a new phone starts with a new key and cannot read old chats. Channels are public broadcasts and are not encrypted.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 12.dp))
            SectionLabel("Danger zone")
            Row2(Ic.Trash, "Delete my account", "Removes your profile and chat list", tint = We.danger, onClick = { confirm = true })
            Spacer(Modifier.height(30.dp))
        }
    }
    choose?.let { (key, title, def) ->
        val cur = Repo.privacyOf(key, def)
        Sheet({ choose = null }) {
            Text(title, color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
            val opts = if (key == "find") listOf("all" to "Everybody", "nobody" to "Nobody") else listOf("all" to "Everybody", "contacts" to "My contacts", "nobody" to "Nobody")
            opts.forEach { (v, n) ->
                Row(Modifier.fillMaxWidth().clickable { choose = null; scope.launch { try { Repo.setPrivacyChoice(key, v) } catch (e: Exception) { Nav.say("Could not save") } } }.padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n, color = We.text, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.weight(1f)); if (cur == v) Icon(Ic.Check, We.primary, 22.dp)
                }
            }
            Text(if (key == "find") "When set to Nobody, friends can no longer discover you through their contacts." else "“My contacts” means people you have chatted with. You always see your own.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
    }
    if (showBackup) KeyBackupSheet({ showBackup = false }) { backupTick++ }
    if (confirm) Sheet({ confirm = false }) {
        Text("Delete your account?", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Text("This can't be undone. If sign-in was a long time ago you may be asked to sign in again first.", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        SheetItem(Ic.Trash, "Delete forever", danger = true) { confirm = false; scope.launch { try { Nav.reset(); Repo.deleteAccount() } catch (e: Exception) { Nav.say("Please sign in again, then retry") } } }
    }
}

@Composable
fun AppearanceScreen() {
    val ctx = LocalContext.current
    val mode by ThemeMode.mode
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Appearance", onBack = { Nav.pop() })
        SectionLabel("Accent colour")
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            UiPrefs.accents.forEachIndexed { i, (n, c) ->
                val col = androidx.compose.ui.graphics.Color(if (We.dark) c.second else c.first)
                Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col).clickable { UiPrefs.setAccent(ctx, i) }, contentAlignment = Alignment.Center) {
                    if (UiPrefs.accent.intValue == i) Icon(Ic.Check, androidx.compose.ui.graphics.Color.White, 22.dp)
                }
            }
        }
        SectionLabel("Theme")
        listOf(0 to "Follow system", 1 to "Light", 2 to "Dark").forEach { (m, label) ->
            Row(Modifier.fillMaxWidth().clickable { ThemeMode.set(ctx, m) }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (m == 2) Ic.Moon else Ic.Palette, We.text2, 24.dp); Spacer(Modifier.width(18.dp))
                Text(label, color = We.text, style = T.body, modifier = Modifier.weight(1f))
                if (mode == m) Icon(Ic.Check, We.primary, 22.dp)
            }
            Divider(start = 62.dp)
        }
    }
}

@Composable
fun BlockedScreen() {
    val blocked by Repo.blockedUsers.collectAsState()
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Blocked users", onBack = { Nav.pop() })
        if (blocked.isEmpty()) Empty(Ic.Block, "No blocked users", "People you block won't be able to message you.")
        else LazyColumn { items(blocked.toList(), key = { it }) { id ->
            val u by Repo.user(id).collectAsState()
            Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(u?.name ?: "", u?.photo ?: "", 44.dp, id); Spacer(Modifier.width(14.dp))
                Text(u?.name ?: "…", color = We.text, style = T.name, modifier = Modifier.weight(1f))
                Text("Unblock", color = We.primary, style = T.small.copy(fontSize = 14.sp), modifier = Modifier.clickable { Repo.block(id, false) }.padding(8.dp))
            }
        } }
    }
}

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("About", onBack = { Nav.pop() })
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(96.dp); Spacer(Modifier.height(14.dp))
            Text("WeTalks", color = We.text, style = T.h1); Text("Version 1.9.6", color = We.text2, style = T.body)
            Spacer(Modifier.height(20.dp))
            Text("Private messaging with groups and channels. Direct chats and groups are end-to-end encrypted with X25519 + AES-256-GCM; your keys stay on your phone.", color = We.text2, style = T.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** App language, the language messages are translated into, and the language spoken in voice notes (for transcripts). */
@Composable
fun LanguageScreen() {
    val ctx = LocalContext.current
    val cur = I18n.lang; val prog = I18n.progress
    var q by remember { mutableStateOf("") }
    var section by remember { mutableIntStateOf(0) }       // 0 app, 1 translate-to, 2 spoken
    val list = I18n.languages.filter { q.isBlank() || it.english.contains(q, true) || it.native.contains(q, true) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Language", onBack = { Nav.pop() })
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("App language", "Translate to", "Voice notes").forEachIndexed { i, n ->
                Text(n, color = if (section == i) We.onPrimary else We.text, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (section == i) We.primary else We.field).clickable { section = i }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        Text(when (section) { 0 -> "Menus and buttons are shown in this language. Messages people write are never changed."; 1 -> "Messages you translate are shown in this language. You can also pick another language from the message menu."; else -> "Pick the language people speak in voice notes so transcripts are accurate. 'Phone default' uses your phone's language." }, color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (prog != null) Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("Translating the app… ${(prog * 100).toInt()}%", color = We.primary, style = T.small)
            androidx.compose.material3.LinearProgressIndicator(progress = { prog }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = We.primary, trackColor = We.line)
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.field).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, We.text2, 18.dp); Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search languages", color = We.text2, style = T.body)
                androidx.compose.foundation.text.BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = T.body.copy(color = We.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(We.primary))
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (section == 2) item { LangRow("Phone default", "", I18n.speechLang.isEmpty()) { I18n.setSpeechLang(ctx, "") } }
            items(list, key = { it.code }) { l ->
                val on = when (section) { 0 -> cur == l.code; 1 -> I18n.translateTo == l.code; else -> I18n.speechLang == l.code }
                LangRow(l.native, l.english, on) { when (section) { 0 -> I18n.set(ctx, l.code); 1 -> I18n.setTranslateTo(ctx, l.code); else -> I18n.setSpeechLang(ctx, l.code) } }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}

@Composable
private fun LangRow(native: String, english: String, on: Boolean, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(native, color = We.text, style = T.name.copy(fontSize = 16.sp)); if (english.isNotEmpty() && english != native) Text(english, color = We.text2, style = T.small) }
        if (on) Icon(Ic.Check, We.primary, 22.dp)
    }
    Divider(start = 20.dp)
}


/** Shown when this phone has a different key than the one the account used before and a backup exists. */
@Composable
fun RestoreKeyScreen() {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(We.bg).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Box(Modifier.size(84.dp).clip(androidx.compose.foundation.shape.CircleShape).background(We.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Icon(Ic.Key, We.primary, 40.dp) }
        Spacer(Modifier.height(20.dp))
        Text("Restore your encryption key", color = We.text, style = T.title, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("This phone has a new key, so your earlier chats can't be opened yet. Enter the passphrase you used for the backup to unlock them.", color = We.text2, style = T.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
        Field(pass, { pass = it; err = null }, "Backup passphrase", password = true, error = err)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Restore", loading = busy, enabled = pass.length >= 4, onClick = { scope.launch { busy = true; val ok = try { Repo.restoreKey(pass) } catch (e: Exception) { false }; busy = false; if (!ok) err = "Wrong passphrase" } })
        Spacer(Modifier.height(10.dp))
        Text("Skip and start fresh", color = We.danger, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { scope.launch { try { Repo.keepNewKey() } catch (_: Exception) {} } }.padding(14.dp))
        Text("Old chats will stay unreadable on this phone if you skip.", color = We.text2, style = T.small)
    }
}


/** Passphrase dialog that locks the private key and stores it, so chats survive a reinstall or a new phone. */
@Composable
fun KeyBackupSheet(onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Sheet(onDismiss) {
        var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
        Text("Back up encryption key", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Text("Chats are locked with a key that lives only on this phone. If you reinstall the app, clear its data or switch phones, that key is gone and old messages can never be opened. Lock a copy with a passphrase now. Nobody, including us, can read it without the passphrase, and if you forget the passphrase the backup cannot be opened.", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp))
        Column(Modifier.padding(24.dp)) {
            Field(p1, { p1 = it }, "Passphrase (8+ characters)", password = true)
            Spacer(Modifier.height(12.dp)); Field(p2, { p2 = it }, "Repeat passphrase", password = true, error = if (p2.isNotEmpty() && p1 != p2) "Does not match" else null)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Save backup", loading = busy, enabled = p1.length >= 8 && p1 == p2, onClick = { scope.launch { busy = true; try { Repo.backupKey(p1); onDone(); onDismiss(); Nav.say("Encryption key backed up") } catch (e: Exception) { Nav.say("Could not back up: " + (e.message ?: "")) }; busy = false } })
        }
    }
}
