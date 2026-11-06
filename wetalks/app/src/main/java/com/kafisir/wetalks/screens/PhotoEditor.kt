package com.kafisir.wetalks.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.kafisir.wetalks.ui.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ------------------------------------------------------------------------------------------------ model
/** A mark is stored in coordinates normalised to the ROTATED photo (0..1), so it survives crop changes. */
sealed class Mark(val color: Color, val width: Float)          // width = fraction of the rotated photo's width
class Pen(color: Color, width: Float, val pts: SnapshotStateList<Offset>) : Mark(color, width)
class Geo(color: Color, width: Float, val kind: Int, a0: Offset, b0: Offset) : Mark(color, width) {   // 0 oval, 1 rectangle, 2 line, 3 arrow
    // snapshot state, so the canvas redraws on every drag step: shapes now appear live while you draw
    var a by mutableStateOf(a0)
    var b by mutableStateOf(b0)
}

class EditItem(val uri: Uri, val base: Bitmap) {
    var rot by mutableIntStateOf(0)
    var flip by mutableStateOf(false)
    var cl by mutableFloatStateOf(0f); var ct by mutableFloatStateOf(0f); var cr by mutableFloatStateOf(1f); var cb by mutableFloatStateOf(1f)
    var ratio by mutableFloatStateOf(0f)                      // 0 = free, otherwise pixel aspect (w / h)
    var filter by mutableIntStateOf(0)
    var bright by mutableFloatStateOf(0f); var contrast by mutableFloatStateOf(1f); var sat by mutableFloatStateOf(1f)
    val marks = mutableStateListOf<Mark>()

    fun rotated(): Bitmap = transformed(base, rot, flip)
    fun rotateCw() {
        rot = (rot + 1) % 4
        fun r(o: Offset) = Offset(1f - o.y, o.x)
        marks.forEach { m -> when (m) { is Pen -> for (i in m.pts.indices) m.pts[i] = r(m.pts[i]); is Geo -> { m.a = r(m.a); m.b = r(m.b) } } }
        val nl = 1f - cb; val nt = cl; val nr = 1f - ct; val nb = cr
        cl = nl; ct = nt; cr = nr; cb = nb
        if (ratio > 0f) ratio = 1f / ratio
    }
    fun flipH() {
        flip = !flip
        fun f(o: Offset) = Offset(1f - o.x, o.y)
        marks.forEach { m -> when (m) { is Pen -> for (i in m.pts.indices) m.pts[i] = f(m.pts[i]); is Geo -> { m.a = f(m.a); m.b = f(m.b) } } }
        val nl = 1f - cr; val nr = 1f - cl; cl = nl; cr = nr
    }
}

private fun transformed(base: Bitmap, rot: Int, flip: Boolean): Bitmap {
    if (rot % 4 == 0 && !flip) return base
    val m = Matrix(); if (flip) m.postScale(-1f, 1f, base.width / 2f, base.height / 2f); m.postRotate(90f * (rot % 4))
    return Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
}

private val inks = listOf(Color.White, Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFAF52DE), Color.Black)
private val filterNames = listOf("Original", "Mono", "Warm", "Cool", "Vivid", "Fade")
private val cropPresets = listOf("Free" to 0f, "Original" to -1f, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)

private fun presetMatrix(i: Int): android.graphics.ColorMatrix? = when (i) {
    1 -> android.graphics.ColorMatrix().apply { setSaturation(0f) }
    2 -> android.graphics.ColorMatrix(floatArrayOf(1.12f, 0f, 0f, 0f, 6f, 0f, 1.02f, 0f, 0f, 0f, 0f, 0f, .86f, 0f, -4f, 0f, 0f, 0f, 1f, 0f))
    3 -> android.graphics.ColorMatrix(floatArrayOf(.9f, 0f, 0f, 0f, -4f, 0f, 1.02f, 0f, 0f, 0f, 0f, 0f, 1.14f, 0f, 8f, 0f, 0f, 0f, 1f, 0f))
    4 -> android.graphics.ColorMatrix().apply { setSaturation(1.6f); postConcat(android.graphics.ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, -8f, 0f, 1.1f, 0f, 0f, -8f, 0f, 0f, 1.1f, 0f, -8f, 0f, 0f, 0f, 1f, 0f))) }
    5 -> android.graphics.ColorMatrix(floatArrayOf(.85f, 0f, 0f, 0f, 30f, 0f, .85f, 0f, 0f, 30f, 0f, 0f, .85f, 0f, 30f, 0f, 0f, 0f, 1f, 0f))
    else -> null
}

