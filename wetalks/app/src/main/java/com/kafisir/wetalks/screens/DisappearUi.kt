package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Disappear
import com.kafisir.wetalks.data.DisappearRules
import com.kafisir.wetalks.ui.Ic
import com.kafisir.wetalks.ui.Icon
import com.kafisir.wetalks.ui.Nav
import com.kafisir.wetalks.ui.Sheet
import com.kafisir.wetalks.ui.T
import com.kafisir.wetalks.ui.Text
import com.kafisir.wetalks.ui.We
import kotlinx.coroutines.launch

/** The centered grey line for a system message ("Alice turned on disappearing messages: 24 hours"). */
@Composable
fun SysLine(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = We.text2, style = T.tiny, textAlign = TextAlign.Center, modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(We.field).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

/** Off / 24 hours / 7 days / 90 days. [canChange] false shows the choice read-only with the reason. */
@Composable
fun DisappearSheet(ref: ChatRef, current: Int, canChange: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Sheet(onDismiss) {
        Text("Disappearing messages", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        Text("New messages and their photos and files are removed for everyone in this chat after the time you choose.", color = We.text2, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        DisappearRules.CHOICES.forEach { secs ->
            Row(Modifier.fillMaxWidth().clickable(enabled = canChange && !busy) {
                if (secs == current) { onDismiss(); return@clickable }
                busy = true
                scope.launch {
                    try { Disappear.setTimer(ref, secs); onDismiss() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { busy = false; Nav.say(e.message ?: "Could not change disappearing messages") }
                }
            }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(DisappearRules.label(secs), color = if (canChange) We.text else We.text2, style = T.body, modifier = Modifier.weight(1f))
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (secs == current) We.primary else We.line), contentAlignment = Alignment.Center) { if (secs == current) Icon(Ic.Check, We.onPrimary, 14.dp) }
            }
        }
        if (!canChange) Text("Only admins can change this.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        Spacer(Modifier.width(1.dp))
        Text("This is not a security guarantee: someone in the chat can still take a screenshot or keep a copy with a changed app. A phone that is off or has no internet removes messages when it is back online.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}
