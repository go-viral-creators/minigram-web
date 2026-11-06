package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*

/** Full-screen "we'll be right back" page. Turned on/off from the Firebase console (config/maintenance), no new app release needed. */
@Composable
fun MaintenanceScreen(m: Repo.Maint, update: Boolean) {
    Box(Modifier.fillMaxSize().background(We.bg).statusBarsPadding().navigationBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(88.dp)
            Spacer(Modifier.height(28.dp))
            Text(if (update) "Update required" else m.title.ifBlank { "We'll be right back" }, color = We.text, style = T.title, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(if (update) "This version of WeTalks is too old. Please install the latest version to continue." else m.message.ifBlank { "WeTalks is under maintenance for a short while. Your messages are safe." },
                color = We.text2, style = T.body, textAlign = TextAlign.Center)
            if (!update && m.eta.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(We.primary.copy(alpha = .12f)).padding(horizontal = 16.dp, vertical = 8.dp)) { Text("Back by " + m.eta, color = We.primary, style = T.small) }
            }
        }
    }
}
