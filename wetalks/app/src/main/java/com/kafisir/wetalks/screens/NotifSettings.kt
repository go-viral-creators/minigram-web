package com.kafisir.wetalks.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.ChatItem
import com.kafisir.wetalks.data.ChatNotif
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*

/** Opens Android's own page with every notification category of WeTalks (sound, vibration, importance per category). */
fun openSystemNotificationSettings(ctx: Context) {
    try { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Nav.say("Open Android Settings > Apps > WeTalks > Notifications") }
}

/** Notification settings of one chat: mute for a while / always, silent, and hiding the message text. */
@Composable
fun ChatNotifSheet(c: ChatItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val id = c.ref.id
    var until by remember { mutableStateOf(ChatNotif.mutedUntil(ctx, id)) }
    var silent by remember { mutableStateOf(ChatNotif.silent(ctx, id)) }
    var hide by remember { mutableStateOf(ChatNotif.hidePreview(ctx, id)) }
    val timed = until > System.currentTimeMillis()
    Sheet(onDismiss) {
        Text("Notifications", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
        if (c.muted || timed) {
            Text(if (c.muted) "Muted" else "Muted until " + java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(until)), color = We.text2, style = T.small, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
            SheetItem(Ic.Bell, "Unmute") { ChatNotif.unmuteTimed(ctx, id); if (c.muted) Repo.setMuted(id, false); onDismiss() }
        } else {
            SheetItem(Ic.BellOff, "Mute for 8 hours") { ChatNotif.muteFor(ctx, id, ChatNotif.H8); onDismiss() }
            SheetItem(Ic.BellOff, "Mute for 1 week") { ChatNotif.muteFor(ctx, id, ChatNotif.WEEK); onDismiss() }
            SheetItem(Ic.BellOff, "Mute always") { Repo.setMuted(id, true); onDismiss() }
        }
        Row2(Ic.Speaker, "Silent", "Show new messages without sound or vibration", trailing = { Toggle(silent) { silent = it; ChatNotif.setSilent(ctx, id, it) } })
        Row2(Ic.Eye, "Hide message text", "Notifications say \"New message\" instead of the text", trailing = { Toggle(hide) { hide = it; ChatNotif.setHidePreview(ctx, id, it) } })
        SheetItem(Ic.Sliders, "Sound and categories (Android settings)") { openSystemNotificationSettings(ctx); onDismiss() }
    }
}