/** Filter preset followed by the brightness / contrast / saturation sliders. */
private fun colorMatrixFor(it: EditItem): android.graphics.ColorMatrix? {
    if (it.filter == 0 && it.bright == 0f && it.contrast == 1f && it.sat == 1f) return null
    val cm = presetMatrix(it.filter)?.let { android.graphics.ColorMatrix(it) } ?: android.graphics.ColorMatrix()
    val s = android.graphics.ColorMatrix().apply { setSaturation(it.sat) }; cm.postConcat(s)
    val c = it.contrast; val t = (1f - c) * 128f + it.bright * 255f
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    return cm
}

// ------------------------------------------------------------------------------------------------ drawing marks (shared by screen + export)
private fun nativeColor(c: Color) = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

/** [map] converts normalised photo coords to canvas pixels. [wpx] = stroke width in pixels. */
private fun drawMark(c: Canvas, m: Mark, map: (Offset) -> Offset, wpx: Float) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = wpx; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = nativeColor(m.color) }
    when (m) {
        is Pen -> {
            if (m.pts.size == 1) { val o = map(m.pts[0]); p.style = Paint.Style.FILL; c.drawCircle(o.x, o.y, wpx / 2, p); return }
            val path = android.graphics.Path()
            m.pts.forEachIndexed { i, o -> val q = map(o); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
            c.drawPath(path, p)
        }
        is Geo -> {
            val a = map(m.a); val b = map(m.b)
            when (m.kind) {
                0 -> c.drawOval(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), p)
                1 -> c.drawRoundRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), wpx, wpx, p)
                2 -> c.drawLine(a.x, a.y, b.x, b.y, p)
                else -> {
                    c.drawLine(a.x, a.y, b.x, b.y, p)
                    val ang = atan2(b.y - a.y, b.x - a.x); val len = max(wpx * 5f, hypot(b.x - a.x, b.y - a.y) * .12f).coerceAtMost(hypot(b.x - a.x, b.y - a.y) * .5f)
                    for (s in listOf(-1, 1)) c.drawLine(b.x, b.y, b.x - len * cos(ang + s * .5f), b.y - len * sin(ang + s * .5f), p)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ automatic shape recognition
/** Looks at a freehand stroke (in screen pixels) and returns a clean shape if it clearly is one: Triple(kind, a, b) or null. */
internal fun recognise(pts: List<Offset>): Triple<Int, Offset, Offset>? {
    if (pts.size < 8) return null
    var len = 0f; for (i in 1 until pts.size) len += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
    val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }; val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
    val w = maxX - minX; val h = maxY - minY; val size = max(w, h)
    if (size < 40f) return null
    val first = pts.first(); val last = pts.last()
    val gap = hypot(last.x - first.x, last.y - first.y)
    // straight line: every point stays close to the chord
    if (gap > .6f * len) {
        val dx = last.x - first.x; val dy = last.y - first.y; val cl = hypot(dx, dy)
        val dev = pts.maxOf { abs((it.x - first.x) * dy - (it.y - first.y) * dx) / cl }
        if (dev < .06f * cl) return Triple(2, first, last)
        return null
    }
    if (gap > .3f * size) return null                                // open scribble
    val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val a = max(w / 2, 1f); val b = max(h / 2, 1f)
    // rectangle: points hug the bounding box and visit all four corners
    val md = min(w, h).coerceAtLeast(1f)
    val onEdge = pts.count { minOf(abs(it.x - minX), abs(it.x - maxX), abs(it.y - minY), abs(it.y - maxY)) / md < .07f }.toFloat() / pts.size
    val corners = listOf(Offset(minX, minY), Offset(maxX, minY), Offset(maxX, maxY), Offset(minX, maxY)).count { c -> pts.any { hypot(it.x - c.x, it.y - c.y) < .17f * size } }
    if (onEdge > .85f && corners == 4) return Triple(1, Offset(minX, minY), Offset(maxX, maxY))
    // oval / circle: points sit on the ellipse fitted to the bounding box
    val res = pts.map { abs(sqrt(((it.x - cx) / a).let { v -> v * v } + ((it.y - cy) / b).let { v -> v * v }) - 1f) }.average()
    if (res < .13) {
        return if (abs(w - h) / size < .18f) { val r = (w + h) / 4; Triple(0, Offset(cx - r, cy - r), Offset(cx + r, cy + r)) } else Triple(0, Offset(minX, minY), Offset(maxX, maxY))
    }
    return null
}

// ------------------------------------------------------------------------------------------------ export
private fun export(item: EditItem): Pair<ByteArray, IntSize> {
    val rb = item.rotated()
    val l = (item.cl * rb.width).toInt().coerceIn(0, rb.width - 2); val t = (item.ct * rb.height).toInt().coerceIn(0, rb.height - 2)
    val w = ((item.cr - item.cl) * rb.width).toInt().coerceIn(2, rb.width - l); val h = ((item.cb - item.ct) * rb.height).toInt().coerceIn(2, rb.height - t)
    var v = Bitmap.createBitmap(rb, l, t, w, h)
    val s = (if (com.kafisir.wetalks.data.DataPrefs.saver) 900f else 1280f) / max(v.width, v.height)
    if (s < 1f) v = Bitmap.createScaledBitmap(v, (v.width * s).toInt(), (v.height * s).toInt(), true)
    val out = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val p = Paint(Paint.FILTER_BITMAP_FLAG); colorMatrixFor(item)?.let { p.colorFilter = android.graphics.ColorMatrixColorFilter(it) }
    c.drawBitmap(v, 0f, 0f, p)
    val rw = item.cr - item.cl; val rh = item.cb - item.ct
    val map = { o: Offset -> Offset((o.x - item.cl) / rw * out.width, (o.y - item.ct) / rh * out.height) }
    item.marks.forEach { m -> drawMark(c, m, map, m.width * out.width / rw) }
    val bo = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, if (com.kafisir.wetalks.data.DataPrefs.saver) 65 else 80, bo)
    return bo.toByteArray() to IntSize(out.width, out.height)
}

