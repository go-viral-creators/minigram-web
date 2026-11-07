package com.kafisir.wetalks.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import com.kafisir.wetalks.ui.Text
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------------------------ in the chat
/**
 * Decides whether a picture in a GROUP or CHANNEL must be hidden behind "Sensitive content, tap to view".
 * [bmp] is the picture that is already decoded for display (or a video's preview picture). Private chats return false at once:
 * nothing is read, remembered, scanned or sent for them.
 */
@Composable
fun rememberNsfwHidden(ref: ChatRef, m: Msg, bmp: ImageBitmap?): Boolean {
    if (!NsfwLogic.kindAllowed(ref.kind)) return false
    NsfwPrefs.ensureLoaded()
    val setting by NsfwSettings.flow(ref.id).collectAsState()
    val ch by Repo.channel(ref.id).collectAsState()
    val g by Repo.group(ref.id).collectAsState()
    val me = Repo.uid
    val staff = if (ref.kind == Kind.GROUP) g?.members?.get(me) == "admin" else ch?.let { it.owner == me || me in it.admins } == true
    val on = setting?.let { NsfwLogic.protectionOn(ref.kind, ch?.priv == true, it.on) } == true && NsfwPrefs.blur
    val level = setting?.level ?: NsfwLevel.DEFAULT
    val marks = if (staff && on) NsfwAlerts.marked(ref.id).collectAsState().value else emptySet()
    var action by remember(m.id) { mutableStateOf(NsfwAction.NONE) }
    val usable = bmp != null && on && m.from != me && !m.pending && !m.deleted
    LaunchedEffect(m.id, usable, level) {
        if (!usable || bmp == null) { action = NsfwAction.NONE; return@LaunchedEffect }
        var scores = NsfwRuntime.cached(ref.id, m.id)
        if (scores == null) {
            val meta = NsfwMeta(m.type, m.mime, m.w, m.h, mine = m.from == me, pending = m.pending, deleted = m.deleted)
            val r = NsfwRuntime.scan(ref.kind, meta, level, bmp.asAndroidBitmap())
            if (r is NsfwResult.Done) { scores = r.scores; NsfwRuntime.remember(ref.id, m.id, r.scores) }
        }
        var act = if (scores != null) NsfwLogic.decide(scores, level) else NsfwAction.NONE
        if (act != NsfwAction.NONE && NsfwAlerts.cleared(ref.id, m.id)) act = NsfwAction.NONE       // an admin said "not sensitive"
        action = act
        if (act != NsfwAction.NONE && scores != null) NsfwAlerts.report(ref, m.id, NsfwLogic.bucket(scores))   // after showing, never before
    }
    val marked = on && m.id in marks
    return on && (action == NsfwAction.BLUR || marked) && !NsfwRuntime.isRevealed(ref.id, m.id)
}

