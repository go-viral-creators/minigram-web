package com.kafisir.wetalks.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.launch

private fun pinMessage(r: Repo.PinResult) = when {
    r.waitMin > 0 -> "Too many wrong tries. Try again in ${r.waitMin} min."
    r.left > 0 -> "Wrong PIN. ${r.left} " + (if (r.left == 1) "try" else "tries") + " left this hour."
    else -> "Wrong PIN."
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) =
    Field(value, { onChange(it.filter { c -> c.isDigit() }.take(6)) }, label, password = true, keyboard = KeyboardType.NumberPassword, error = error)

/** Shown on a phone that is not trusted yet, before anything else, when 2-step verification is on. */
@Composable
fun TwoStepGateScreen() {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var forgot by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(We.bg).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(30.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(We.primary.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Icon(Ic.Key, We.primary, 40.dp) }
        Spacer(Modifier.height(20.dp))
        Text("Two-step verification", color = We.text, style = T.title, textAlign = TextAlign.Center)
        Text("Enter the 6-digit PIN you set for this account.", color = We.text2, style = T.body, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp))
        PinField(pin, { pin = it; err = null }, "PIN", err)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Continue", loading = busy, enabled = pin.length == 6, onClick = {
            scope.launch {
                busy = true
                val r = try { Repo.verifyPin(pin) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Repo.PinResult(false) }
                busy = false
                if (!r.ok) { err = pinMessage(r); pin = "" }
            }
        })
        Spacer(Modifier.height(10.dp))
        Text("Forgot PIN?", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { forgot = true }.padding(14.dp))
        Text("Sign out", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { Repo.signOut() }.padding(14.dp))
    }
    if (forgot) ForgotPinDialog { forgot = false }
}

@Composable
private fun ForgotPinDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val at by produceState(-1L, tick) { value = Repo.pinResetAt() }
    val day = 86_400_000L
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(We.surface).padding(22.dp)) {
            Text("Forgot your PIN?", color = We.text, style = T.title); Spacer(Modifier.height(8.dp))
            val left = if (at > 0) ((at + 7 * day - System.currentTimeMillis()) / day).toInt() + 1 else 7
            Text(when {
                at < 0 -> "Checking…"
                at == 0L -> "You can remove the PIN after waiting 7 days. Everyone who already has the PIN is told, and can cancel it. Start the 7 days now?"
                left > 0 && System.currentTimeMillis() < at + 7 * day -> "A reset is waiting. You can remove the PIN in about $left day(s). If you remember the PIN before that, just enter it."
                else -> "The 7 days are over. You can remove the PIN now."
            }, color = We.text2, style = T.body)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                Text("Close", color = We.primary, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onDismiss() }.padding(horizontal = 14.dp, vertical = 10.dp))
                if (at == 0L) Text("Start", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { scope.launch { try { Repo.requestPinReset() } catch (e: Exception) { Nav.say(Repo.explain(e, "PIN reset request")) }; tick++ } }.padding(horizontal = 14.dp, vertical = 10.dp))
                if (at > 0 && System.currentTimeMillis() >= at + 7 * day) Text("Remove PIN", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { scope.launch { val ok = try { Repo.completePinReset() } catch (e: Exception) { false }; if (!ok) Nav.say("Could not remove the PIN yet"); onDismiss() } }.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/** A sheet with one or more PIN boxes. [submit] returns an error text, or null when done. */
@Composable
private fun PinSheet(title: String, labels: List<String>, button: String, onDismiss: () -> Unit, submit: suspend (List<String>) -> String?) {
    val scope = rememberCoroutineScope()
    val pins = remember { mutableStateOf(List(labels.size) { "" }) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Sheet(onDismiss) {
        Text(title, color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        Column(Modifier.padding(24.dp)) {
            labels.forEachIndexed { i, l ->
                PinField(pins.value[i], { v -> pins.value = pins.value.toMutableList().also { it[i] = v }; err = null }, l, if (i == labels.lastIndex) err else null)
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(button, loading = busy, enabled = pins.value.all { it.length == 6 }, onClick = {
                scope.launch {
                    busy = true
                    val e = try { submit(pins.value) } catch (x: kotlinx.coroutines.CancellationException) { throw x } catch (x: Exception) { Repo.explain(x, "Two-step setup") }
                    busy = false
                    if (e == null) onDismiss() else { err = e; pins.value = List(labels.size) { "" } }
                }
            })
        }
    }
}

@Composable
fun TwoStepScreen() {
    var tick by remember { mutableIntStateOf(0) }
    val enabled by produceState<Boolean?>(null, tick) { value = Repo.twoStepEnabled() }
    val resetAt by produceState(0L, tick, enabled) { value = if (enabled == true) Repo.pinResetAt() else 0L }
    var sheet by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Two-step verification", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (enabled == null) Text("Checking…", color = We.text2, style = T.body, modifier = Modifier.padding(20.dp))
            else if (enabled == false) {
                Row2(Ic.Key, "Turn on", "Set a 6-digit PIN", onClick = { sheet = "on" })
                Text("With a PIN on, someone who only gets your SMS code cannot open your account on a new phone. You can set it up or turn it off any time. Your messages stay unreadable without your encryption key either way.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
            } else {
                if (resetAt > 0) {
                    Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(We.surface).padding(16.dp)) {
                        Text("A PIN reset was requested", color = We.danger, style = T.body.copy(fontWeight = FontWeight.SemiBold))
                        Text("Someone asked to remove your PIN without knowing it. If that was not you, cancel it now with your PIN.", color = We.text2, style = T.small, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                        PrimaryButton("Cancel the reset", { sheet = "cancel" })
                    }
                }
                Row2(Ic.Check, "Two-step verification is on", "This phone is trusted")
                Row2(Ic.Edit, "Change PIN", "Other phones will ask for the new PIN", onClick = { sheet = "change" })
                Row2(Ic.Trash, "Turn off", "Needs your PIN", tint = We.danger, onClick = { sheet = "off" })
                Text("Forgot the PIN? Open WeTalks on a new phone and choose Forgot PIN: after 7 days it can be removed, and anyone who still has the PIN can cancel that.", color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
            }
        }
    }
    when (sheet) {
        "on" -> PinSheet("Create a PIN", listOf("New PIN (6 digits)", "Repeat PIN"), "Turn on", { sheet = "" }) { p ->
            if (p[0] != p[1]) "The two PINs do not match" else { Repo.enableTwoStep(p[0]); tick++; Nav.say("Two-step verification is on"); null }
        }
        "change" -> PinSheet("Change PIN", listOf("Current PIN", "New PIN (6 digits)", "Repeat new PIN"), "Change", { sheet = "" }) { p ->
            if (p[1] != p[2]) "The new PINs do not match" else { val r = Repo.changeTwoStep(p[0], p[1]); if (r.ok) { tick++; Nav.say("PIN changed"); null } else pinMessage(r) }
        }
        "off" -> PinSheet("Turn off", listOf("Current PIN"), "Turn off", { sheet = "" }) { p ->
            val r = Repo.disableTwoStep(p[0]); if (r.ok) { tick++; Nav.say("Two-step verification is off"); null } else pinMessage(r)
        }
        "cancel" -> PinSheet("Cancel the reset", listOf("Current PIN"), "Cancel the reset", { sheet = "" }) { p ->
            val r = Repo.cancelPinReset(p[0]); if (r.ok) { tick++; Nav.say("Reset cancelled"); null } else pinMessage(r)
        }
    }
}
