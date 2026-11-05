package com.kafisir.wetalks.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.kafisir.wetalks.R
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun friendly(e: Throwable): String = when (e) {
    is FirebaseAuthInvalidUserException -> "No account found with this email."
    is FirebaseAuthInvalidCredentialsException -> "Wrong email or password."
    is FirebaseAuthUserCollisionException -> "An account with this email already exists. Try signing in."
    is FirebaseAuthException -> e.message ?: "Sign-in failed."
    else -> e.message ?: "Something went wrong. Check your connection."
}

private suspend fun googleIdToken(activity: Activity): String {
    val web = activity.getString(R.string.default_web_client_id)
    val cm = CredentialManager.create(activity)
    fun req(filter: Boolean) = GetCredentialRequest.Builder().addCredentialOption(
        GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(filter).setServerClientId(web).setAutoSelectEnabled(false).build()).build()
    val res = try { cm.getCredential(activity, req(true)) }
    catch (e: androidx.credentials.exceptions.NoCredentialException) {
        try { cm.getCredential(activity, req(false)) }
        catch (e2: androidx.credentials.exceptions.NoCredentialException) {
            cm.getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(web).build()).build())
        }
    }
    return GoogleIdTokenCredential.createFrom(res.credential.data).idToken
}

@Composable
fun AuthScreen(onPhone: () -> Unit) {
    val ctx = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var signUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(We.bg).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(28.dp))
        Logo(76.dp)
        Spacer(Modifier.height(18.dp))
        Text(if (signUp) "Create your account" else "Welcome back", color = We.text, style = T.h1)
        Spacer(Modifier.height(6.dp))
        Text("Private, fast messaging for you, your groups and your channels.", color = We.text2, style = T.body)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.field).padding(4.dp)) {
            listOf("Sign in" to false, "Sign up" to true).forEach { (t, v) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (signUp == v) We.surface else Color.Transparent).clickable { signUp = v; error = null; info = null }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(t, color = if (signUp == v) We.primary else We.text2, style = T.name.copy(fontSize = 15.sp))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (signUp) { Field(name, { name = it }, "Full name", leading = Ic.User); Spacer(Modifier.height(14.dp)) }
        Field(email, { email = it; error = null }, "Email", leading = Ic.Mail, keyboard = KeyboardType.Email)
        Spacer(Modifier.height(14.dp))
        Field(pass, { pass = it; error = null }, "Password", leading = Ic.Lock, password = true, error = error)
        if (!signUp) {
            Text("Forgot password?", color = We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.align(Alignment.End).clickable {
                if (email.isBlank()) error = "Enter your email above first" else scope.launch {
                    try { Repo.resetPassword(email); info = "Reset link sent to ${email.trim()}" } catch (e: Exception) { error = friendly(e) }
                }
            }.padding(vertical = 10.dp))
        } else Spacer(Modifier.height(12.dp))
        if (info != null) Text(info!!, color = We.ok, style = T.small, modifier = Modifier.padding(bottom = 8.dp))
        Spacer(Modifier.height(6.dp))
        PrimaryButton(if (signUp) "Create account" else "Sign in", loading = busy, onClick = {
            when {
                email.isBlank() || !email.contains("@") -> error = "Enter a valid email"
                pass.length < 6 -> error = "Password must be at least 6 characters"
                signUp && name.isBlank() -> error = "Enter your name"
                else -> scope.launch {
                    busy = true; error = null
                    try {
                        if (signUp) { Repo.signUpEmail(email, pass); Nav.pendingName = name.trim() } else Repo.signInEmail(email, pass)
                    } catch (e: Exception) { error = friendly(e) }
                    busy = false
                }
            }
        })
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(We.line)); Text("  or continue with  ", color = We.text2, style = T.small); Box(Modifier.weight(1f).height(1.dp).background(We.line))
        }
        Spacer(Modifier.height(20.dp))
        OutlineButton("Continue with Google", onClick = {
            scope.launch {
                busy = true; error = null
                try { Repo.signInGoogle(googleIdToken(ctx)) }
                catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) { }
                catch (e: Exception) { error = "Google sign-in failed: ${e.message ?: "unknown error"}" }
                busy = false
            }
        }, icon = { Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0xFFEA4335)), contentAlignment = Alignment.Center) { Text("G", color = Color.White, style = T.name.copy(fontSize = 14.sp)) } })
        Spacer(Modifier.height(12.dp))
        OutlineButton("Continue with phone", onClick = onPhone, icon = { Icon(Ic.Phone, We.primary, 22.dp) })
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Lock, We.text2, 14.dp); Spacer(Modifier.width(6.dp))
            Text("End-to-end encrypted chats", color = We.text2, style = T.small)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun PhoneScreen(onBack: () -> Unit) {
    val act = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var country by remember { mutableStateOf(com.kafisir.wetalks.data.Countries.guess(act)) }
    var num by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var vid by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var left by remember { mutableStateOf(0) }
    LaunchedEffect(vid, left) { if (vid != null && left > 0) { delay(1000); left-- } }

    fun send() {
        val bad = com.kafisir.wetalks.data.Countries.validate(country, num)
        if (bad != null) { error = bad; return }
        val full = com.kafisir.wetalks.data.Countries.e164(country, num)
        busy = true; error = null
        Repo.startPhone(act, full,
            onCode = { id -> vid = id; busy = false; left = 45 },
            onAuto = { c -> scope.launch { try { Repo.signInWith(c) } catch (e: Exception) { error = friendly(e) }; busy = false } },
            onError = { m -> error = m; busy = false })
    }

    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(if (vid == null) "Your phone number" else "Verify", onBack = { if (vid != null) { vid = null; code = "" } else onBack() })
        Column(Modifier.fillMaxSize().imePadding().padding(24.dp)) {
            if (vid == null) {
                Text("We will text you a 6-digit code to confirm your number.", color = We.text2, style = T.body)
                Spacer(Modifier.height(22.dp))
                PhoneField(country, { country = it; num = com.kafisir.wetalks.data.Countries.clean(it, num); error = null }, num, { num = it; error = null }, error = error)
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Send code", loading = busy, onClick = ::send)
            } else {
                Text("Enter the code sent to +${country.dial} ${num.trim()}", color = We.text2, style = T.body)
                Spacer(Modifier.height(22.dp))
                Field(code, { code = it.filter { c -> c.isDigit() }.take(6); error = null }, "6-digit code", keyboard = KeyboardType.NumberPassword, error = error)
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Verify", loading = busy, enabled = code.length == 6, onClick = {
                    scope.launch { busy = true; try { Repo.signInPhoneCode(vid!!, code) } catch (e: Exception) { error = "Wrong or expired code" }; busy = false }
                })
                Spacer(Modifier.height(14.dp))
                Text(if (left > 0) "Resend code in ${left}s" else "Resend code", color = if (left > 0) We.text2 else We.primary, style = T.small.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable(enabled = left == 0) { send() }.padding(10.dp))
            }
        }
    }
}

