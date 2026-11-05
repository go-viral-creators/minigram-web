package com.kafisir.wetalks

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
    override fun onCreate() { super.onCreate(); Repo.init(this); ThemeMode.load(this); UiPrefs.load(this); AppLock.load(this) }
}

class MainActivity : androidx.fragment.app.FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WeTheme { App() } }
    }
}

@Composable
fun App() {
    val uid by remember { Repo.authState() }.collectAsState(initial = Repo.uid.ifEmpty { null })
    var phone by remember { mutableStateOf(false) }
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
            LaunchedEffect(uid, me) { if (me == true) { Repo.watchBlocks(); Repo.publishKey() } }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (me == true) Repo.setOnline(true) }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (me == true) Repo.setOnline(false) }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (AppLock.enabled) AppLock.locked = true }
            when (me) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Logo(88.dp) }
                false -> ProfileSetupScreen(null, onDone = {})
                true -> if (AppLock.enabled && AppLock.locked) LockOverlay() else Main()
            }
        }
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
    val top = Nav.stack.lastOrNull()
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
                is RImage -> ImageViewer(r.ref, r.mediaId)
                RPhotoEdit -> PhotoEditorScreen()
                is RBrowser -> BrowserScreen(r.url)
                RChatSettings -> ChatSettingsScreen()
                RStorage -> StorageScreen()
                RAppLock -> AppLockScreen()
            }
        }
    }
}