// ------------------------------------------------------------------------------------------------ screen
/** Send-photo screen: free crop on a canvas, rotate / flip, pen with automatic shapes, ready-made shapes, filters, adjustments, caption. */
@Composable
fun PhotoEditorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ref = Nav.pendingRef ?: run { Nav.pop(); return }
    val replyId = Nav.pendingReply
    val items = remember { mutableStateListOf<EditItem>() }
    var loaded by remember { mutableStateOf(false) }
    var idx by remember { mutableIntStateOf(0) }
    var tool by remember { mutableIntStateOf(0) }     // 0 none, 1 crop, 2 draw, 3 shapes, 4 filters, 5 adjust
    var ink by remember { mutableStateOf(inks[1]) }
    var penWidth by remember { mutableFloatStateOf(.008f) }
    var auto by remember { mutableStateOf(true) }
    var shape by remember { mutableIntStateOf(0) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            Nav.pendingPhotos.take(10).forEach { u ->
                ctx.contentResolver.openInputStream(u)?.use { ins ->
                    val b = BitmapFactory.decodeStream(ins)
                    if (b != null) { val s = 1800f / max(b.width, b.height); items.add(EditItem(u, if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b)) }
                }
            }
        }
        loaded = true
    }
    val cur = items.getOrNull(idx)
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, Color.White, { Nav.pop() })
            Text(if (items.size > 1) "${idx + 1} / ${items.size}" else "Edit photo", color = Color.White, style = T.name, modifier = Modifier.weight(1f))
            if (cur != null && tool in 2..3) IconBtn(Ic.Undo, Color.White, { if (cur.marks.isNotEmpty()) cur.marks.removeAt(cur.marks.lastIndex) })
            if (items.size > 1) IconBtn(Ic.Trash, Color.White, { if (items.isNotEmpty()) { items.removeAt(idx); idx = idx.coerceAtMost(items.size - 1); if (items.isEmpty()) Nav.pop() } })
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (cur == null) { if (!loaded) androidx.compose.material3.CircularProgressIndicator(color = Color.White) }
            else EditCanvas(cur, tool, ink, penWidth, auto, shape)
        }
        if (cur != null) {
            when (tool) {
                1 -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cropPresets.forEach { (n, r) ->
                            val rb = cur.rotated(); val target = if (r < 0f) rb.width.toFloat() / rb.height else r
                            Chip(n, if (r == 0f) cur.ratio == 0f else abs(cur.ratio - target) < .01f) { setCropRatio(cur, if (r == 0f) 0f else target) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("Rotate ⟳", false) { cur.rotateCw() }
                        Chip("Flip ⇋", false) { cur.flipH() }
                        Chip("Reset", false) { cur.cl = 0f; cur.ct = 0f; cur.cr = 1f; cur.cb = 1f; cur.ratio = 0f }
                    }
                }
                2 -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    InkRow(ink) { ink = it }
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Size", color = Color.White, style = T.small); Spacer(Modifier.width(10.dp))
                        Slider(penWidth, { penWidth = it }, valueRange = .003f..0.03f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
                        Spacer(Modifier.width(8.dp)); Chip("Auto shapes", auto) { auto = !auto }
                    }
                }
                3 -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Circle", "Rectangle", "Line", "Arrow").forEachIndexed { i, n -> Chip(n, shape == i) { shape = i } } }
                    Spacer(Modifier.height(4.dp)); InkRow(ink) { ink = it }
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Thickness", color = Color.White, style = T.small); Spacer(Modifier.width(10.dp))
                        Slider(penWidth, { penWidth = it }, valueRange = .003f..0.03f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
                    }
                }
                4 -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { filterNames.forEachIndexed { i, n -> Chip(n, cur.filter == i) { cur.filter = i } } }
                5 -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    AdjustRow("Brightness", cur.bright, -.4f..0.4f) { cur.bright = it }
                    AdjustRow("Contrast", cur.contrast, .6f..1.6f) { cur.contrast = it }
                    AdjustRow("Saturation", cur.sat, 0f..2f) { cur.sat = it }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolBtn(Ic.Crop, tool == 1) { tool = if (tool == 1) 0 else 1 }
                ToolBtn(Ic.Rotate, false) { cur.rotateCw() }
                ToolBtn(Ic.Brush, tool == 2) { tool = if (tool == 2) 0 else 2 }
                ToolBtn(Ic.Shapes, tool == 3) { tool = if (tool == 3) 0 else 3 }
                ToolBtn(Ic.Smile, tool == 4) { tool = if (tool == 4) 0 else 4 }
                ToolBtn(Ic.Sliders, tool == 5) { tool = if (tool == 5) 0 else 5 }
            }
        }
        if (items.size > 1) androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(items.size) { i ->
                Image(items[i].base.asImageBitmap(), null, Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (i == idx) We.primary else Color.Transparent, RoundedCornerShape(8.dp)).clickable { idx = i },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(Color(0xFF1E2630)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (caption.isEmpty()) Text("Add a caption…", color = Color(0xFF8A9BB0), style = T.body)
                BasicTextField(caption, { caption = it.take(500) }, Modifier.fillMaxWidth(), textStyle = T.body.copy(color = Color.White), cursorBrush = SolidColor(Color.White), maxLines = 4)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(We.primary).clickable(enabled = !busy && items.isNotEmpty()) {
                // like Telegram: the editor closes at once and the photos appear in the chat as "sending" bubbles
                val cap = caption.trim()
                items.toList().forEachIndexed { i, it ->
                    val sc = 420f / max(it.base.width, it.base.height)
                    val thumb = Bitmap.createScaledBitmap(it.base, (it.base.width * sc).toInt().coerceAtLeast(1), (it.base.height * sc).toInt().coerceAtLeast(1), true).asImageBitmap()
                    val o = Repo.Outgoing(java.util.UUID.randomUUID().toString(), ref, "image", text = if (i == 0) cap else "", thumb = thumb)
                    Repo.runOutgoing(o) {
                        val (bytes, size) = withContext(Dispatchers.Default) { export(it) }
                        val id = Repo.uploadMedia(ref, bytes, "image/jpeg")
                        Repo.send(ref, "image", JSONObject().put("m", id).put("w", size.width).put("h", size.height).put("c", if (i == 0) cap else "").toString(), if (i == 0) replyId else null, if (i == 0 && cap.isNotBlank()) cap else "Photo")
                    }
                }
                Nav.pendingReply = null
                Nav.pop()
            }, contentAlignment = Alignment.Center) {
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Ic.Send, We.onPrimary, 22.dp)
            }
        }
    }
}

