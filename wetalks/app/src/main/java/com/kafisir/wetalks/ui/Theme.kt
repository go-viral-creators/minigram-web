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

@Composable
fun WeTheme(content: @Composable () -> Unit) {
    val m by ThemeMode.mode
    val dark = when (m) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val c = if (dark) DarkWe else LightWe
    val scheme = if (dark) darkColorScheme(primary = c.primary, background = c.bg, surface = c.surface, onSurface = c.text)
    else lightColorScheme(primary = c.primary, background = c.bg, surface = c.surface, onSurface = c.text)
    CompositionLocalProvider(LocalWe provides c) { MaterialTheme(colorScheme = scheme, content = content) }
}
