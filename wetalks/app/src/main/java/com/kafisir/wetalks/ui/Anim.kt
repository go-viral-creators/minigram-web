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
    class Pack(val name: String, val codes: List<String>, val locked: Boolean = false)
    val packs = listOf(
        Pack("Animated", listOf("1f600", "1f60e", "1f970", "1f618", "1f973", "1f62d", "1f621", "1f631", "1f914", "1f917", "1f634", "1f44f", "1f4aa", "270c_fe0f", "1f91d", "1f64c",
            "1f48b", "1f496", "1f31f", "1f680", "1f3c6", "1f381", "2615", "1f607", "1f60b", "1f92f", "1f929", "1f60d", "1f60a", "1f642", "1f609", "1f60f", "1f612", "1f644", "1f624", "1f630", "1f975", "1f976", "1f92a", "1f911", "1f912", "1f637", "1f974", "1f61c")),
        Pack("Hearts", listOf("2764_fe0f", "1f9e1", "1f49b", "1f49a", "1f499", "1f49c", "1f90d", "1f5a4", "1f493", "1f495", "1f498", "1f49e", "1f48e", "1f48d", "1f48b", "1f63b")),
        Pack("Hands", listOf("1f44d", "1f44e", "1f44b", "1f918", "1f919", "1f44c", "1f91e", "1f90f", "1f44a", "1f64f", "1f91d", "270c_fe0f", "1f44f", "1f64c", "1f4aa")),
        Pack("Nature", listOf("1f308", "1f30a", "1f338", "1f339", "1f340", "1f335", "1f31e", "2b50", "26a1", "2744_fe0f", "1f525", "1f4a5", "1f4a1", "1f4ac", "1f38a", "1f389")),
        Pack("Animals", listOf("1f431", "1f43c", "1f984", "1f98b", "1f423", "1f981", "1f438", "1f98a", "1f43b", "1f43a", "1f427", "1f40d", "1f422", "1f419", "1f42c", "1f648", "1f649", "1f64a"), locked = true),
        Pack("Food", listOf("1f355", "1f354", "1f366", "1f37f", "1f349", "1f353", "1f369", "1f382", "1f377", "1f36a", "1f35c", "2615"), locked = true),
        Pack("Spooky", listOf("1f47b", "1f480", "1f47d", "1f916", "1f383", "1f921", "1f608", "1f47f", "1f4a9", "1f4a3", "1f52e", "1f6f8"), locked = true),
        Pack("Fun", listOf("26bd", "1f3cf", "1f451", "1f3b8", "1f4f8", "1f3af", "1f3ac", "1f3b2", "1f697", "2708_fe0f", "1f4b8", "1f3c6"), locked = true),
    )
    val stickers get() = packs[0].codes
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
