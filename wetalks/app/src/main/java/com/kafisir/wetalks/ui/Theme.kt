package com.kafisir.wetalks.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.R

/** Cobalt Pro palette. */
@Immutable
class WeColors(
    val dark: Boolean, val primary: Color, val onPrimary: Color, val bg: Color, val surface: Color, val appBar: Color, val onAppBar: Color,
    val text: Color, val text2: Color, val line: Color, val sent: Color, val onSent: Color, val recv: Color, val onRecv: Color,
    val danger: Color, val ok: Color, val field: Color,
)

val LightWe = WeColors(false, Color(0xFF1F6FEB), Color.White, Color.White, Color.White, Color(0xFF1F6FEB), Color.White,
    Color(0xFF0F1B2D), Color(0xFF6B7A90), Color(0xFFEEF0F4), Color(0xFFDCEBFF), Color(0xFF0F1B2D), Color(0xFFF1F3F7), Color(0xFF0F1B2D),
    Color(0xFFE5484D), Color(0xFF12B76A), Color(0xFFF4F6FA))
val DarkWe = WeColors(true, Color(0xFF4F9CFF), Color(0xFF06101C), Color(0xFF0E1621), Color(0xFF0E1621), Color(0xFF17212B), Color(0xFFE8EEF5),
    Color(0xFFE8EEF5), Color(0xFF8A9BB0), Color(0xFF1F2C3A), Color(0xFF21456E), Color(0xFFEAF2FB), Color(0xFF1B2735), Color(0xFFE8EEF5),
    Color(0xFFFF6B70), Color(0xFF3DD68C), Color(0xFF17212B))

val LocalWe = staticCompositionLocalOf { LightWe }
val We: WeColors @Composable get() = LocalWe.current

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold))

object T {
    val title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    val h1 = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp)
    val name = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp)
    val tiny = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    val btn = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

/** 0 = follow system, 1 = light, 2 = dark */
object ThemeMode {
    val mode = mutableIntStateOf(0)
    fun load(c: Context) { mode.intValue = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).getInt("theme", 0) }
    fun set(c: Context, m: Int) { mode.intValue = m; c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).edit().putInt("theme", m).apply() }
}

/** Accent colours (light / dark variants), chat text size and bubble shape. */
object UiPrefs {
    val accents = listOf("Blue" to (0xFF1F6FEB to 0xFF4F9CFF), "Violet" to (0xFF6D4AFF to 0xFF9B85FF), "Green" to (0xFF12A66A to 0xFF3DD68C),
        "Orange" to (0xFFF2733A to 0xFFFF9A5C), "Pink" to (0xFFE0457B to 0xFFFF7DA8), "Teal" to (0xFF0E9AA7 to 0xFF38D0DC))
    val accent = mutableIntStateOf(0)
    val textScale = androidx.compose.runtime.mutableFloatStateOf(1f)
    val bubble = mutableIntStateOf(16)
    val wallpaper = mutableIntStateOf(0)
    val bubbleStyle = mutableIntStateOf(0)
    val dim = androidx.compose.runtime.mutableFloatStateOf(0f)
    val blur = mutableIntStateOf(0)
    val customVer = mutableIntStateOf(0)
    val compact = androidx.compose.runtime.mutableStateOf(false)
    val linkPreview = androidx.compose.runtime.mutableStateOf(true)
    val markdown = androidx.compose.runtime.mutableStateOf(true)
    var dir: java.io.File? = null
    private fun sp(c: Context) = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE)
    fun load(c: Context) { val s = sp(c); accent.intValue = s.getInt("accent", 0); textScale.floatValue = s.getFloat("tscale", 1f); bubble.intValue = s.getInt("bubble", 16); wallpaper.intValue = s.getInt("wall", 0)
        bubbleStyle.intValue = s.getInt("bstyle", 0); dim.floatValue = s.getFloat("dim", 0f); blur.intValue = s.getInt("blur", 0); compact.value = s.getBoolean("compact", false); linkPreview.value = s.getBoolean("linkprev", true); markdown.value = s.getBoolean("md", true); dir = c.filesDir }
    fun setBubbleStyle(c: Context, v: Int) { bubbleStyle.intValue = v; sp(c).edit().putInt("bstyle", v).apply() }
    fun setDim(c: Context, v: Float) { dim.floatValue = v; sp(c).edit().putFloat("dim", v).apply() }
    fun setBlur(c: Context, v: Int) { blur.intValue = v; sp(c).edit().putInt("blur", v).apply() }
    fun setCompact(c: Context, v: Boolean) { compact.value = v; sp(c).edit().putBoolean("compact", v).apply() }
    fun setMarkdown(c: Context, v: Boolean) { markdown.value = v; sp(c).edit().putBoolean("md", v).apply() }
    fun setLinkPreview(c: Context, v: Boolean) { linkPreview.value = v; sp(c).edit().putBoolean("linkprev", v).apply() }
    fun setAccent(c: Context, i: Int) { accent.intValue = i; sp(c).edit().putInt("accent", i).apply() }
    fun setTextScale(c: Context, v: Float) { textScale.floatValue = v; sp(c).edit().putFloat("tscale", v).apply() }
    fun setBubble(c: Context, v: Int) { bubble.intValue = v; sp(c).edit().putInt("bubble", v).apply() }
    fun setWallpaper(c: Context, v: Int) { wallpaper.intValue = v; sp(c).edit().putInt("wall", v).apply() }
}

private fun WeColors.accented(a: Color): WeColors {
    val sent = if (dark) androidx.compose.ui.graphics.lerp(Color(0xFF17212B), a, .42f) else androidx.compose.ui.graphics.lerp(Color.White, a, .17f)
    return WeColors(dark, a, onPrimary, bg, surface, if (dark) appBar else a, onAppBar, text, text2, line, sent, onSent, recv, onRecv, danger, ok, field)
}

@Composable
fun WeTheme(content: @Composable () -> Unit) {
    val m by ThemeMode.mode
    val ai by UiPrefs.accent
    val dark = when (m) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val acc = UiPrefs.accents[ai.coerceIn(0, UiPrefs.accents.lastIndex)].second
    val c = (if (dark) DarkWe else LightWe).accented(Color(if (dark) acc.second else acc.first))
    val scheme = if (dark) darkColorScheme(primary = c.primary, background = c.bg, surface = c.surface, onSurface = c.text)
    else lightColorScheme(primary = c.primary, background = c.bg, surface = c.surface, onSurface = c.text)
    CompositionLocalProvider(LocalWe provides c) { MaterialTheme(colorScheme = scheme, content = content) }
}
