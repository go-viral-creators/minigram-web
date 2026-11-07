package com.kafisir.wetalks

import com.kafisir.wetalks.screens.CallMini
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.screens.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.delay

class WeTalksApp : Application() {
    override fun onCreate() { super.onCreate(); if (Application.getProcessName().endsWith(":tts")) return   // the voice engine process needs none of the app
        com.kafisir.wetalks.data.CrashLog.install(this); com.kafisir.wetalks.data.ChatLock.load(this); com.kafisir.wetalks.screens.CallSeen.load(this); com.kafisir.wetalks.ui.Sfx.init(this); Repo.init(this); Repo.watchConfig(); com.kafisir.wetalks.data.Calls.init(this); ThemeMode.load(this); UiPrefs.load(this); AppLock.load(this); com.kafisir.wetalks.voice.VoiceFx.load(this); I18n.load(this); com.kafisir.wetalks.data.DataPrefs.load(this); com.kafisir.wetalks.voice.VoiceFx.warmUp(this); com.kafisir.wetalks.data.FileKit.sweep(this); com.kafisir.wetalks.screens.ServicePrefs.load(this); com.kafisir.wetalks.voice.NeuralTts.load(this) }
}

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onStart() { super.onStart(); com.kafisir.wetalks.data.Calls.foreground = true }
    override fun onStop() { super.onStop(); com.kafisir.wetalks.data.Calls.foreground = false }

    // a volume button while a call is ringing only silences the ring (like the normal phone app)
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        if (com.kafisir.wetalks.data.Calls.phase == com.kafisir.wetalks.data.CallPhase.INCOMING && (e.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN) com.kafisir.wetalks.data.Calls.silenceRing()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true); setTurnScreenOn(true)
        intent?.data?.let { Nav.pendingLink.value = it.toString() }
        openChatFrom(intent)
        setContent { WeTheme { App() } }
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); intent.data?.let { Nav.pendingLink.value = it.toString() }; openChatFrom(intent) }
    /** Tapping a message notification opens that chat. */
    private fun openChatFrom(i: android.content.Intent?) {
        val cb = i?.getStringExtra("cb_uid")?.takeIf { it.isNotEmpty() }
        if (cb != null) { Nav.pendingCallback.value = Triple(cb, i.getBooleanExtra("cb_video", false), i.getBooleanExtra("cb_call", false)); return }
        if (i?.getBooleanExtra("services", false) == true) { Nav.pendingServices.value = true; return }
        val cid = i?.getStringExtra("cid")?.takeIf { it.isNotEmpty() } ?: return
        Nav.pendingChat.value = com.kafisir.wetalks.data.ChatRef(cid, com.kafisir.wetalks.data.Kind.values().getOrElse(i.getIntExtra("kind", 0)) { com.kafisir.wetalks.data.Kind.DM }, i.getStringExtra("peer")?.ifEmpty { null })
        com.kafisir.wetalks.data.Notifier.cancel(cid)
    }
}

