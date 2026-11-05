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

object Nav {
    val stack = mutableStateListOf<Route>()
    val tab = mutableIntStateOf(0)
    val profileVersion = mutableIntStateOf(0)
    val toast = mutableStateOf<String?>(null)
    var pendingName: String? = null
    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean { if (stack.isEmpty()) return false; stack.removeAt(stack.lastIndex); return true }
    fun reset() { stack.clear(); tab.intValue = 0 }
    fun say(s: String) { toast.value = s }
}
