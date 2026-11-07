package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.DataPrefs
import com.kafisir.wetalks.data.MediaNet
import com.kafisir.wetalks.data.MediaPolicy
import com.kafisir.wetalks.data.MediaPolicy.Net
import com.kafisir.wetalks.data.MediaPolicy.Type
import com.kafisir.wetalks.ui.*

private fun typeIcon(t: Type) = when (t) { Type.PHOTO -> Ic.Image; Type.VOICE -> Ic.Mic; Type.AUDIO -> Ic.Audio; Type.VIDEO -> Ic.Video; Type.DOC -> Ic.File; Type.GIF -> Ic.Smile }
private fun typeHint(t: Type) = when (t) {
    Type.PHOTO -> "Pictures sent in chats"; Type.VOICE -> "Voice messages"; Type.AUDIO -> "Songs and sound files"
    Type.VIDEO -> "Video files"; Type.DOC -> "PDF, Office files, zip and more"; Type.GIF -> "GIFs and animated pictures" }

/** Me -> Data & storage -> Auto-download. Separate choices for Wi-Fi, mobile data and roaming. */
@Composable
fun AutoDownloadScreen() {
    val s = DataPrefs.autoRules
    val now = MediaNet.state
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Auto-download", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Choose what downloads by itself. Everything else shows a tap-to-download tile with its size. Tapping always works.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            Row2(Ic.Database, "Use less data", "On mobile data and roaming only voice messages download by themselves. On Wi-Fi big files stop at " + MediaPolicy.LESS_DATA_WIFI_MAX_MB + " MB.",
                trailing = { Toggle(s.useLessData) { DataPrefs.setAutoRules(s.copy(useLessData = it)) } })
            Text(when {
                now.net == Net.OFFLINE -> "Right now: no connection"
                now.systemDataSaver && now.net != Net.WIFI -> "Right now: " + now.net.name.lowercase().replaceFirstChar { it.uppercase() } + ". Android Data Saver is on, so only voice messages download by themselves."
                else -> "Right now: " + when (now.net) { Net.WIFI -> "Wi-Fi"; Net.MOBILE -> "mobile data"; Net.ROAMING -> "roaming"; Net.OFFLINE -> "" }
            }, color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

            for (n in listOf(Net.WIFI, Net.MOBILE, Net.ROAMING)) {
                val r = s.rule(n)
                SectionLabel(n.label + if (now.net == n) "  ·  now" else "")
                for (t in Type.values()) Row2(typeIcon(t), t.label, typeHint(t), trailing = { Toggle(r.has(t)) { DataPrefs.setAutoRules(s.withRule(n, r.with(t, it))) } })
                if (r.has(Type.VIDEO) || r.has(Type.AUDIO) || r.has(Type.DOC)) {
                    Text("Only up to this size (video, audio, documents)", color = We.text2, style = T.small, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mb in MediaPolicy.SIZE_CHOICES) {
                            val on = r.maxMb == mb
                            Text(MediaPolicy.sizeLabel(mb), color = if (on) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) We.primary else We.field).clickable { DataPrefs.setAutoRules(s.withRule(n, r.copy(maxMb = mb))) }.padding(horizontal = 16.dp, vertical = 9.dp))
                        }
                    }
                    if (r.maxMb > 0) Text("Files whose size is not known wait for a tap.", color = We.text2, style = T.tiny, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            Text("Photos you sent yourself and anything already on this phone are never held back. Stickers are tiny and always show. If you tap a download while roaming, you get a short warning first.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Reset to the default choices", { DataPrefs.setAutoRules(MediaPolicy.defaults()); Nav.say("Auto-download choices reset") }) }
            Spacer(Modifier.height(30.dp))
        }
    }
}
