package com.kafisir.wetalks.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.AudioRoute
import com.kafisir.wetalks.data.CallAudio
import com.kafisir.wetalks.data.CallRoutes
import com.kafisir.wetalks.data.RouteKind
import com.kafisir.wetalks.ui.*

private fun line(name: String, vararg d: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    d.forEach { addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
}.build()

private val BluetoothIcon: ImageVector by lazy { line("Bluetooth", "M7 7l10 10l-5 5V2l5 5L7 17") }
private val HeadsetIcon: ImageVector by lazy { line("Headset", "M4 16v-4a8 8 0 0 1 16 0v4M4 14h3v6H4ZM17 14h3v6h-3Z") }

private fun iconFor(r: AudioRoute): ImageVector = when (r.kind) {
    RouteKind.EARPIECE -> Ic.Phone
    RouteKind.SPEAKER -> Ic.Speaker
    RouteKind.BLUETOOTH -> BluetoothIcon
    RouteKind.WIRED -> HeadsetIcon
}

/** The call button that shows where the sound goes now (phone, speaker, Bluetooth, headset) and opens the list of choices. */
@Composable
fun AudioRouteButton(onTouch: () -> Unit) {
    val ctx = LocalContext.current
    val route = CallAudio.route
    var open by remember { mutableStateOf(false) }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) CallAudio.onBluetoothPermissionResult()
        else Nav.say("Bluetooth is off for WeTalks, so calls use the phone or speaker. You can allow it in the phone's Settings.")
    }
    val on = route.kind != RouteKind.EARPIECE
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = .16f)).clickable(onClick = { open = true; onTouch() }), contentAlignment = Alignment.Center) {
            Icon(iconFor(route), if (on) Color(0xFF0B1220) else Color.White, 24.dp)
        }
        Text(CallRoutes.shortLabel(route), color = Color.White.copy(alpha = .8f), style = T.tiny, modifier = Modifier.padding(top = 5.dp))
    }
    if (open) AudioRouteSheet(onDismiss = { open = false }, onAllowBluetooth = { open = false; btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) })
}

/** Bottom sheet with every route that can be used right now. */
@Composable
fun AudioRouteSheet(onDismiss: () -> Unit, onAllowBluetooth: () -> Unit) {
    val ctx = LocalContext.current
    val current = CallAudio.route
    val list = CallAudio.available
    Sheet(onDismiss) {
        Text("Play call audio on", color = We.text, style = T.name, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        for (r in list) {
            val selected = CallRoutes.same(r, current)
            RouteRow(iconFor(r), CallRoutes.label(r), selected) { CallAudio.select(r); onDismiss() }
        }
        // Android 12+: a Bluetooth headset can only be used after the permission is given
        if (CallAudio.needsBluetoothPermission(ctx)) RouteRow(BluetoothIcon, "Bluetooth headset (tap to allow)", false) { onAllowBluetooth() }
    }
}

@Composable
private fun RouteRow(icon: ImageVector, text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, if (selected) We.primary else We.text2, 22.dp); Spacer(Modifier.width(18.dp))
        Text(text, color = if (selected) We.primary else We.text, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.weight(1f))
        if (selected) Icon(Ic.Check, We.primary, 22.dp)
    }
}
