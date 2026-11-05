package com.kafisir.wetalks.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** One procedurally drawn chat background. [draw] receives (dark mode, accent colour). */
class Wall(val name: String, val locked: Boolean, val draw: DrawScope.(Boolean, Color) -> Unit)

/** One shared animation clock (seconds). Animated backgrounds and bubbles read it while drawing, so only they redraw each frame. */
object Clock { var t by androidx.compose.runtime.mutableFloatStateOf(0f) }

class AWall(val name: String, val locked: Boolean, val draw: DrawScope.(Float, Boolean, Color) -> Unit)

object AWalls {
    const val BASE = 200
    private fun DrawScope.blob(c: Color, x: Float, y: Float, r: Float, a: Float) = drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
    private fun fr(t: Float) = t - kotlin.math.floor(t)
    val all: List<AWall> = listOf(
        AWall("Drift", false) { t, d, a -> repeat(4) { i -> blob(lerp(a, Color(0xFF9B6BFF), i / 4f), size.width * (.5f + .4f * sin(t * .25f + i * 1.7f)), size.height * (.5f + .4f * cos(t * .2f + i * 2.3f)), size.width * .8f, if (d) .30f else .22f) } },
        AWall("Rising bubbles", false) { t, _, a -> val r = Random(21); repeat(26) { val sp = .05f + r.nextFloat() * .08f; val ph = r.nextFloat(); val y = size.height * (1.1f - fr(t * sp + ph) * 1.25f); val x = size.width * r.nextFloat() + 14f * sin(t + ph * 9); drawCircle(a.copy(alpha = .22f), (6 + r.nextInt(18)) * Walls.density, Offset(x, y), style = Stroke(1.5f * Walls.density)) } },
        AWall("Snowfall", false) { t, d, _ -> val r = Random(5); repeat(70) { val sp = .06f + r.nextFloat() * .1f; val ph = r.nextFloat(); val y = size.height * (fr(t * sp + ph) * 1.1f - .05f); val x = size.width * r.nextFloat() + 18f * sin(t * .8f + ph * 10); drawCircle((if (d) Color.White else Color(0xFF8FB2FF)).copy(alpha = .55f), (1.5f + r.nextFloat() * 2.5f) * Walls.density, Offset(x, y)) } },
        AWall("Fireflies", false) { t, d, a -> val r = Random(8); repeat(34) { val x = size.width * (r.nextFloat() + .06f * sin(t * .4f + it)) ; val y = size.height * (r.nextFloat() + .05f * cos(t * .35f + it * 2)); val al = (.35f + .65f * (.5f + .5f * sin(t * 1.6f + it * 1.9f))); blob(if (d) Color(0xFFFFE066) else a, x, y, 26 * Walls.density, al * .5f); drawCircle(if (d) Color(0xFFFFF3B0) else a, 2.2f * Walls.density, Offset(x, y)) } },
        AWall("Flowing waves", false) { t, _, a -> for (k in 0 until 7) { val p = Path(); val y0 = size.height * (k + 1) / 8f; p.moveTo(0f, y0); var x = 0f; while (x <= size.width) { p.lineTo(x, y0 + 18 * Walls.density * sin(x / size.width * 5 + t * (.5f + k * .08f) + k)); x += 8f }; drawPath(p, a.copy(alpha = .16f), style = Stroke(1.8f * Walls.density, cap = StrokeCap.Round)) } },
        AWall("Aurora flow", true) { t, _, _ -> drawRect(Color(0xFF071427)); blob(Color(0xFF16D9A5), size.width * (.3f + .25f * sin(t * .2f)), size.height * .25f, size.width, .55f); blob(Color(0xFF7B5CFF), size.width * (.75f - .25f * sin(t * .17f)), size.height * (.5f + .1f * cos(t * .3f)), size.width * 1.05f, .5f); blob(Color(0xFFFF5CA8), size.width * (.4f + .2f * cos(t * .23f)), size.height * .88f, size.width * .85f, .4f) },
        AWall("Twinkling stars", true) { t, _, _ -> val r = Random(3); drawRect(Brush.verticalGradient(listOf(Color(0xFF05081C), Color(0xFF1B2A5A)))); repeat(130) { val x = r.nextFloat() * size.width; val y = r.nextFloat() * size.height; val ph = r.nextFloat() * 6f; drawCircle(Color.White.copy(alpha = (.2f + .8f * (.5f + .5f * sin(t * (.8f + r.nextFloat() * 1.8f) + ph)))), (.6f + r.nextFloat() * 2f) * Walls.density, Offset(x, y)) } },
        AWall("Rain", true) { t, _, _ -> drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2A3C), Color(0xFF3B5368)))); val r = Random(12); repeat(80) { val sp = .6f + r.nextFloat() * .6f; val x = size.width * r.nextFloat(); val y = size.height * (fr(t * sp + r.nextFloat()) * 1.2f - .1f); drawLine(Color.White.copy(alpha = .25f), Offset(x, y), Offset(x - 6f, y + 22f * Walls.density), 1.4f * Walls.density) } },
        AWall("Lava lamp", true) { t, _, _ -> drawRect(Color(0xFF2A0A1A)); repeat(5) { i -> blob(listOf(Color(0xFFFF4D2E), Color(0xFFFFB02E), Color(0xFFFF2E92), Color(0xFFFF6B3D), Color(0xFFFFD166))[i], size.width * (.5f + .35f * sin(t * (.18f + i * .05f) + i)), size.height * (.5f + .42f * cos(t * (.15f + i * .04f) + i * 2)), size.width * .55f, .75f) } },
        AWall("Digital rain", true) { t, _, _ -> drawRect(Color(0xFF02120A)); val r = Random(4); val cols = (size.width / (14 * Walls.density)).toInt(); for (c in 0 until cols) { val sp = .15f + r.nextFloat() * .3f; val ph = r.nextFloat(); val head = size.height * (fr(t * sp + ph) * 1.3f); val x = c * 14 * Walls.density + 7 * Walls.density; for (k in 0 until 10) drawCircle(Color(0xFF3DFF8F).copy(alpha = (1f - k / 10f) * .7f), 2.2f * Walls.density, Offset(x, head - k * 12 * Walls.density)) } },
    )
}

