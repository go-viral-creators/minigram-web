package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings and small caches for the scam warning. Everything here stays on the phone. */
object ScamPrefs {
    var enabled by mutableStateOf(true); private set
    private var loaded = false
    private fun sp() = Repo.app.getSharedPreferences("wt_scam", android.content.Context.MODE_PRIVATE)
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try { enabled = sp().getBoolean("warn", true) } catch (_: Throwable) { }
    }
    fun set(v: Boolean) { enabled = v; try { sp().edit().putBoolean("warn", v).apply() } catch (_: Throwable) { } }

    /** message keys whose banner the person closed (this session only) */
    val dismissed = mutableStateMapOf<String, Boolean>()

    private val cache = object : LinkedHashMap<String, ScamVerdict>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScamVerdict>?) = size > 600
    }
    fun cached(key: String): ScamVerdict? = synchronized(cache) { cache[key] }
    fun remember(key: String, v: ScamVerdict) { synchronized(cache) { cache[key] = v } }
}

private fun scannable(m: Msg): Boolean =
    m.from != Repo.uid && !m.deleted && !m.unreadable && !m.pending && m.text.length >= 10 && (m.type == "text" || m.type == "link" || m.type == "image")

/**
 * Warning shown under an incoming message that looks like a scam. It only warns: nothing is deleted, blocked, reported or sent
 * unless the person taps the button. [onReport] is connected by the report feature; [onBlock] blocks the sender.
 */
@Composable
fun ScamBannerFor(m: Msg, onReport: () -> Unit = {}, onBlock: () -> Unit = { Repo.block(m.from, true); Nav.say("Blocked") }) {
    LaunchedEffect(Unit) { ScamPrefs.ensureLoaded() }
    if (!ScamPrefs.enabled || !scannable(m)) return
    val key = m.id + ":" + m.text.hashCode()
    if (ScamPrefs.dismissed[key] == true) return
    val verdict by produceState(ScamPrefs.cached(key), key) {
        value = ScamPrefs.cached(key) ?: withContext(Dispatchers.Default) { ScamDetector.scan(m.text) }.also { ScamPrefs.remember(key, it) }
    }
    val v = verdict ?: return
    if (!v.show) return
    ScamBanner(v, onReport, onBlock) { ScamPrefs.dismissed[key] = true }
}

@Composable
fun ScamBanner(v: ScamVerdict, onReport: () -> Unit, onBlock: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.danger.copy(alpha = .12f)).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Ic.Alert, We.danger, 18.dp, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("This message may be a scam. Don't share your OTP, PIN or bank details.", color = We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold))
                when (v.lang) {
                    ScamLang.HINGLISH -> Text("Ye message scam ho sakta hai. OTP, PIN ya bank details kisi ko na batayein.", color = We.text2, style = T.tiny)
                    ScamLang.HINDI -> Text("यह संदेश धोखाधड़ी हो सकता है। ओटीपी, पिन या बैंक की जानकारी किसी को न बताएं।", color = We.text2, style = T.tiny)
                    else -> {}
                }
                if (v.reasons.isNotEmpty()) {
                    val why = v.reasons.joinToString(", ") { if (v.lang == ScamLang.HINGLISH) it.hinglish else if (v.lang == ScamLang.HINDI) it.hindi else it.en }
                    Text((if (v.lang == ScamLang.EN) "Why: " else if (v.lang == ScamLang.HINDI) "कारण: " else "Kyun: ") + why, color = We.text2, style = T.tiny, modifier = Modifier.padding(top = 2.dp))
                }
            }
            IconBtn(Ic.Close, We.text2, onDismiss, 18.dp, Modifier.padding(2.dp))
        }
        Row(Modifier.padding(start = 26.dp, top = 2.dp)) {
            Text("Report", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onReport() }.padding(horizontal = 10.dp, vertical = 6.dp))
            Text("Block", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBlock() }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}
