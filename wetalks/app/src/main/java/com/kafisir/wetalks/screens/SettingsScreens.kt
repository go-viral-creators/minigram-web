package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
    val fp = remember { Crypto.b64(java.security.MessageDigest.getInstance("SHA-256").digest(Crypto.keys(Repo.app).pub)).take(16) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Privacy & security", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Who can see")
            Row2(Ic.Eye, "Last seen & online", "Others can see when you were last active", trailing = { Toggle(me?.showLastSeen ?: true) { Repo.setPrivacy("showLastSeen", it) } })
            Row2(Ic.Check2, "Read receipts", "Show blue ticks when you read messages", trailing = { Toggle(me?.readReceipts ?: true) { Repo.setPrivacy("readReceipts", it) } })
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
            Text("WeTalks", color = We.text, style = T.h1); Text("Version 1.4.0", color = We.text2, style = T.body)
            Spacer(Modifier.height(20.dp))
            Text("Private messaging with groups and channels. Direct chats and groups are end-to-end encrypted with X25519 + AES-256-GCM; your keys stay on your phone.", color = We.text2, style = T.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