object Walls {
    const val CUSTOM = 100
    private fun DrawScope.vgrad(c1: Color, c2: Color, c3: Color? = null) = drawRect(Brush.verticalGradient(listOfNotNull(c1, c2, c3)))
    private fun ink(dark: Boolean) = if (dark) Color.White else Color(0xFF0B1220)
    private fun DrawScope.blob(c: Color, x: Float, y: Float, r: Float, a: Float = .35f) =
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
    private fun DrawScope.stars(n: Int, seed: Int, a: Float = .8f) { val r = Random(seed); repeat(n) { drawCircle(Color.White.copy(alpha = a * (.25f + r.nextFloat() * .75f)), 0.6f + r.nextFloat() * 2.2f, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) } }
    private fun DrawScope.hills(c: Color, baseY: Float, amp: Float, seed: Int, a: Float) {
        val r = Random(seed); val ph = r.nextFloat() * 6f; val f = 1.2f + r.nextFloat()
        val p = Path().apply { moveTo(0f, size.height); var x = 0f; while (x <= size.width + 8) { lineTo(x, baseY + amp * sin(ph + x / size.width * f * 2 * PI.toFloat())); x += 8f }; lineTo(size.width, size.height); close() }
        drawPath(p, c.copy(alpha = a))
    }

    val all: List<Wall> = listOf(
        Wall("Plain", false) { _, _ -> },
        Wall("Soft", false) { _, a -> vgrad(a.copy(alpha = .10f), Color.Transparent) },
        Wall("Tinted", false) { _, a -> vgrad(a.copy(alpha = .22f), a.copy(alpha = .06f)) },
        Wall("Dots", false) { d, _ -> val s = 22.dpPx(); var y = s / 2; while (y < size.height) { var x = s / 2 + if (((y / s).toInt() and 1) == 1) s / 2 else 0f; while (x < size.width) { drawCircle(ink(d).copy(alpha = .10f), 1.5.dpPx(), Offset(x, y)); x += s }; y += s } },
        Wall("Grid", false) { d, _ -> val s = 30.dpPx(); var x = 0f; while (x < size.width) { drawLine(ink(d).copy(alpha = .06f), Offset(x, 0f), Offset(x, size.height), 1f); x += s }; var y = 0f; while (y < size.height) { drawLine(ink(d).copy(alpha = .06f), Offset(0f, y), Offset(size.width, y), 1f); y += s } },
        Wall("Waves", false) { _, a -> for (k in 0 until 9) { val p = Path(); val y0 = size.height * (k + 1) / 10f; p.moveTo(0f, y0); var x = 0f; while (x <= size.width) { p.lineTo(x, y0 + 16.dpPx() * sin(x / size.width * 4 * PI.toFloat() + k)); x += 6f }; drawPath(p, a.copy(alpha = .13f), style = Stroke(1.6f.dpPx(), cap = StrokeCap.Round)) } },
        Wall("Doodles", false) { d, a -> val r = Random(7); val c = a.copy(alpha = .18f); repeat(70) {
            val x = r.nextFloat() * size.width; val y = r.nextFloat() * size.height; val s = (7 + r.nextInt(9)).dpPx(); val st = Stroke(1.6f.dpPx(), cap = StrokeCap.Round)
            when (r.nextInt(4)) { 0 -> drawCircle(c, s / 2, Offset(x, y), style = st); 1 -> { drawLine(c, Offset(x - s / 2, y), Offset(x + s / 2, y), st.width); drawLine(c, Offset(x, y - s / 2), Offset(x, y + s / 2), st.width) }
                2 -> drawPath(Path().apply { moveTo(x, y - s / 2); lineTo(x + s / 2, y + s / 2); lineTo(x - s / 2, y + s / 2); close() }, c, style = st)
                else -> rotate(r.nextFloat() * 90f, Offset(x, y)) { drawRoundRect(c, Offset(x - s / 2, y - s / 3), Size(s, s * .66f), CornerRadius(3f), style = st) } } } },
        Wall("Stripes", false) { d, _ -> val s = 26.dpPx(); var x = -size.height; while (x < size.width) { drawLine(ink(d).copy(alpha = .06f), Offset(x, size.height), Offset(x + size.height, 0f), 1.6f); x += s } },
        Wall("Rings", false) { _, a -> val r = Random(3); repeat(26) { drawCircle(a.copy(alpha = .10f), (14 + r.nextInt(70)).dpPx(), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height), style = Stroke(1.6f.dpPx())) } },
        Wall("Hills", false) { _, a -> vgrad(a.copy(alpha = .08f), Color.Transparent); hills(a, size.height * .72f, 26.dpPx(), 1, .10f); hills(a, size.height * .82f, 22.dpPx(), 2, .14f); hills(a, size.height * .91f, 18.dpPx(), 3, .20f) },
        Wall("Dusk", false) { d, a -> vgrad(lerp(a, Color(0xFF9B59B6), .45f).copy(alpha = if (d) .38f else .28f), lerp(a, Color(0xFFFF9A5C), .4f).copy(alpha = .10f), Color.Transparent) },
        Wall("Paper", false) { d, _ -> val r = Random(11); repeat((size.width * size.height / 700).toInt().coerceAtMost(2600)) { drawCircle(ink(d).copy(alpha = .05f + r.nextFloat() * .06f), .6f + r.nextFloat() * 1.1f, Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) } },