@Composable
fun App() {
    val uid by remember { Repo.authState() }.collectAsState(initial = Repo.uid.ifEmpty { null })
    var phone by remember { mutableStateOf(false) }
    val ctxApp = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // status-bar icons: white on our blue/slate app bars, dark only on the plain sign-in page in light mode
    val view = androidx.compose.ui.platform.LocalView.current
    val darkNow = We.dark
    SideEffect {
        val w = (view.context as android.app.Activity).window
        val ctl = androidx.core.view.WindowCompat.getInsetsController(w, view)
        ctl.isAppearanceLightStatusBars = uid == null && !phone && !darkNow
        ctl.isAppearanceLightNavigationBars = !darkNow
    }
    Box(Modifier.fillMaxSize().background(We.bg)) {
        if (uid == null) {
            if (phone) PhoneScreen { phone = false } else AuthScreen { phone = true }
        } else {
            val ver by Nav.profileVersion
            val me by produceState<Boolean?>(null, uid, ver) { value = try { Repo.userOnce(uid!!)?.name?.isNotEmpty() == true } catch (e: Exception) { false } }
            val needPin by Repo.twoStepRequired.collectAsState()
            val pinChecked by Repo.twoStepChecked.collectAsState()
            LaunchedEffect(uid, me) { if (me == true) { try { Repo.checkTwoStep() } catch (_: Exception) {} } }
            // nothing starts (chat list, notifications, keys) until 2-step verification, if it is on, has been passed on this phone
            LaunchedEffect(uid, me, needPin, pinChecked) { if (me == true && pinChecked && !needPin) {
                // incoming calls first: nothing else may stop the phone from ringing
                try { com.kafisir.wetalks.data.Calls.listen() } catch (_: Exception) {}
                com.kafisir.wetalks.data.ConnectService.sync(ctxApp); com.kafisir.wetalks.data.Notifier.start(ctxApp); Repo.registerPush(); Repo.watchServiceInbox(); try { Repo.publishWebProfile() } catch (_: Exception) {}
                try { Repo.watchBlocks(); Repo.watchPrivacy(); Repo.publishKey(); Repo.registerDevice(); Repo.watchDevice(); com.kafisir.wetalks.data.Calls.listen() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Nav.say(e.message ?: "Could not set up encryption") } } }
            val link by Nav.pendingLink
            val pendChat by Nav.pendingChat
            val pendCb by Nav.pendingCallback
            val pendSvc by Nav.pendingServices
            LaunchedEffect(pendSvc, me) { if (me == true && pendSvc) { Nav.pendingServices.value = false; Nav.stack.clear(); Nav.push(RServices) } }
            LaunchedEffect(pendCb, me, needPin, pinChecked) { if (me == true && pinChecked && !needPin && pendCb != null) { val (u, v, ring) = pendCb!!; Nav.pendingCallback.value = null; Nav.stack.clear(); Nav.tab.intValue = 3; if (ring) Nav.push(RCallBack(u, v)) } }
            LaunchedEffect(pendChat, me) { if (me == true && pendChat != null) { val c = pendChat!!; Nav.pendingChat.value = null; Nav.stack.clear(); Nav.push(RChat(c)) } }
            LaunchedEffect(link, me) { if (me == true && link != null) { val l = link!!; Nav.pendingLink.value = null; try { Repo.resolveLink(l)?.let { Nav.push(it) } } catch (_: Exception) {} } }
            // the first resume often happens before the profile check has finished, so also go online as soon as it has
            LaunchedEffect(me) { if (me == true) { Repo.watchConnection(); if (com.kafisir.wetalks.data.Calls.foreground) Repo.setOnline(true) } }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (me == true) { Repo.setOnline(true); try { com.kafisir.wetalks.data.Calls.listen() } catch (_: Exception) {} } }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (me == true) Repo.setOnline(false) }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (AppLock.enabled) AppLock.locked = true; com.kafisir.wetalks.data.ChatLock.relockAll() }
            when (me) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Logo(88.dp) }
                false -> ProfileSetupScreen(null, onDone = {})
                true -> if (!pinChecked) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Logo(88.dp) } else if (needPin) com.kafisir.wetalks.screens.TwoStepGateScreen() else if (Repo.keyConflict.collectAsState().value) RestoreKeyScreen() else if (com.kafisir.wetalks.data.Calls.phase != com.kafisir.wetalks.data.CallPhase.IDLE && !(com.kafisir.wetalks.data.Calls.minimized && com.kafisir.wetalks.data.Calls.phase != com.kafisir.wetalks.data.CallPhase.INCOMING) && !(AppLock.enabled && AppLock.locked && com.kafisir.wetalks.data.Calls.phase != com.kafisir.wetalks.data.CallPhase.INCOMING)) { CallScreen() }
                    else if (AppLock.enabled && AppLock.locked) LockOverlay() else Main()
            }
        }
        if (Repo.uid.isNotEmpty() && Repo.twoStepChecked.collectAsState().value && !Repo.twoStepRequired.collectAsState().value) com.kafisir.wetalks.screens.TwoStepReminder()
        val maint by Repo.maint.collectAsState()
        val myVer = remember { try { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctxApp.packageManager.getPackageInfo(ctxApp.packageName, 0)) } catch (_: Exception) { Long.MAX_VALUE } }
        if (maint.on || maint.minVersion > myVer) com.kafisir.wetalks.screens.MaintenanceScreen(maint, update = !maint.on)
        val toast by Nav.toast
        LaunchedEffect(toast) { if (toast != null) { delay(2600); Nav.toast.value = null } }
        if (toast != null) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 90.dp, start = 24.dp, end = 24.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE60F1B2D)).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(toast!!, color = Color.White, style = T.small)
        }
    }
}

