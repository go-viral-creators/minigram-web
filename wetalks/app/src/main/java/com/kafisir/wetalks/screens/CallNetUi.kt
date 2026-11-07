package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.CallNetState
import com.kafisir.wetalks.ui.T
import com.kafisir.wetalks.ui.Text

/** Signal bars (1 to 4) for the call's network quality; nothing is shown until the first measurement. */
@Composable
fun NetBars(modifier: Modifier = Modifier) {
    val n = CallNetState.bars
    if (n <= 0) return
    val on = when (n) { 1 -> Color(0xFFEF4444); 2 -> Color(0xFFF59E0B); else -> Color(0xFF22C55E) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..4) Box(Modifier.width(5.dp).height((5 + i * 4).dp).clip(RoundedCornerShape(2.dp)).background(if (i <= n) on else Color.White.copy(alpha = .25f)))
    }
}

/** The pill shown when video was paused to keep the call going (the bars are on the top). */
@Composable
fun NetBanner(modifier: Modifier = Modifier) {
    if (!CallNetState.videoPaused) return
    Text("Poor connection: video paused to keep the call going", color = Color.White, style = T.small, textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xCCB45309)).padding(horizontal = 14.dp, vertical = 8.dp))
}
