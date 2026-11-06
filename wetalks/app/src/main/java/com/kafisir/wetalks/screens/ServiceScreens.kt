package com.kafisir.wetalks.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch

/** When the person last opened the service inbox (everything newer counts as unread). */
object ServicePrefs {
    var seen by mutableLongStateOf(0L); private set
    fun load(c: Context) { seen = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).getLong("svc_seen", 0L) }
    fun markSeen(c: Context, ts: Long) { if (ts > seen) { seen = ts; c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).edit().putLong("svc_seen", ts).apply() } }
}

/** The row on top of the chat list. */
@Composable
fun ServicesRow() {
    val msgs by Repo.serviceMsgs.collectAsState()
    if (msgs.isEmpty()) return
    val unread = msgs.count { it.ts > ServicePrefs.seen }
    Row(Modifier.fillMaxWidth().clickable { Nav.push(RServices) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(We.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(Ic.Bell, We.primary, 26.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Service messages", color = We.text, style = T.name)
            val m = msgs.first()
            Text(m.appName + ": " + m.text.take(70), color = We.text2, style = T.small, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (unread > 0) Box(Modifier.clip(CircleShape).background(We.primary).padding(horizontal = 8.dp, vertical = 2.dp)) { Text(unread.toString(), color = We.onPrimary, style = T.tiny) }
    }
    Divider(start = 82.dp)
}

@Composable
fun ServiceInboxScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs by Repo.serviceMsgs.collectAsState()
    var blockAsk by remember { mutableStateOf<ServiceMsg?>(null) }
    var openLink by remember { mutableStateOf<ServiceMsg?>(null) }
    LaunchedEffect(msgs.firstOrNull()?.ts) { msgs.firstOrNull()?.let { ServicePrefs.markSeen(ctx, it.ts) }; Notifier.clearServices() }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Service messages", onBack = { Nav.pop() }, subtitle = "From apps you use. Never share a code.")
        if (msgs.isEmpty()) Empty(Ic.Bell, "Nothing here", "Codes and alerts from apps and websites that use WeTalks will show up here.")
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(msgs, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(We.surface).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.appName, "", 36.dp, m.appId)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.appName, color = We.text, style = T.name, maxLines = 1)
                            Text(if (m.verified) "Verified service" else "Unverified service", color = if (m.verified) We.ok else We.text2, style = T.tiny)
                        }
                        Text(timeShort(m.ts), color = We.text2, style = T.tiny)
                    }
                    if (m.title.isNotEmpty()) Text(m.title, color = We.text, style = T.name, modifier = Modifier.padding(top = 10.dp))
                    val code = if (m.otp) Regex("\\b\\d{4,8}\\b").find(m.text)?.value else null
                    if (code != null) {
                        Text(code.chunked(3).joinToString(" "), color = We.primary, style = T.h1.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp), modifier = Modifier.padding(top = 8.dp))
                    }
                    Text(m.text, color = if (code != null) We.text2 else We.text, style = T.body, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (code != null) Chip("Copy code") { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", code)); Nav.say("Code copied") }
                        if (m.link.isNotEmpty()) Chip("Open link") { openLink = m }
                        Spacer(Modifier.weight(1f))
                        Chip("Block") { blockAsk = m }
                        Chip("Delete") { scope.launch { try { Repo.deleteServiceMsgs(listOf(m.id)) } catch (_: Exception) { Nav.say("Could not delete") } } }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
    blockAsk?.let { m ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { blockAsk = null }, containerColor = We.surface,
            title = { Text("Block " + m.appName + "?", color = We.text, style = T.title) },
            text = { Text("This app won't be able to send you service messages. You can undo this in Privacy & security.", color = We.text2, style = T.body) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { blockAsk = null; scope.launch { try { Repo.setServiceBlocked(m.appId, true); Nav.say("Blocked " + m.appName) } catch (_: Exception) { Nav.say("Could not block") } } }) { Text("Block", color = We.danger) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { blockAsk = null }) { Text("Cancel", color = We.text2) } })
    }
    openLink?.let { m ->
        val host = try { Uri.parse(m.link).host ?: m.link } catch (_: Exception) { m.link }
        androidx.compose.material3.AlertDialog(onDismissRequest = { openLink = null }, containerColor = We.surface,
            title = { Text("Open this link?", color = We.text, style = T.title) },
            text = { Text("$host\n\nWeTalks does not control this website. Only continue if you trust " + m.appName + ".", color = We.text2, style = T.body) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { openLink = null; try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }) { Text("Open", color = We.primary) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { openLink = null }) { Text("Cancel", color = We.text2) } })
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(We.primary.copy(alpha = .12f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold))
    }
}
