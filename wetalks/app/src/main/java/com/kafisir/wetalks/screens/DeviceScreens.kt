package com.kafisir.wetalks.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch

/** Scans any QR code and hands its text to [onCode]. */
fun scanCode(ctx: Context, onCode: (String) -> Unit) {
    try {
        val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, opts).startScan()
            .addOnSuccessListener { b -> onCode(b.rawValue ?: "") }
            .addOnFailureListener { Nav.say("Scanner isn't available on this phone") }
    } catch (e: Exception) { Nav.say("Scanner isn't available on this phone") }
}

private fun ago(ts: Long): String {
    if (ts <= 0) return "unknown"
    val s = (System.currentTimeMillis() - ts) / 1000
    return when { s < 90 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} days ago" }
}

/** All phones signed in to this account; link a new one or log one out. */
@Composable
fun LinkedDevicesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by Repo.devices.collectAsState()
    var request by remember { mutableStateOf<Repo.LinkRequest?>(null) }
    var logout by remember { mutableStateOf<Repo.DeviceInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Repo.watchDevices(); Repo.registerDevice() }
    val me = Repo.deviceId
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Linked devices", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row2(Ic.Plus, "Link a new phone", "Scan the code the new phone shows after it signs in", onClick = {
                scanCode(ctx) { code -> scope.launch {
                    val r = try { Repo.readLinkRequest(code) } catch (e: Exception) { null }
                    if (r == null) Nav.say("That code is not valid for this account, or it expired") else request = r
                } }
            })
            SectionLabel("Signed in on")
            if (list.isEmpty()) Text("No phones listed yet.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            list.forEach { d ->
                val mine = d.id == me
                Row2(Ic.Phone, d.name + if (mine) " (this phone)" else "", (if (mine) "Active now" else "Last active " + ago(d.last)) + (if (d.ver.isNotEmpty()) " · v" + d.ver else "") + if (d.revoked) " · logging out…" else "",
                    trailing = { if (!mine && !d.revoked) Text("Log out", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { logout = d }.padding(horizontal = 10.dp, vertical = 8.dp)) })
            }
            Text("Your chats are end-to-end encrypted. A linked phone gets a sealed copy of your encryption key straight from your other phone; the server never sees it. Only link phones you own.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
            Text("Logging out a phone signs it out the next time it is online. If a phone is lost, also change your encryption key and turn on 2-step verification.", color = We.text2, style = T.small, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 30.dp))
        }
    }
    request?.let { r ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) request = null }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(We.surface).padding(22.dp)) {
                Text("Link " + r.name + "?", color = We.text, style = T.title); Spacer(Modifier.height(8.dp))
                Text("This phone will be able to read all your chats. Only continue if the code is on a phone you are holding.", color = We.text2, style = T.body)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    Text("Cancel", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (!busy) request = null }.padding(horizontal = 14.dp, vertical = 10.dp))
                    Text(if (busy) "Linking…" else "Link", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                        if (busy) return@clickable
                        busy = true
                        scope.launch { try { Repo.grantLink(r); Nav.say("Linked. The new phone will open in a moment") } catch (e: Exception) { Nav.say(Repo.explain(e, "Linking")) }; busy = false; request = null }
                    }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
    logout?.let { d ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { logout = null }) {
            Column(Modifier.clip(RoundedCornerShape(20.dp)).background(We.surface).padding(22.dp)) {
                Text("Log out " + d.name + "?", color = We.text, style = T.title); Spacer(Modifier.height(8.dp))
                Text("That phone is signed out the next time it connects to the internet.", color = We.text2, style = T.body)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    Text("Cancel", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { logout = null }.padding(horizontal = 14.dp, vertical = 10.dp))
                    Text("Log out", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { scope.launch { try { Repo.logOutDevice(d.id) } catch (e: Exception) { Nav.say(Repo.explain(e, "Log out of that phone")) } }; logout = null }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }
}

/** On the NEW phone: shows a QR code for the old phone to scan, then waits for the key. [onDone] runs when the key is installed. */
@Composable
fun LinkNewPhoneScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<Repo.LinkOffer?>(null) }
    var state by remember { mutableStateOf("starting") }          // starting | waiting | failed
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        state = "starting"
        val o = try { Repo.startLink() } catch (e: Exception) { state = "failed"; return@LaunchedEffect }
        offer = o; state = "waiting"
        val ok = try { Repo.awaitLink(o) } catch (e: kotlinx.coroutines.CancellationException) { Repo.cancelLink(o); throw e } catch (e: Exception) { false }
        if (!ok) state = "failed"
    }
    Column(Modifier.fillMaxSize().background(We.bg).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Link from my other phone", color = We.text, style = T.title, textAlign = TextAlign.Center)
        Text("On the phone that already has your chats open WeTalks, go to Me → Linked devices → Link a new phone, and scan this code.", color = We.text2, style = T.body, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(18.dp), contentAlignment = Alignment.Center) {
            val o = offer
            if (o != null && state == "waiting") QrImage(o.code, 230.dp) else Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) { Text(if (state == "failed") "Not linked" else "Preparing…", color = Color(0xFF6B7A90), style = T.body) }
        }
        Spacer(Modifier.height(16.dp))
        Text(when (state) { "waiting" -> "Waiting for the other phone… (10 minutes)"; "failed" -> "The other phone did not confirm in time, or the code did not match your account."; else -> "" }, color = We.text2, style = T.small, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        if (state == "failed") PrimaryButton("Try again", { attempt++ })
        Spacer(Modifier.height(10.dp))
        Text("Back", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { offer?.let { o -> scope.launch { Repo.cancelLink(o) } }; onBack() }.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}