/** Largest centred crop box with pixel aspect [r] (0 = free: leave the box alone). */
private fun setCropRatio(it: EditItem, r: Float) {
    it.ratio = r
    if (r <= 0f) return
    val rb = it.rotated(); val ph = rb.height.toFloat(); val pw = rb.width.toFloat()
    var w = 1f; var h = pw / (r * ph)           // normalised height for full width
    if (h > 1f) { h = 1f; w = r * ph / pw }
    it.cl = (1f - w) / 2; it.cr = it.cl + w; it.ct = (1f - h) / 2; it.cb = it.ct + h
}

@Composable private fun InkRow(ink: Color, pick: (Color) -> Unit) {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        inks.forEach { c -> Box(Modifier.size(if (c == ink) 34.dp else 28.dp).clip(CircleShape).background(c).border(2.dp, if (c == ink) Color.White else Color(0x55FFFFFF), CircleShape).clickable { pick(c) }) }
    }
}

@Composable private fun AdjustRow(label: String, v: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = T.small, modifier = Modifier.width(84.dp))
        Slider(v, set, valueRange = range, modifier = Modifier.weight(1f).height(34.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
    }
}

@Composable private fun Chip(t: String, on: Boolean, click: () -> Unit) {
    Text(t, color = if (on) Color.Black else Color.White, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, softWrap = false,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Color.White else Color(0xFF2A3440)).clickable(onClick = click).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable private fun ToolBtn(ic: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, click: () -> Unit) {
    Box(Modifier.size(50.dp).clip(CircleShape).background(if (on) We.primary else Color(0xFF2A3440)).clickable(onClick = click), contentAlignment = Alignment.Center) { Icon(ic, Color.White, 24.dp) }
}

@Composable
private fun EditCanvas(item: EditItem, tool: Int, ink: Color, penWidth: Float, auto: Boolean, shape: Int) {
    val rb = remember(item.rot, item.flip) { item.rotated() }
    val img = remember(rb) { rb.asImageBitmap() }
    val cm = colorMatrixFor(item)
    val cf = remember(item.filter, item.bright, item.contrast, item.sat) { cm?.let { ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(it.array)) } }
    val cropping = tool == 1
    val l = if (cropping) 0f else item.cl; val t = if (cropping) 0f else item.ct; val r = if (cropping) 1f else item.cr; val b = if (cropping) 1f else item.cb
    val aspect = (rb.width * (r - l)) / (rb.height * (b - t))
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    fun toBase(o: Offset) = Offset(l + o.x / size.width * (r - l), t + o.y / size.height * (b - t))
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(aspect).clip(RoundedCornerShape(4.dp)).onSizeChanged { size = it }
            .then(when (tool) {
                2, 3 -> Modifier.pointerInput(tool, ink, auto, shape, penWidth, l, t, r, b) {
                    var cur: Mark? = null
                    val raw = ArrayList<Offset>()
                    detectDragGestures(onDragStart = { o ->
                        raw.clear(); raw.add(o)
                        val st: Mark = if (tool == 2) Pen(ink, penWidth, mutableStateListOf(toBase(o))) else Geo(ink, penWidth, shape.let { when (it) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 } }, toBase(o), toBase(o))
                        item.marks.add(st); cur = st
                    }, onDrag = { c, _ ->
                        val o = Offset(c.position.x.coerceIn(0f, size.width.toFloat()), c.position.y.coerceIn(0f, size.height.toFloat()))
                        when (val m = cur) { is Pen -> { raw.add(o); m.pts.add(toBase(o)) }; is Geo -> m.b = toBase(o); else -> {} }
                    }, onDragEnd = {
                        val m = cur
                        if (m is Pen && auto) recognise(raw)?.let { (k, a, bb) ->
                            val i = item.marks.indexOf(m); if (i >= 0) item.marks[i] = Geo(m.color, m.width, k, toBase(a), toBase(bb))
                        }
                        cur = null
                    })
                }
                1 -> Modifier.pointerInput(Unit) {
                    var mode = 0                     // 1..4 corners (tl,tr,br,bl), 5..8 edges (l,t,r,b), 9 move
                    val hit = 36f * density
                    detectDragGestures(onDragStart = { o ->
                        val x0 = item.cl * size.width; val x1 = item.cr * size.width; val y0 = item.ct * size.height; val y1 = item.cb * size.height
                        fun near(a: Float, bb: Float) = abs(a - bb) < hit
                        mode = when {
                            near(o.x, x0) && near(o.y, y0) -> 1; near(o.x, x1) && near(o.y, y0) -> 2; near(o.x, x1) && near(o.y, y1) -> 3; near(o.x, x0) && near(o.y, y1) -> 4
                            near(o.x, x0) && o.y in y0..y1 -> 5; near(o.y, y0) && o.x in x0..x1 -> 6; near(o.x, x1) && o.y in y0..y1 -> 7; near(o.y, y1) && o.x in x0..x1 -> 8
                            o.x in x0..x1 && o.y in y0..y1 -> 9; else -> 0
                        }
                    }, onDrag = { c, d ->
                        val dx = d.x / size.width; val dy = d.y / size.height; val minS = .08f
                        val locked = item.ratio > 0f
                        val kr = if (locked) (rb.width / rb.height.toFloat()) / item.ratio else 0f      // normalised w = normalised h * kr
                        when (mode) {
                            9 -> { val w = item.cr - item.cl; val h = item.cb - item.ct; val nl = (item.cl + dx).coerceIn(0f, 1f - w); val nt = (item.ct + dy).coerceIn(0f, 1f - h); item.cl = nl; item.cr = nl + w; item.ct = nt; item.cb = nt + h }
                            in 1..4 -> {
                                val left = mode == 1 || mode == 4; val top = mode == 1 || mode == 2
                                var nx = if (left) item.cl + dx else item.cr + dx; var ny = if (top) item.ct + dy else item.cb + dy
                                nx = nx.coerceIn(0f, 1f); ny = ny.coerceIn(0f, 1f)
                                val ax = if (left) item.cr else item.cl; val ay = if (top) item.cb else item.ct
                                var w = abs(nx - ax).coerceAtLeast(minS); var h = abs(ny - ay).coerceAtLeast(minS)
                                if (locked) {
                                    h = w / kr
                                    val maxH = if (top) ay else 1f - ay; val maxW = if (left) ax else 1f - ax
                                    if (h > maxH) { h = maxH; w = h * kr }; if (w > maxW) { w = maxW; h = w / kr }
                                }
                                if (left) { item.cl = ax - w; item.cr = ax } else { item.cl = ax; item.cr = ax + w }
                                if (top) { item.ct = ay - h; item.cb = ay } else { item.ct = ay; item.cb = ay + h }
                            }
                            5 -> if (!locked) item.cl = (item.cl + dx).coerceIn(0f, item.cr - minS)
                            7 -> if (!locked) item.cr = (item.cr + dx).coerceIn(item.cl + minS, 1f)
                            6 -> if (!locked) item.ct = (item.ct + dy).coerceIn(0f, item.cb - minS)
                            8 -> if (!locked) item.cb = (item.cb + dy).coerceIn(item.ct + minS, 1f)
                        }
                    })
                }
                else -> Modifier
            })) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width; val h = this.size.height
                drawImage(img, srcOffset = IntOffset((l * rb.width).toInt(), (t * rb.height).toInt()), srcSize = IntSize(((r - l) * rb.width).toInt().coerceAtLeast(1), ((b - t) * rb.height).toInt().coerceAtLeast(1)),
                    dstSize = IntSize(w.toInt(), h.toInt()), colorFilter = cf)
                val map = { o: Offset -> Offset((o.x - l) / (r - l) * w, (o.y - t) / (b - t) * h) }
                drawIntoCanvas { cv -> item.marks.forEach { m -> drawMark(cv.nativeCanvas, m, map, m.width * w / (r - l)) } }
                if (cropping) {
                    val x0 = item.cl * w; val x1 = item.cr * w; val y0 = item.ct * h; val y1 = item.cb * h
                    val dimC = Color(0x99000000)
                    drawRect(dimC, Offset(0f, 0f), Size(w, y0)); drawRect(dimC, Offset(0f, y1), Size(w, h - y1))
                    drawRect(dimC, Offset(0f, y0), Size(x0, y1 - y0)); drawRect(dimC, Offset(x1, y0), Size(w - x1, y1 - y0))
                    val line = Color.White
                    drawRect(line, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    for (k in 1..2) {                                              // rule-of-thirds grid
                        drawLine(line.copy(alpha = .45f), Offset(x0 + (x1 - x0) * k / 3, y0), Offset(x0 + (x1 - x0) * k / 3, y1), 1f)
                        drawLine(line.copy(alpha = .45f), Offset(x0, y0 + (y1 - y0) * k / 3), Offset(x1, y0 + (y1 - y0) * k / 3), 1f)
                    }
                    val hs = 11f * density
                    for ((cx, cy) in listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)) drawCircle(line, hs, Offset(cx, cy))
                    for ((cx, cy) in listOf(x0 to (y0 + y1) / 2, x1 to (y0 + y1) / 2, (x0 + x1) / 2 to y0, (x0 + x1) / 2 to y1)) drawRoundRect(line, Offset(cx - hs * .7f, cy - hs * .7f), Size(hs * 1.4f, hs * 1.4f), androidx.compose.ui.geometry.CornerRadius(hs * .3f))
                }
            }
        }
    }
}
