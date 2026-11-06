package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.ui.*
import com.kafisir.wetalks.voice.NeuralTts
import com.kafisir.wetalks.voice.Speaker

/** Settings → Natural voices: download, turn on/off, pick the voice, and try it. */
@Composable
fun NaturalVoicesScreen() {
    val ctx = LocalContext.current
    val st = NeuralTts.status
    var testing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Natural voices", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp)) {
                Text("Human-like reading aloud", color = We.text, style = T.title)
                Text("Long-press a message and tap Listen to hear it in a natural voice. The voice runs on your phone, so your messages never leave it. By default WeTalks keeps using your phone's normal voice until you turn this on.", color = We.text2, style = T.body, modifier = Modifier.padding(top = 6.dp))
            }
            SectionLabel("Voice pack")
            when (st) {
                NeuralTts.Status.Ready -> {
                    Row2(Ic.Check, "Installed", "About " + (NeuralTts.packBytes * 133 / 100 / 1048576) + " MB on your phone", trailing = { SmallBtn("Delete") { NeuralTts.remove(ctx) } })
                    Row2(Ic.Speaker, "Use natural voices", "Listen uses this voice for English, Hindi, Hinglish and 28 more languages", trailing = { Toggle(NeuralTts.enabled) { NeuralTts.setEnabled(ctx, it) } })
                    SectionLabel("Voice")
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (k in 0..4) { val v = row * 5 + k; Pick(NeuralTts.voiceNames[v].replace("Female ", "F").replace("Male ", "M"), NeuralTts.voice == v) { NeuralTts.setVoice(ctx, v) } }
                        }
                        Text("F1–F5 are female voices, M1–M5 are male voices. Tap Try it to hear the one you picked.", color = We.text2, style = T.tiny)
                    }
                    Column(Modifier.padding(16.dp)) {
                        Text("Try it", color = We.text, style = T.name)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SmallBtn(if (testing) "Stop" else "English") { testNow(ctx, "Hello! This is the natural voice in WeTalks. I can read your messages aloud in a clear, friendly way.", "t-en") { testing = it } }
                            SmallBtn("हिन्दी") { testNow(ctx, "नमस्ते! मैं आपके मैसेज पढ़कर सुना सकती हूँ। आज का मौसम बहुत अच्छा है।", "t-hi") { testing = it } }
                            SmallBtn("Hinglish") { testNow(ctx, "ab tum dekhna bhai, main apne application ke andar aise feature dal dunga.", "t-hg") { testing = it } }
                        }
                    }
                }
                NeuralTts.Status.Downloading -> Column(Modifier.padding(16.dp)) {
                    Text("Downloading… " + (NeuralTts.progress * 100).toInt() + "%", color = We.text, style = T.name)
                    androidx.compose.material3.LinearProgressIndicator(progress = { NeuralTts.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(6.dp)), color = We.primary, trackColor = We.line)
                    Text("Keep the app open. If the connection drops it continues where it stopped.", color = We.text2, style = T.small, modifier = Modifier.padding(top = 8.dp))
                    SmallBtn("Cancel", Modifier.padding(top = 10.dp)) { NeuralTts.cancelDownload() }
                }
                NeuralTts.Status.TooWeak -> Row2(Ic.Info, "Not supported on this phone", "Natural voices need a phone with about 3 GB of memory or more. Your phone's normal voice keeps working.")
                NeuralTts.Status.Broken -> Row2(Ic.Alert, "Switched off", "The app closed unexpectedly the last time a natural voice was used, so it was turned off for safety. Tap to try again.", onClick = { NeuralTts.resetBroken(ctx) })
                else -> Column(Modifier.padding(16.dp)) {
                    Text("Download once (about " + (NeuralTts.packBytes / 1048576) + " MB)", color = We.text, style = T.name)
                    Text("Use Wi-Fi. The voice stays on your phone and you can delete it any time. It reads 31 languages including English, Hindi and Hinglish. Other languages use your phone's voice.", color = We.text2, style = T.small, modifier = Modifier.padding(top = 4.dp))
                    if (NeuralTts.error.isNotEmpty()) Text(NeuralTts.error, color = We.danger, style = T.small, modifier = Modifier.padding(top = 8.dp))
                    SmallBtn("Download natural voices", Modifier.padding(top = 12.dp), primary = true) { NeuralTts.download(ctx) }
                }
            }
            Text("Voices: Supertonic 3 by Supertone (MIT code, OpenRAIL-M model licence) running with sherpa-onnx.", color = We.text2, style = T.tiny, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun testNow(ctx: android.content.Context, text: String, id: String, flag: (Boolean) -> Unit) {
    flag(true)
    Speaker.onState = { if (Speaker.speakingId == null) flag(false) }
    Speaker.speak(ctx, id, text, "") { Nav.say(it) }
}

@Composable
private fun SmallBtn(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(if (primary) We.primary else We.primary.copy(alpha = .12f)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(label, color = if (primary) We.onPrimary else We.primary, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}

@Composable
private fun RowScope.Pick(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) We.primary else We.field).clickable(onClick = onClick).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) We.onPrimary else We.text, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
    }
}
