package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.MediaKinds
import com.kafisir.wetalks.ui.*

/** The "tap to download" tile: a circle with a down arrow and, when the sender's phone told us, the size ("2.4 MB"). */
@Composable
fun DownloadChip(size: Long, big: Boolean = true, light: Boolean = false) {
    val d: Dp = if (big) 52.dp else 38.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(d).clip(CircleShape).background(if (light) Color(0x99000000) else We.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(Ic.Download, if (light) Color.White else We.primary, if (big) 28.dp else 20.dp)
        }
        if (size > 0) Text(MediaKinds.bytes(size), color = if (light) Color.White else We.text2, style = T.small,
            modifier = Modifier.padding(top = 5.dp).then(if (light) Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 1.dp) else Modifier))
        else if (big) Text("Tap to download", color = if (light) Color.White else We.text2, style = T.small, modifier = Modifier.padding(top = 5.dp))
    }
}