        // ---- locked (WeTalks Plus)
        Wall("Aurora", true) { d, _ -> drawRect(if (d) Color(0xFF0A1424) else Color(0xFFEAF6F8)); blob(Color(0xFF16D9A5), size.width * .2f, size.height * .2f, size.width * .9f); blob(Color(0xFF7B5CFF), size.width * .85f, size.height * .45f, size.width); blob(Color(0xFFFF5CA8), size.width * .3f, size.height * .85f, size.width * .8f, .28f) },
        Wall("Starry night", true) { _, _ -> vgrad(Color(0xFF070B1F), Color(0xFF1B2A5A)); stars(140, 5) },
        Wall("Sunset", true) { _, _ -> vgrad(Color(0xFFFF7E5F), Color(0xFFFEB47B), Color(0xFFB65C9E)); hills(Color(0xFF2B1B3F), size.height * .85f, 24.dpPx(), 4, .55f) },
        Wall("Ocean", true) { _, _ -> vgrad(Color(0xFF38B6FF), Color(0xFF0A4D8C), Color(0xFF062A52)); for (k in 0 until 7) hills(Color.White, size.height * (.25f + k * .11f), 14.dpPx(), 20 + k, .06f) },
        Wall("Forest", true) { _, _ -> vgrad(Color(0xFFBFE8C6), Color(0xFF2F7D4F)); val r = Random(9); repeat(26) { val x = r.nextFloat() * size.width; val h = (60 + r.nextInt(120)).dpPx(); val w = h * .45f; val y = size.height; drawPath(Path().apply { moveTo(x, y - h); lineTo(x + w / 2, y); lineTo(x - w / 2, y); close() }, Color(0xFF14452A).copy(alpha = .35f + r.nextFloat() * .3f)) } },
        Wall("Neon grid", true) { _, _ -> vgrad(Color(0xFF12002B), Color(0xFF2B0057)); val hy = size.height * .55f; for (i in 0..14) { val x = size.width * i / 14f; drawLine(Color(0xFFFF2BD6).copy(alpha = .55f), Offset(size.width / 2, hy), Offset((x - size.width / 2) * 6 + size.width / 2, size.height), 1.4f) }; var y = hy; var st = 8f; while (y < size.height) { drawLine(Color(0xFF2BF3FF).copy(alpha = .5f), Offset(0f, y), Offset(size.width, y), 1.4f); y += st; st *= 1.35f } },
        Wall("Lava", true) { _, _ -> drawRect(Color(0xFF2A0A0A)); blob(Color(0xFFFF4D2E), size.width * .3f, size.height * .3f, size.width, .6f); blob(Color(0xFFFFB02E), size.width * .8f, size.height * .65f, size.width * .9f, .5f); blob(Color(0xFFFF2E63), size.width * .2f, size.height * .9f, size.width * .8f, .5f) },
        Wall("Candy", true) { _, _ -> drawRect(Color(0xFFFFE3F1)); val s = 34.dpPx(); var x = -size.height; var i = 0; while (x < size.width) { rotate(0f) { drawLine(if (i % 2 == 0) Color(0xFFFFB3D9) else Color(0xFFB8F2E6), Offset(x, size.height), Offset(x + size.height, 0f), s * .55f) }; x += s; i++ } },
        Wall("Mint", true) { _, _ -> vgrad(Color(0xFFD7FBEA), Color(0xFF8EE3C0)); val r = Random(2); repeat(24) { drawCircle(Color.White.copy(alpha = .22f), (10 + r.nextInt(46)).dpPx(), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) } },
        Wall("Nebula", true) { _, _ -> drawRect(Color(0xFF06071A)); blob(Color(0xFF6C3BFF), size.width * .25f, size.height * .25f, size.width, .55f); blob(Color(0xFF00B8FF), size.width * .8f, size.height * .5f, size.width * .9f, .4f); blob(Color(0xFFFF3BAC), size.width * .4f, size.height * .85f, size.width * .9f, .4f); stars(120, 8) },
        Wall("Honeycomb", true) { d, a -> drawRect(if (d) Color(0xFF14110A) else Color(0xFFFFF6E0)); val s = 30.dpPx(); val h = s * .866f; var row = 0; var y = 0f; while (y < size.height + s) { var x = if (row % 2 == 0) 0f else h; while (x < size.width + s) { val p = Path(); for (k in 0..5) { val an = PI.toFloat() / 3 * k + PI.toFloat() / 6; val px = x + cos(an) * s * .55f; val py = y + sin(an) * s * .55f; if (k == 0) p.moveTo(px, py) else p.lineTo(px, py) }; p.close(); drawPath(p, Color(0xFFF2A900).copy(alpha = .35f), style = Stroke(1.6f.dpPx())); x += h * 2 }; y += s * .82f; row++ } },
        Wall("Topo", true) { d, _ -> drawRect(if (d) Color(0xFF0F1A14) else Color(0xFFF1F5EA)); val cx = size.width * .65f; val cy = size.height * .4f; for (i in 1..18) { val p = Path(); val rr = i * 22.dpPx(); for (k in 0..120) { val an = k / 120f * 2 * PI.toFloat(); val w = 1f + .12f * sin(an * 3 + i * .5f) + .06f * sin(an * 5 - i * .3f); val x = cx + cos(an) * rr * w * 1.1f; val y = cy + sin(an) * rr * w; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }; p.close(); drawPath(p, Color(0xFF3F9B6B).copy(alpha = .28f), style = Stroke(1.4f.dpPx())) } },
        Wall("Confetti", true) { d, _ -> drawRect(if (d) Color(0xFF14101F) else Color(0xFFFFFBF2)); val r = Random(4); val cols = listOf(Color(0xFFFF5C8A), Color(0xFFFFC53D), Color(0xFF3DD6A3), Color(0xFF4F9CFF), Color(0xFF9B6BFF)); repeat(90) { val x = r.nextFloat() * size.width; val y = r.nextFloat() * size.height; rotate(r.nextFloat() * 180f, Offset(x, y)) { drawRoundRect(cols[r.nextInt(cols.size)].copy(alpha = .75f), Offset(x, y), Size(4.dpPx() + r.nextInt(6).dpPx(), 3.dpPx()), CornerRadius(2f)) } } },
    )

    fun isLocked(id: Int) = if (id >= AWalls.BASE) AWalls.all.getOrNull(id - AWalls.BASE)?.locked == true else all.getOrNull(id)?.locked == true
    fun nameOf(id: Int) = if (id >= AWalls.BASE) AWalls.all.getOrNull(id - AWalls.BASE)?.name ?: "" else all.getOrNull(id)?.name ?: ""
    private fun Number.dpPx(): Float = toFloat() * density
    var density = 3f

    // ---- custom image
    private fun file(dir: File) = File(dir, "chat_bg.jpg")
    fun saveCustom(dir: File, bytes: ByteArray) { file(dir).writeBytes(bytes) }
    fun loadCustom(dir: File, blur: Int): ImageBitmap? {
        val f = file(dir); if (!f.exists()) return null
        var bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        if (blur > 0) { val d = 2 + blur * 3; bmp = Bitmap.createScaledBitmap(bmp, max(8, bmp.width / d), max(8, bmp.height / d), true) }
        return bmp.asImageBitmap()
    }
}

