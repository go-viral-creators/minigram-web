package com.kafisir.wetalks.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.kafisir.wetalks.data.ChatRef

sealed interface Route
data class RChat(val ref: ChatRef) : Route
data class RProfile(val uid: String) : Route
data object RNewChat : Route
data object RNewGroup : Route
data object RNewChannel : Route
data object RDiscover : Route
data class RGroupInfo(val id: String) : Route
data class RChannelInfo(val id: String) : Route
data object REditProfile : Route
data object RPrivacy : Route
data object RAppearance : Route
data object RBlocked : Route
data object RAbout : Route
data class RAddMembers(val gid: String) : Route
data class RImage(val ref: ChatRef, val mediaId: String) : Route
data object RPhotoEdit : Route
data class RBrowser(val url: String) : Route
data object RChatSettings : Route
data object RStorage : Route
data object RAppLock : Route
data object RLanguage : Route
data class RGroupSettings(val id: String) : Route
data class RChannelSettings(val id: String) : Route
data class RQr(val title: String, val subtitle: String, val photo: String, val seed: String, val link: String) : Route

object Nav {
    val stack = mutableStateListOf<Route>()
    val tab = mutableIntStateOf(0)
    val profileVersion = mutableIntStateOf(0)
    val toast = mutableStateOf<String?>(null)
    var pendingName: String? = null
    var pendingPhotos: List<android.net.Uri> = emptyList()
    var pendingRef: ChatRef? = null
    var pendingReply: String? = null
    var jumpTo: String? = null
    val pendingLink = mutableStateOf<String?>(null)
    /** Opens the chat and scrolls to (and highlights) one message. */
    fun openChatAt(ref: ChatRef, mid: String) {
        jumpTo = mid
        val i = stack.indexOfLast { it is RChat && it.ref == ref }
        if (i >= 0) { while (stack.size - 1 > i) stack.removeAt(stack.lastIndex) } else stack.add(RChat(ref))
    }
    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean { if (stack.isEmpty()) return false; stack.removeAt(stack.lastIndex); return true }
    fun reset() { stack.clear(); tab.intValue = 0 }
    fun say(s: String) { toast.value = I18n.t(s) }
}