/** Profile editor / first-run setup: header with photo, one card of fields, discoverable by phone (optional). */
@Composable
fun ProfileSetupScreen(existing: com.kafisir.wetalks.data.User?, onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: Nav.pendingName ?: Repo.auth.currentUser?.displayName ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var about by remember { mutableStateOf(existing?.about ?: "") }
    val savedPhone = remember { ctx.getSharedPreferences("wt_ui", android.content.Context.MODE_PRIVATE).getString("my_phone", "") ?: "" }
    val parsed = remember { com.kafisir.wetalks.data.Countries.parse(savedPhone) }
    var country by remember { mutableStateOf(parsed?.first ?: com.kafisir.wetalks.data.Countries.guess(ctx)) }
    var phone by remember { mutableStateOf(parsed?.second ?: savedPhone.filter { it.isDigit() }.takeLast(10)) }
    var phoneErr by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(existing != null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u: Uri? -> if (u != null) photo = Pics.avatarB64(ctx, u) }

    LaunchedEffect(username) {
        val l = username.lowercase()
        if (l.isEmpty()) { status = null; ok = false; return@LaunchedEffect }
        if (!Regex("^[a-z0-9_]{3,20}$").matches(l)) { status = "3-20 letters, numbers or _"; ok = false; return@LaunchedEffect }
        status = "Checking…"; ok = false
        delay(400)
        val free = try { Repo.usernameFree(l) } catch (e: Exception) { false }
        ok = free; status = if (free) "Available" else "Already taken"
    }

    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(if (existing == null) "Set up your profile" else "Edit profile", onBack = onBack)
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(We.appBar, We.bg))).padding(top = 18.dp, bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Box(Modifier.clip(CircleShape).background(We.bg).padding(4.dp)) { Avatar(name.ifEmpty { "?" }, photo ?: existing?.photo ?: "", 112.dp, seed = Repo.uid) }
                    Box(Modifier.align(Alignment.BottomEnd).size(38.dp).clip(CircleShape).background(We.primary).border2(We.bg), contentAlignment = Alignment.Center) { Icon(Ic.Camera, We.onPrimary, 20.dp) }
                }
                Spacer(Modifier.height(10.dp))
                Text("Tap to change photo", color = We.text2, style = T.small)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Field(name, { name = it.take(60) }, "Full name", leading = Ic.User)
                Spacer(Modifier.height(14.dp))
                Field(username, { username = it.filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) }, "Username", prefix = "@",
                    error = if (!ok && status != null && status != "Checking…") status else null, trailing = { if (ok) Icon(Ic.Check, We.ok, 20.dp) })
                if (status != null && (ok || status == "Checking…")) Text(status!!, color = if (ok) We.ok else We.text2, style = T.small, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
                Spacer(Modifier.height(14.dp))
                Field(about, { about = it.take(140) }, "Bio", leading = Ic.Info)
                Spacer(Modifier.height(14.dp))
                PhoneField(country, { country = it; phone = com.kafisir.wetalks.data.Countries.clean(it, phone); phoneErr = null }, phone, { phone = it; phoneErr = null }, label = "Phone number (optional)", error = phoneErr, hint = false)
                Text("Lets friends who have your number find you. Only a one-way hash is stored; your number is never shown.", color = We.text2, style = T.small, modifier = Modifier.padding(start = 2.dp, top = 6.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
            PrimaryButton(if (existing == null) "Continue" else "Save changes", loading = busy, enabled = name.isNotBlank() && ok, onClick = {
                val pe = if (phone.isEmpty()) null else com.kafisir.wetalks.data.Countries.validate(country, phone)
                if (pe != null) { phoneErr = pe; return@PrimaryButton }
                scope.launch {
                    busy = true
                    val err = Repo.saveProfile(name, username, about, photo)
                    if (err == null && phone.isNotEmpty()) {
                        val full = com.kafisir.wetalks.data.Countries.e164(country, phone)
                        try { Repo.registerPhone(full) } catch (_: Exception) {}
                        ctx.getSharedPreferences("wt_ui", android.content.Context.MODE_PRIVATE).edit().putString("my_phone", full).apply()
                    }
                    busy = false
                    if (err != null) Nav.say(err) else { Nav.pendingName = null; Nav.profileVersion.intValue++; onDone() }
                }
            })
        }
    }
}

private fun Modifier.border2(c: androidx.compose.ui.graphics.Color) = this.then(Modifier.border(3.dp, c, CircleShape))