/** A heavily shrunk copy of the picture, drawn big with smoothing: a blur that works on every Android version and shows no detail. */
private fun tiny(b: ImageBitmap): ImageBitmap? = try {
    val src = b.asAndroidBitmap()
    val w = 14; val h = (14f * src.height / src.width).toInt().coerceIn(6, 28)
    Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
} catch (_: Throwable) { null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SensitiveCover(bmp: ImageBitmap?, modifier: Modifier, compact: Boolean = false, onReveal: () -> Unit, onLong: () -> Unit = {}) {
    val small = remember(bmp) { bmp?.let { tiny(it) } }
    Box(modifier.background(Color(0xFF2A2F3A)).combinedClickable(onClick = onReveal, onLongClick = onLong), contentAlignment = Alignment.Center) {
        if (small != null) Image(small, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
        Box(Modifier.matchParentSize().background(Color(0x66000000)))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Icon(Ic.EyeOff, Color.White, if (compact) 22.dp else 30.dp)
            Text(if (compact) "Sensitive" else "Sensitive content, tap to view", color = Color.White, style = if (compact) T.tiny else T.small.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ------------------------------------------------------------------------------------------------ settings
@Composable
fun NsfwSettingsRows(id: String, kind: Kind) {
    if (!NsfwLogic.kindAllowed(kind)) return
    val scope = rememberCoroutineScope()
    val setting by NsfwSettings.flow(id).collectAsState()
    val ch by Repo.channel(id).collectAsState()
    val on = NsfwLogic.protectionOn(kind, ch?.priv == true, setting?.on)
    val lvl = setting?.level ?: NsfwLevel.DEFAULT
    fun save(o: Boolean, l: NsfwLevel) { scope.launch { try { NsfwSettings.save(id, o, l) } catch (e: Exception) { Nav.say(Repo.explain(e, "Saving this setting")) } } }
    SectionLabel("Sensitive images")
    Row2(Ic.Shield, "Protect from sensitive images", "Photos that may be sensitive are blurred for members who keep this on, and you get a warning to review them.", trailing = { Toggle(on) { save(it, lvl) } })
    if (on) {
        NsfwLevel.values().forEach { l ->
            Row(Modifier.fillMaxWidth().clickable { save(true, l) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(l.label, color = We.text, style = T.name); Text(l.sub, color = We.text2, style = T.small) }
                Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (lvl == l) We.primary else We.line), contentAlignment = Alignment.Center) { if (lvl == l) Icon(Ic.Check, We.onPrimary, 14.dp) }
            }
        }
    }
    Text(NsfwPrivacyText + if (!NsfwRuntime.available()) "\n\nThe image check is not included in this version of the app yet, so nothing is checked." else "",
        color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
}

const val NsfwPrivacyText = "The check runs on each person's own phone. No picture leaves the phone for it. Only a flag (a strength number and the message id) is sent to the admins, and only for groups and channels. Private chats are never checked."

/** The person's own switch, in Privacy & security. */
@Composable
fun NsfwLocalRow() {
    NsfwPrefs.ensureLoaded()
    Row2(Ic.EyeOff, "Blur sensitive images in groups and channels", "Checked on this phone only. Private chats are never checked.", trailing = { Toggle(NsfwPrefs.blur) { NsfwPrefs.setBlurOn(it) } })
}

// ------------------------------------------------------------------------------------------------ admin list (a tab of the Moderation screen)
@Composable
fun NsfwAlertsPane(id: String, kind: Kind) {
    val scope = rememberCoroutineScope()
    val list by remember(id) { NsfwAlerts.alerts(id) }.collectAsState(emptyList())
    val g by Repo.group(id).collectAsState()
    val c by Repo.channel(id).collectAsState()
    val owner = if (kind == Kind.GROUP) g?.owner else c?.owner
    val me = Repo.uid
    var confirm by remember { mutableStateOf<Triple<String, String, suspend () -> Unit>?>(null) }
    LaunchedEffect(list) { list.maxOfOrNull { it.ts }?.let { NsfwAlerts.markSeen(id, it) } }
    fun run(ok: String, what: String, block: suspend () -> Unit) { scope.launch { try { block(); Nav.say(ok) } catch (e: Exception) { Nav.say(Repo.explain(e, what)) } } }
    if (list.isEmpty()) { Empty(Ic.Shield, "Nothing flagged", "When a member's phone thinks a photo may be sensitive, it shows up here. " + NsfwPrivacyText); return }
    LazyColumn {
        items(list, key = { it.mid }) { a ->
            val sender by produceState<String?>(null, a.mid) { value = NsfwAlerts.senderOf(id, a.mid) }
            val su = sender
            val u by (if (su != null) Repo.user(su) else kotlinx.coroutines.flow.MutableStateFlow<User?>(null)).collectAsState()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(We.surface).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (a.bucket >= 85) "Very likely sensitive" else if (a.bucket >= 60) "Likely sensitive" else "May be sensitive", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Text(timeShort(a.ts), color = We.text2, style = T.tiny)
                }
                Text("Photo or video from " + (if (su == me) "you" else u?.name?.ifEmpty { null } ?: "…") + ". It is an automatic hint and can be wrong.", color = We.text2, style = T.small, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    NsfwAct("Open message") { Nav.openChatAt(ChatRef(id, kind), a.mid) }
                    NsfwAct("Not sensitive") { run("Marked as not sensitive", "Not sensitive") { NsfwAlerts.notSensitive(id, a.mid) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    NsfwAct("Remove message", true) { confirm = Triple("Remove this message?", "It is deleted for everybody in the chat.", { NsfwAlerts.removeMessage(ChatRef(id, kind), a.mid) }) }
                    if (su != null && su != me && su != owner) NsfwAct("Ban sender", true) {
                        confirm = Triple("Ban ${u?.name ?: "this person"}?", "They are removed and can't join again until you unban them.", {
                            if (kind == Kind.GROUP) Reports.banFromGroup(id, su) else Reports.banFromChannel(id, su)
                            NsfwAlerts.close(id, a.mid)
                        })
                    }
                }
            }
        }
    }
    confirm?.let { (title, sub, action) ->
        Sheet({ confirm = null }) {
            Text(title, color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
            Text(sub, color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            SheetItem(Ic.Trash, "Yes, do it", danger = true) { confirm = null; run("Done", "Moderation") { action() } }
        }
    }
}

@Composable
private fun NsfwAct(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(text, color = if (danger) We.danger else We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp))
}