@Composable
fun Main() {
    BackHandler(enabled = Nav.stack.isNotEmpty()) { Nav.pop() }
    // Android 13+: notifications (calls, voice-message controls) need a one-time permission
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    var askBackup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val sp = ctx0.getSharedPreferences("wt_ui", android.content.Context.MODE_PRIVATE)
        delay(4000)
        if (sp.getInt("bk_skips", 0) < 3 && !Repo.hasKeyBackup()) askBackup = true
    }
    if (askBackup) {
        val sp = ctx0.getSharedPreferences("wt_ui", android.content.Context.MODE_PRIVATE)
        val skip = { sp.edit().putInt("bk_skips", sp.getInt("bk_skips", 0) + 1).apply(); askBackup = false }
        KeyBackupSheet(skip) { askBackup = false }
    }
    LaunchedEffect(Unit) {
        val sp = ctx0.getSharedPreferences("wt_ui", android.content.Context.MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !sp.getBoolean("notif_asked", false) && ctx0.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sp.edit().putBoolean("notif_asked", true).apply(); notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val top = Nav.stack.lastOrNull()
    Box(Modifier.fillMaxSize()) {
    AnimatedContent(top, transitionSpec = {
        if (targetState != null && initialState == null || (targetState != null && Nav.stack.size >= 1))
            (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
        else fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
    }, label = "nav") { r ->
        Box(Modifier.fillMaxSize().background(We.bg)) {
            when (r) {
                null -> HomeScreen()
                is RChat -> ChatScreen(r.ref)
                is RProfile -> ProfileScreen(r.uid)
                RNewChat -> NewChatScreen()
                RNewGroup -> NewGroupScreen()
                RNewChannel -> NewChannelScreen()
                RDiscover -> DiscoverScreen()
                is RGroupInfo -> GroupInfoScreen(r.id)
                is RChannelInfo -> ChannelInfoScreen(r.id)
                is RAddMembers -> AddMembersScreen(r.gid)
                REditProfile -> {
                    val me by Repo.user(Repo.uid).collectAsState()
                    ProfileSetupScreen(me, onDone = { Nav.pop() }, onBack = { Nav.pop() })
                }
                RPrivacy -> PrivacyScreen()
                RAppearance -> AppearanceScreen()
                RBlocked -> BlockedScreen()
                RAbout -> AboutScreen()
                is RImage -> ImageViewer(r.ref, r.mediaId, r.ids)
                is RNaturalVoices -> com.kafisir.wetalks.screens.NaturalVoicesScreen()
                is RHtml -> com.kafisir.wetalks.screens.HtmlPreviewScreen(r.html)
                is RServices -> com.kafisir.wetalks.screens.ServiceInboxScreen()
                is RVideo -> com.kafisir.wetalks.screens.VideoPlayerScreen(r.path, r.title)
                RPhotoEdit -> PhotoEditorScreen()
                is RBrowser -> BrowserScreen(r.url)
                RChatSettings -> ChatSettingsScreen()
                RStorage -> StorageScreen()
                RAppLock -> AppLockScreen()
                is RFolder -> com.kafisir.wetalks.screens.FolderScreen(r.locked, r.tab)
                RLabels -> com.kafisir.wetalks.screens.LabelsScreen()
                RDevices -> com.kafisir.wetalks.screens.LinkedDevicesScreen()
                RTwoStep -> com.kafisir.wetalks.screens.TwoStepScreen()
                is RCallInfo -> com.kafisir.wetalks.screens.CallInfoScreen(r.peer)
                is RCallBack -> com.kafisir.wetalks.screens.CallBackScreen(r.uid, r.video)
                is RQr -> QrScreen(r)
                RLanguage -> LanguageScreen()
                is RGroupSettings -> GroupSettingsScreen(r.id)
                is RChannelSettings -> ChannelSettingsScreen(r.id)
                is RModeration -> com.kafisir.wetalks.screens.ModerationScreen(r.id, r.kind)
            }
        }
    }
    VoiceOverlay()
    CallMini()
    }
}

@Composable
private fun BoxScope.VoiceOverlay() {
    if (com.kafisir.wetalks.voice.VoicePlayer.currentId != null && Nav.stack.lastOrNull() !is RPhotoEdit)
        MiniPlayer(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp))
    else if (com.kafisir.wetalks.voice.Speaker.reading && Nav.stack.lastOrNull() !is RPhotoEdit)
        ReadAloudBar(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 62.dp))
}