/** Solid + procedural pattern (or the user's own picture) behind the messages. */
@Composable
fun Modifier.chatWallpaper(): Modifier = wallpaperFor(UiPrefs.wallpaper.intValue, UiPrefs.dim.floatValue, UiPrefs.blur.intValue, UiPrefs.customVer.intValue)

@Composable
fun Modifier.wallpaperFor(id: Int, dim: Float, blur: Int, ver: Int): Modifier {
    val accent = We.primary; val dark = We.dark; val bg = We.bg
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Walls.density = density
    val custom = remember(id, blur, ver) { if (id == Walls.CUSTOM) UiPrefs.dir?.let { Walls.loadCustom(it, blur) } else null }
    val wall = Walls.all.getOrNull(id)
    val awall = if (id >= AWalls.BASE) AWalls.all.getOrNull(id - AWalls.BASE) else null
    return this.background(bg).drawWithCache {
        onDrawBehind {
            if (awall != null) { awall.draw.invoke(this, Clock.t, dark, accent); if (dim > 0f) drawRect(Color.Black.copy(alpha = dim)); return@onDrawBehind }
            if (custom != null) {
                val sc = max(size.width / custom.width, size.height / custom.height)
                val w = custom.width * sc; val h = custom.height * sc
                drawImage(custom, dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), dstSize = IntSize(w.toInt() + 1, h.toInt() + 1), filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
            } else wall?.draw?.invoke(this, dark, accent)
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
        }
    }
}

