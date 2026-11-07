package com.kafisir.wetalks.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.kafisir.wetalks.ui.Nav
import com.kafisir.wetalks.ui.T
import com.kafisir.wetalks.ui.Text
import com.kafisir.wetalks.ui.We
import com.kafisir.wetalks.voice.DndAccess
import com.kafisir.wetalks.voice.MicPermission
import com.kafisir.wetalks.voice.RecordDraft
import com.kafisir.wetalks.voice.RecordGuard
import com.kafisir.wetalks.voice.RecordSession
import com.kafisir.wetalks.voice.RecorderMachine

/**
 * Everything the person sees before and around a voice recording that is not the bar itself: the microphone permission
 * explanation, the "open settings" path after a permanent denial, the one-time "mute other notifications?" question, and
 * the small hint line. [preflight] is called when the mic is pressed; it returns true only when recording may start now.
 */
object RecordPrompts {
    private enum class Dlg { MIC_WHY, MIC_SETTINGS, DND }
    private var dlg by mutableStateOf<Dlg?>(null)
    private var launch: (() -> Unit)? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("wt_rec", Context.MODE_PRIVATE)
    private fun Context.activity(): Activity? { var c = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

    fun preflight(ctx: Context, chatId: String): Boolean {
        val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val why = ctx.activity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: false
        when (MicPermission.next(granted, why, sp(ctx).getBoolean("mic_asked", false))) {
            MicPermission.Step.GO -> {}
            MicPermission.Step.ASK -> { launch?.invoke(); return false }
            MicPermission.Step.EXPLAIN_THEN_ASK -> { dlg = Dlg.MIC_WHY; return false }
            MicPermission.Step.OPEN_SETTINGS -> { dlg = Dlg.MIC_SETTINGS; return false }
        }
        val other = RecordDraft.chatId
        if (other != null && other != chatId) { Nav.say("Send or delete your unsent voice message in the other chat first"); return false }
        if (RecordSession.state == RecorderMachine.State.PAUSED || RecordSession.state == RecorderMachine.State.SAVED_DRAFT) { Nav.say("Send or delete the paused voice message first"); return false }
        val v = RecordGuard.check(ctx)
        if (!v.allowed) { Nav.say(v.message); return false }
        if (DndAccess.canAsk(ctx)) { dlg = Dlg.DND; return false }
        return true
    }

    /** Put once in the chat screen. Owns the permission launcher and the dialogs. */
    @Composable
    fun Host() {
        val ctx = LocalContext.current
        val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            sp(ctx).edit().putBoolean("mic_asked", true).apply()
            if (ok) Nav.say("Microphone allowed. Hold the mic button to record") else Nav.say("Voice messages need the microphone")
        }
        DisposableEffect(perm) { launch = { perm.launch(Manifest.permission.RECORD_AUDIO) }; onDispose { launch = null } }
        when (dlg) {
            Dlg.MIC_WHY -> AlertDialog(onDismissRequest = { dlg = null }, containerColor = We.surface,
                title = { Text("Allow the microphone?", color = We.text, style = T.title) },
                text = { Text("WeTalks uses the microphone only while you hold or lock the mic button to record a voice message.", color = We.text2, style = T.body) },
                confirmButton = { TextButton(onClick = { dlg = null; perm.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue", color = We.primary) } },
                dismissButton = { TextButton(onClick = { dlg = null }) { Text("Not now", color = We.text2) } })
            Dlg.MIC_SETTINGS -> AlertDialog(onDismissRequest = { dlg = null }, containerColor = We.surface,
                title = { Text("Microphone is turned off", color = We.text, style = T.title) },
                text = { Text("Android will not ask again. Open Settings, tap Permissions, then Microphone, and choose Allow only while using the app.", color = We.text2, style = T.body) },
                confirmButton = { TextButton(onClick = { dlg = null; try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Nav.say("Open Settings, then Apps, then WeTalks") } }) { Text("Open Settings", color = We.primary) } },
                dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancel", color = We.text2) } })
            Dlg.DND -> {
                var never by remember { mutableStateOf(false) }
                AlertDialog(onDismissRequest = { DndAccess.noteAsked(ctx, never); dlg = null }, containerColor = We.surface,
                    title = { Text("Mute other notifications while you record?", color = We.text, style = T.title) },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            Text("Sounds from other apps can end up in your voice message. WeTalks can turn on Do Not Disturb (alarms only) while you record, and turns it back off when you finish. Calls and messages stay quiet during the recording, so you may miss a ring. You need to allow this once in Settings.", color = We.text2, style = T.body)
                            Row(Modifier.padding(top = 12.dp).clickable { never = !never }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(never, { never = it }); Spacer(Modifier.width(4.dp)); Text("Don't ask again", color = We.text2, style = T.body)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { DndAccess.noteAsked(ctx, never); dlg = null; try { ctx.startActivity(DndAccess.settingsIntent()) } catch (_: Exception) { Nav.say("Could not open the Do Not Disturb settings") } }) { Text("Allow", color = We.primary) } },
                    dismissButton = { TextButton(onClick = { DndAccess.noteAsked(ctx, never); dlg = null }) { Text("Not now", color = We.text2) } })
            }
            null -> {}
        }
    }

    /** One small line above the recording bar. Shows nothing when all is well. */
    @Composable
    fun Hint(modifier: Modifier = Modifier) {
        val h = RecordSession.hint ?: return
        Text(h, color = We.text2, style = T.small, modifier = modifier)
    }
}
