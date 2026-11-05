package com.kafisir.wetalks.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

/** Animated emoji (Google Noto Animated Emoji, CC BY 4.0), bundled as Lottie JSON in assets/anim. */
object Anim {
    val reactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "🎉", "👎", "💯")
    val stickers = listOf("1f600", "1f60e", "1f970", "1f618", "1f973", "1f62d", "1f621", "1f631", "1f914", "1f917", "1f634", "1f44f", "1f4aa", "270c_fe0f", "1f91d", "1f64c",
        "1f48b", "1f496", "1f31f", "1f680", "1f3c6", "1f381", "2615", "1f607", "1f60b", "1f92f", "1f929", "1f60d")
    fun code(emoji: String): String = emoji.codePoints().toArray().joinToString("_") { Integer.toHexString(it) }
}

@Composable
fun AnimEmoji(code: String, size: Dp, loop: Boolean = true, modifier: Modifier = Modifier) {
    val comp by rememberLottieComposition(LottieCompositionSpec.Asset("anim/$code.json"))
    val progress by animateLottieCompositionAsState(comp, iterations = if (loop) LottieConstants.IterateForever else 1)
    if (comp != null) LottieAnimation(comp, { progress }, modifier.size(size))
    else Text("", fontSize = (size.value * .7f).sp)
}

@Composable
fun ReactionEmoji(emoji: String, size: Dp, loop: Boolean = true) {
    val code = remember(emoji) { Anim.code(emoji) }
    AnimEmoji(code, size, loop)
}