// ------------------------------------------------------------------------------------------------ bubble looks
class BubbleStyle(val name: String, val locked: Boolean)

object Bubbles {
    /** Dark locked looks need light text. */
    fun fgFor(style: Int, mine: Boolean): Color? = if (style == 7 || style == 9 || style == 19) Color.White else null
    fun animated(style: Int) = style >= 12

    val all = listOf(BubbleStyle("Classic", false), BubbleStyle("Gradient", false), BubbleStyle("Outline", false), BubbleStyle("Dots", false), BubbleStyle("Stripes", false), BubbleStyle("Paper", false), BubbleStyle("Glass", false),
        BubbleStyle("Neon", true), BubbleStyle("Aurora", true), BubbleStyle("Carbon", true), BubbleStyle("Candy", true), BubbleStyle("Sunrise", true),
        BubbleStyle("Shimmer", false), BubbleStyle("Pulse", false), BubbleStyle("Flow", false), BubbleStyle("Sparkle", false), BubbleStyle("Wave", false),
        BubbleStyle("Rainbow", true), BubbleStyle("Float", true), BubbleStyle("Neon pulse", true), BubbleStyle("Liquid", true), BubbleStyle("Glitter", true))
}

/** Fills a bubble according to the chosen style; [fg] is the text colour (used to tint textures). */
@Composable
fun Modifier.bubbleLook(mine: Boolean, shape: Shape, override: Int? = null): Modifier {
    val style = override ?: UiPrefs.bubbleStyle.intValue
    val base = if (mine) We.sent else We.recv
    val fg = if (mine) We.onSent else We.onRecv
    val accent = We.primary
    val darkNow = We.dark
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    fun dp(v: Float) = v * density
    val clipped = this.then(Modifier.clip(shape))
    return when (style) {
        1 -> clipped.background(Brush.linearGradient(listOf(base, lerp(base, accent, if (mine) .38f else .14f))))
        2 -> clipped.background(base.copy(alpha = .35f)).border2(accent.copy(alpha = if (mine) .75f else .35f), shape)
        3 -> clipped.background(base).drawWithCache { onDrawBehind { val s = dp(9f); var y = s / 2; while (y < size.height) { var x = s / 2; while (x < size.width) { drawCircle(fg.copy(alpha = .09f), dp(1.3f), Offset(x, y)); x += s }; y += s } } }
        4 -> clipped.background(base).drawWithCache { onDrawBehind { val s = dp(9f); var x = -size.height; while (x < size.width) { drawLine(fg.copy(alpha = .07f), Offset(x, size.height), Offset(x + size.height, 0f), dp(1.4f)); x += s } } }
        5 -> clipped.background(base).drawWithCache { onDrawBehind { val r = Random(size.width.toInt() + size.height.toInt()); repeat(((size.width * size.height) / dp(40f) / dp(1f)).toInt().coerceIn(20, 500)) { drawCircle(fg.copy(alpha = .06f + r.nextFloat() * .06f), dp(.5f) + r.nextFloat() * dp(.7f), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) } } }
        6 -> clipped.background(Brush.verticalGradient(listOf(base.copy(alpha = .78f), base.copy(alpha = .48f)))).border2(Color.White.copy(alpha = if (We.dark) .18f else .6f), shape)
        7 -> clipped.background(Color(0xFF0B0B1A)).border2(Color(0xFF28F2FF), shape)
        8 -> clipped.background(Brush.linearGradient(listOf(Color(0xFF16D9A5).copy(alpha = .55f), Color(0xFF7B5CFF).copy(alpha = .55f), Color(0xFFFF5CA8).copy(alpha = .5f))))
        9 -> clipped.background(Color(0xFF1B1B1F)).drawWithCache { onDrawBehind { val s = dp(6f); var y = 0f; while (y < size.height) { var x = if (((y / s).toInt() and 1) == 0) 0f else s / 2; while (x < size.width) { drawRect(Color.White.copy(alpha = .05f), Offset(x, y), Size(s / 2, s / 2)); x += s }; y += s / 2 } } }
        10 -> clipped.background(Brush.linearGradient(listOf(Color(0xFFFFC2E2), Color(0xFFB8F2E6))))
        11 -> clipped.background(Brush.linearGradient(listOf(Color(0xFFFFD36B), Color(0xFFFF8E5C))))
        12 -> clipped.background(base).drawBehind { val w = size.width; val h = size.height; val x = (Clock.t % 2.6f) / 2.6f * (w + h * 2) - h; drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (darkNow) .16f else .55f), Color.Transparent), Offset(x, 0f), Offset(x + h, h))) }
        13 -> clipped.background(base).drawBehind { drawRect(accent.copy(alpha = .05f + .13f * (.5f + .5f * sin(Clock.t * 2.2f)))) }
        14 -> clipped.background(base).drawBehind { val w = size.width; val o = ((Clock.t % 4f) / 4f) * w * 2 - w; drawRect(Brush.linearGradient(listOf(Color.Transparent, accent.copy(alpha = .30f), Color.Transparent), Offset(o, 0f), Offset(o + w, size.height))) }
        15 -> clipped.background(base).drawBehind { val r = Random(size.width.toInt()); repeat(9) { val x = r.nextFloat() * size.width; val y = r.nextFloat() * size.height; val a = (.5f + .5f * sin(Clock.t * 3f + it * 1.7f)); drawCircle(Color.White.copy(alpha = a * .75f), dp(1.6f), Offset(x, y)); drawLine(Color.White.copy(alpha = a * .5f), Offset(x - dp(4f), y), Offset(x + dp(4f), y), dp(.8f)); drawLine(Color.White.copy(alpha = a * .5f), Offset(x, y - dp(4f)), Offset(x, y + dp(4f)), dp(.8f)) } }
        16 -> clipped.background(base).drawBehind { val p = androidx.compose.ui.graphics.Path(); val y0 = size.height * .62f; p.moveTo(0f, size.height); var x = 0f; while (x <= size.width + 6f) { p.lineTo(x, y0 + dp(5f) * sin(x / dp(22f) + Clock.t * 2.4f)); x += 6f }; p.lineTo(size.width, size.height); p.close(); drawPath(p, accent.copy(alpha = .20f)) }
        17 -> clipped.background(base).drawBehind { val h0 = (Clock.t * 45f) % 360f; val cs = listOf(0f, 70f, 140f).map { Color.hsv((h0 + it) % 360f, .5f, 1f).copy(alpha = .55f) }; drawRect(Brush.linearGradient(cs, Offset.Zero, Offset(size.width, size.height))) }
        18 -> clipped.background(base).drawBehind { val r = Random(size.height.toInt()); repeat(7) { val sp = .12f + r.nextFloat() * .18f; val x = r.nextFloat() * size.width; val y = size.height * (1.1f - ((Clock.t * sp + r.nextFloat()) % 1f) * 1.2f); drawCircle(accent.copy(alpha = .30f), dp(3f + r.nextInt(5)), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(dp(1f))) } }
        19 -> clipped.background(Color(0xFF0B0B1A)).border2(Color(0xFFFF2BD6).copy(alpha = .8f), shape).drawBehind { val a = .15f + .35f * (.5f + .5f * sin(Clock.t * 2.6f)); drawRect(Brush.horizontalGradient(listOf(Color(0xFF28F2FF).copy(alpha = a), Color(0xFFFF2BD6).copy(alpha = a)))) }
        20 -> clipped.background(base).drawBehind { val w = size.width; val h = size.height; for (i in 0 until 2) { val c = Offset(w * (.5f + .4f * sin(Clock.t * (.9f + i * .4f) + i * 2)), h * (.5f + .35f * cos(Clock.t * (.7f + i * .3f)))); drawCircle(Brush.radialGradient(listOf((if (i == 0) accent else Color(0xFFFF5CA8)).copy(alpha = .45f), Color.Transparent), c, h), h, c) } }
        21 -> clipped.background(base).drawBehind { val r = Random(size.width.toInt() * 3); repeat(40) { val a = (.5f + .5f * sin(Clock.t * (2f + r.nextFloat() * 3f) + it)); drawCircle(Color(0xFFFFD36B).copy(alpha = a * .8f), dp(.9f), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) } }
        else -> clipped.background(base)
    }
}

private fun Modifier.border2(c: Color, shape: Shape) = this.then(Modifier.border(1.2.dp, c, shape))
