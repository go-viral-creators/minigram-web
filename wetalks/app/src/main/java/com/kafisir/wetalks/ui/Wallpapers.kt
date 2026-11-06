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
        AWall("Soap bubbles", false) { t, d, a -> val r = Random(31); repeat(18) { val sp = .03f + r.nextFloat() * .05f; val ph = r.nextFloat(); val rad = (14 + r.nextInt(34)) * Walls.density; val y = size.height * (1.15f - fr(t * sp + ph) * 1.3f); val x = size.width * r.nextFloat() + 22f * sin(t * .6f + ph * 8); val c = Offset(x, y)
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, lerp(a, Color(0xFFFF8BD1), .5f).copy(alpha = .18f), Color.White.copy(alpha = if (d) .22f else .5f)), c, rad), rad, c); drawCircle(Color.White.copy(alpha = .55f), rad * .14f, Offset(x - rad * .38f, y - rad * .4f)) } },
        AWall("Cherry blossoms", false) { t, d, _ -> drawRect(Brush.verticalGradient(listOf(if (d) Color(0xFF2B1B2F) else Color(0xFFFFEEF4), if (d) Color(0xFF1B1224) else Color(0xFFFFD9E6)))); val r = Random(41); repeat(34) { val sp = .035f + r.nextFloat() * .05f; val ph = r.nextFloat(); val y = size.height * (fr(t * sp + ph) * 1.2f - .1f); val x = size.width * r.nextFloat() + 40f * sin(t * .5f + ph * 9) * Walls.density; val s = (5 + r.nextInt(7)) * Walls.density
            rotate((t * 40f + ph * 360f) % 360f, Offset(x, y)) { drawOval(Color(0xFFFF9EC0).copy(alpha = .75f), Offset(x - s, y - s * .55f), Size(s * 2f, s * 1.1f)); drawOval(Color.White.copy(alpha = .35f), Offset(x - s * .6f, y - s * .3f), Size(s, s * .5f)) } } },
        AWall("Ocean layers", false) { t, d, _ -> drawRect(Brush.verticalGradient(listOf(if (d) Color(0xFF0B2545) else Color(0xFFBFE3FF), if (d) Color(0xFF13315C) else Color(0xFF7CC4F5)))); val cols = listOf(Color(0xFF4DA8E8), Color(0xFF2F86C9), Color(0xFF1F63A8), Color(0xFF144585)); for (k in 0 until 4) { val p = Path(); val y0 = size.height * (.5f + k * .13f); p.moveTo(0f, size.height); var x = 0f; while (x <= size.width + 6f) { p.lineTo(x, y0 + (14 - k * 2) * Walls.density * sin(x / size.width * (3f + k) + t * (.5f + k * .2f) + k)); x += 6f }; p.lineTo(size.width, size.height); p.close(); drawPath(p, cols[k].copy(alpha = .55f)) } },
        AWall("Network", false) { t, d, a -> val r = Random(51); val n = 34; val pts = List(n) { Offset(size.width * (r.nextFloat() + .08f * sin(t * (.3f + r.nextFloat() * .3f) + it)).coerceIn(0f, 1f), size.height * (r.nextFloat() + .08f * cos(t * (.25f + r.nextFloat() * .3f) + it * 2)).coerceIn(0f, 1f)) }; val lim = 120 * Walls.density
            for (i in 0 until n) for (j in i + 1 until n) { val dd = (pts[i] - pts[j]).getDistance(); if (dd < lim) drawLine(a.copy(alpha = .22f * (1f - dd / lim)), pts[i], pts[j], 1.2f * Walls.density) }; pts.forEach { drawCircle(a.copy(alpha = .5f), 2.6f * Walls.density, it) } },
        AWall("Mesh gradient", false) { t, d, _ -> drawRect(if (d) Color(0xFF10131F) else Color(0xFFF6F1FF)); val cs = listOf(Color(0xFF6C63FF), Color(0xFF00C2FF), Color(0xFFFF6FB5), Color(0xFFFFB86B)); cs.forEachIndexed { i, c -> blob(c, size.width * (.5f + .45f * sin(t * (.12f + i * .04f) + i * 1.6f)), size.height * (.5f + .45f * cos(t * (.1f + i * .05f) + i * 2.1f)), size.width * .95f, if (d) .5f else .45f) } },
        AWall("Galaxy", true) { t, _, _ -> drawRect(Color(0xFF04050F)); val c = Offset(size.width * .5f, size.height * .5f); val r = Random(61); repeat(260) { val arm = it % 3; val dist = r.nextFloat(); val ang = dist * 7f + arm * 2.094f + t * .05f * (1.2f - dist); val rad = dist * size.width * .75f; val x = c.x + cos(ang) * rad; val y = c.y + sin(ang) * rad * .55f + (r.nextFloat() - .5f) * 30f; drawCircle(lerp(Color(0xFFFFE9B5), Color(0xFF7B8CFF), dist).copy(alpha = (.25f + .75f * r.nextFloat()) * (1.1f - dist)), (.7f + r.nextFloat() * 1.8f) * Walls.density, Offset(x, y)) }; blob(Color(0xFF7B5CFF), c.x, c.y, size.width * .5f, .35f) },
        AWall("Fireworks", true) { t, _, _ -> drawRect(Brush.verticalGradient(listOf(Color(0xFF050818), Color(0xFF111B3A)))); for (k in 0 until 4) { val per = 3.2f; val tt = ((t + k * .9f) % per) / per; val seed = ((t + k * .9f) / per).toInt() * 7 + k; val rr = Random(seed); val c = Offset(size.width * (.15f + .7f * rr.nextFloat()), size.height * (.12f + .45f * rr.nextFloat())); val col = Color.hsv(rr.nextFloat() * 360f, .65f, 1f)
            if (tt < .25f) { val y = c.y + (1f - tt / .25f) * size.height * .35f; drawCircle(Color.White, 2.5f * Walls.density, Offset(c.x, y)) } else { val e = (tt - .25f) / .75f; repeat(30) { val a = it / 30f * 6.283f; val rad = e * 90 * Walls.density * (.6f + .4f * rr.nextFloat()); drawCircle(col.copy(alpha = (1f - e).coerceIn(0f, 1f)), 2f * Walls.density, Offset(c.x + cos(a) * rad, c.y + sin(a) * rad + e * e * 30 * Walls.density)) } } } },
        AWall("Sun rays", true) { t, _, _ -> drawRect(Brush.verticalGradient(listOf(Color(0xFFFFE29F), Color(0xFFFFA99F), Color(0xFFFF719A)))); val c = Offset(size.width * .5f, size.height * .22f); repeat(14) { val ang = it / 14f * 6.283f + t * .06f; val p = Path().apply { moveTo(c.x, c.y); lineTo(c.x + cos(ang - .09f) * size.height * 1.4f, c.y + sin(ang - .09f) * size.height * 1.4f); lineTo(c.x + cos(ang + .09f) * size.height * 1.4f, c.y + sin(ang + .09f) * size.height * 1.4f); close() }; drawPath(p, Color.White.copy(alpha = .13f)) }; drawCircle(Color.White.copy(alpha = .8f), 38 * Walls.density, c) },
        AWall("Aurora curtains", true) { t, _, _ -> drawRect(Brush.verticalGradient(listOf(Color(0xFF020A1C), Color(0xFF0B2230)))); for (k in 0 until 3) { val col = listOf(Color(0xFF3DFFB0), Color(0xFF58A6FF), Color(0xFFB27BFF))[k]; var x = 0f; while (x < size.width) { val h = size.height * (.35f + .2f * sin(x / size.width * 6f + t * (.4f + k * .15f) + k * 2f)); drawRect(Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = .22f), Color.Transparent), 0f, h), Offset(x, size.height * (.08f + k * .12f)), Size(6f * Walls.density, h)); x += 5f * Walls.density } } },
        AWall("Synthwave", true) { t, _, _ -> drawRect(Brush.verticalGradient(listOf(Color(0xFF1A0033), Color(0xFF5B0E7E), Color(0xFFFF4D8D)))); val hy = size.height * .56f; drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFD36B), Color(0xFFFF4D8D)), hy - 90 * Walls.density, hy), 78 * Walls.density, Offset(size.width / 2, hy - 20 * Walls.density)); drawRect(Color(0xFF1A0033), Offset(0f, hy), Size(size.width, size.height - hy))
            for (i in 0..16) drawLine(Color(0xFFFF2BD6).copy(alpha = .6f), Offset(size.width / 2, hy), Offset((i - 8) * size.width / 3.2f + size.width / 2, size.height), 1.3f * Walls.density); var y = 0f; val off = (t * .35f) % 1f; for (k in 0 until 14) { val f = (k + off) / 14f; y = hy + (size.height - hy) * f * f; drawLine(Color(0xFF2BF3FF).copy(alpha = .55f), Offset(0f, y), Offset(size.width, y), 1.3f * Walls.density) } },
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
    fun fgFor(style: Int, mine: Boolean): Color? = if (style == 7 || style == 9 || style == 19 || style == 27 || style == 29 || style == 30) Color.White else null
    fun animated(style: Int) = style >= 12

    val all = listOf(BubbleStyle("Classic", false), BubbleStyle("Gradient", false), BubbleStyle("Outline", false), BubbleStyle("Dots", false), BubbleStyle("Stripes", false), BubbleStyle("Paper", false), BubbleStyle("Glass", false),
        BubbleStyle("Neon", true), BubbleStyle("Aurora", true), BubbleStyle("Carbon", true), BubbleStyle("Candy", true), BubbleStyle("Sunrise", true),
        BubbleStyle("Shimmer", false), BubbleStyle("Pulse", false), BubbleStyle("Flow", false), BubbleStyle("Sparkle", false), BubbleStyle("Wave", false),
        BubbleStyle("Rainbow", true), BubbleStyle("Float", true), BubbleStyle("Neon pulse", true), BubbleStyle("Liquid", true), BubbleStyle("Glitter", true),
        BubbleStyle("Spin", false), BubbleStyle("Aurora glass", false), BubbleStyle("Ripple", false), BubbleStyle("Scanline", false), BubbleStyle("Hearts", false),
        BubbleStyle("Fire", true), BubbleStyle("Ice", true), BubbleStyle("Electric", true), BubbleStyle("Galaxy", true), BubbleStyle("Gold shine", true))
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
        22 -> clipped.background(base).drawBehind { val c = Offset(size.width / 2, size.height / 2); rotate((Clock.t * 50f) % 360f, c) { drawRect(Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = .32f), Color.Transparent, Color.Transparent), c), Offset(-size.width, -size.width), Size(size.width * 3, size.height + size.width * 2)) } }
        23 -> clipped.background(Color(0xFF16D9A5).copy(alpha = .3f)).border2(Color.White.copy(alpha = .5f), shape).drawBehind { val t = Clock.t; drawRect(Brush.linearGradient(listOf(Color(0xFF16D9A5).copy(alpha = .35f + .15f * sin(t)), Color(0xFF7B5CFF).copy(alpha = .4f), Color(0xFFFF5CA8).copy(alpha = .3f + .15f * cos(t * .8f))))) }
        24 -> clipped.background(base).drawBehind { val c = Offset(0f, size.height); for (k in 0 until 3) { val e = ((Clock.t * .45f + k / 3f) % 1f); drawCircle(accent.copy(alpha = (1f - e) * .35f), e * size.width * 1.1f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(dp(2f))) } }
        25 -> clipped.background(base).drawBehind { val y = ((Clock.t * .6f) % 1.3f - .15f) * size.height; drawRect(Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = .28f), Color.Transparent), y - dp(10f), y + dp(10f)), Offset(0f, y - dp(10f)), Size(size.width, dp(20f))) }
        26 -> clipped.background(base).drawBehind { val r = Random(size.width.toInt() + 5); repeat(6) { val sp = .12f + r.nextFloat() * .15f; val x = r.nextFloat() * size.width; val y = size.height * (1.1f - ((Clock.t * sp + r.nextFloat()) % 1f) * 1.2f); val s = dp(5f + r.nextInt(4)); val p = androidx.compose.ui.graphics.Path().apply { moveTo(x, y + s * .9f); cubicTo(x - s * 1.6f, y - s * .2f, x - s * .4f, y - s * 1.2f, x, y - s * .3f); cubicTo(x + s * .4f, y - s * 1.2f, x + s * 1.6f, y - s * .2f, x, y + s * .9f); close() }; drawPath(p, Color(0xFFFF4D6D).copy(alpha = .45f)) } }
        27 -> clipped.background(Color(0xFF2A0A0A)).drawBehind { val f = .5f + .5f * sin(Clock.t * 7f) * cos(Clock.t * 3.1f); drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFF6A00).copy(alpha = .45f + .25f * f), Color(0xFFFFD000).copy(alpha = .35f + .2f * f)))) }
        28 -> clipped.background(Brush.linearGradient(listOf(Color(0xFFE3F6FF), Color(0xFFA9DBFF)))).drawBehind { val r = Random(size.width.toInt()); repeat(14) { val a = (.5f + .5f * sin(Clock.t * 2f + it * 1.3f)); drawCircle(Color.White.copy(alpha = a * .9f), dp(.8f + r.nextFloat() * 1.2f), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) } }
        29 -> clipped.background(Color(0xFF0B0F24)).border2(Color(0xFF6FE7FF).copy(alpha = .7f), shape).drawBehind { val ph = (Clock.t * 6f).toInt(); val r = Random(ph); if (r.nextInt(3) != 0) { val p = androidx.compose.ui.graphics.Path(); var x = r.nextFloat() * size.width; var y = 0f; p.moveTo(x, y); while (y < size.height) { y += size.height / 5f; x += (r.nextFloat() - .5f) * size.width * .35f; p.lineTo(x, y) }; drawPath(p, Color(0xFF9AF0FF).copy(alpha = .6f), style = androidx.compose.ui.graphics.drawscope.Stroke(dp(1.4f))) } }
        30 -> clipped.background(Brush.linearGradient(listOf(Color(0xFF0A0B24), Color(0xFF2B1B5A)))).drawBehind { val r = Random(size.width.toInt() + 11); repeat(16) { val a = (.3f + .7f * (.5f + .5f * sin(Clock.t * (1f + r.nextFloat() * 2f) + it))); drawCircle(Color.White.copy(alpha = a), dp(.6f + r.nextFloat() * 1.3f), Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)) } }
        31 -> clipped.background(Brush.linearGradient(listOf(Color(0xFFF7D774), Color(0xFFD9A441), Color(0xFFF7D774)))).drawBehind { val w = size.width; val x = ((Clock.t * .5f) % 1.6f - .3f) * w; drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .6f), Color.Transparent), Offset(x, 0f), Offset(x + w * .3f, size.height))) }
        else -> clipped.background(base)
    }
}

private fun Modifier.border2(c: Color, shape: Shape) = this.then(Modifier.border(1.2.dp, c, shape))
