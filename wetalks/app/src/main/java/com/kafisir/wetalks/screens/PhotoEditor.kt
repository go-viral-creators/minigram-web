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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class Stroke(val color: Color, val pts: SnapshotStateList<Offset>)

class EditItem(val uri: Uri, val base: Bitmap) {
    var rot by mutableIntStateOf(0)
    var crop by mutableIntStateOf(0)
    var filter by mutableIntStateOf(0)
    val strokes = mutableStateListOf<Stroke>()
}

private val cropNames = listOf("Original", "Square", "4:3", "3:4", "16:9")
private val filterNames = listOf("Original", "Mono", "Warm", "Cool", "Vivid", "Fade")
private val inks = listOf(Color.White, Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0A84FF), Color.Black)

private fun geometry(base: Bitmap, rot: Int, crop: Int): Bitmap {
    var b = base
    if (rot % 4 != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f * (rot % 4)) }, true)
    val ratio = when (crop) { 1 -> 1f; 2 -> 4f / 3; 3 -> 3f / 4; 4 -> 16f / 9; else -> 0f }
    if (ratio > 0f) {
        var w = b.width; var h = b.height
        if (w.toFloat() / h > ratio) w = (h * ratio).toInt() else h = (w / ratio).toInt()
        b = Bitmap.createBitmap(b, (b.width - w) / 2, (b.height - h) / 2, w, h)
    }
    return b
}

private fun matrixFor(i: Int): android.graphics.ColorMatrix? = when (i) {
    1 -> android.graphics.ColorMatrix().apply { setSaturation(0f) }
    2 -> android.graphics.ColorMatrix(floatArrayOf(1.12f, 0f, 0f, 0f, 6f, 0f, 1.02f, 0f, 0f, 0f, 0f, 0f, .86f, 0f, -4f, 0f, 0f, 0f, 1f, 0f))
    3 -> android.graphics.ColorMatrix(floatArrayOf(.9f, 0f, 0f, 0f, -4f, 0f, 1.02f, 0f, 0f, 0f, 0f, 0f, 1.14f, 0f, 8f, 0f, 0f, 0f, 1f, 0f))
    4 -> android.graphics.ColorMatrix().apply { setSaturation(1.6f); postConcat(android.graphics.ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, -8f, 0f, 1.1f, 0f, 0f, -8f, 0f, 0f, 1.1f, 0f, -8f, 0f, 0f, 0f, 1f, 0f))) }
    5 -> android.graphics.ColorMatrix(floatArrayOf(.85f, 0f, 0f, 0f, 30f, 0f, .85f, 0f, 0f, 30f, 0f, 0f, .85f, 0f, 30f, 0f, 0f, 0f, 1f, 0f))
    else -> null
}

private fun export(item: EditItem): Pair<ByteArray, IntSize> {
    var v = geometry(item.base, item.rot, item.crop)
    val max = 1280
    val s = max.toFloat() / maxOf(v.width, v.height)
    if (s < 1f) v = Bitmap.createScaledBitmap(v, (v.width * s).toInt(), (v.height * s).toInt(), true)
    val out = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val p = Paint(Paint.FILTER_BITMAP_FLAG)
    matrixFor(item.filter)?.let { p.colorFilter = android.graphics.ColorMatrixColorFilter(it) }
    c.drawBitmap(v, 0f, 0f, p)
    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = out.width * 0.009f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    item.strokes.forEach { st ->
        sp.color = android.graphics.Color.argb((st.color.alpha * 255).toInt(), (st.color.red * 255).toInt(), (st.color.green * 255).toInt(), (st.color.blue * 255).toInt())
        val path = android.graphics.Path()
        st.pts.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x * out.width, o.y * out.height) else path.lineTo(o.x * out.width, o.y * out.height) }
        if (st.pts.size == 1) c.drawPoint(st.pts[0].x * out.width, st.pts[0].y * out.height, sp) else c.drawPath(path, sp)
    }
    val bo = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 78, bo)
    return bo.toByteArray() to IntSize(out.width, out.height)
}

/** Send-photo screen: crop, rotate, draw, filters, caption, multiple photos. */
@Composable
fun PhotoEditorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ref = Nav.pendingRef ?: run { Nav.pop(); return }
    val replyId = Nav.pendingReply
    val items = remember { mutableStateListOf<EditItem>() }
    var loaded by remember { mutableStateOf(false) }
    var idx by remember { mutableIntStateOf(0) }
    var tool by remember { mutableIntStateOf(0) }     // 0 none, 1 crop, 2 draw, 3 filters
    var ink by remember { mutableStateOf(inks[1]) }
    var caption by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            Nav.pendingPhotos.take(10).forEach { u ->
                ctx.contentResolver.openInputStream(u)?.use { ins ->
                    val b = BitmapFactory.decodeStream(ins)
                    if (b != null) { val s = 1600f / maxOf(b.width, b.height); items.add(EditItem(u, if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b)) }
                }
            }
        }
        loaded = true
    }
    val cur = items.getOrNull(idx)
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Close, Color.White, { Nav.pop() })
            Text(if (items.size > 1) "${idx + 1} / ${items.size}" else "Send photo", color = Color.White, style = T.name, modifier = Modifier.weight(1f))
            if (items.size > 1) IconBtn(Ic.Trash, Color.White, { if (items.isNotEmpty()) { items.removeAt(idx); idx = idx.coerceAtMost(items.size - 1); if (items.isEmpty()) Nav.pop() } })
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (cur == null) { if (!loaded) androidx.compose.material3.CircularProgressIndicator(color = Color.White) }
            else EditCanvas(cur, tool == 2, ink)
        }
        if (cur != null) {
            when (tool) {
                1 -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    cropNames.forEachIndexed { i, n -> Chip(n, cur.crop == i) { cur.crop = i; cur.strokes.clear() } }
                }
                2 -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    inks.forEach { c -> Box(Modifier.size(if (c == ink) 34.dp else 28.dp).clip(CircleShape).background(c).border(2.dp, if (c == ink) Color.White else Color.Transparent, CircleShape).clickable { ink = c }) }
                    IconBtn(Ic.Undo, Color.White, { if (cur.strokes.isNotEmpty()) cur.strokes.removeAt(cur.strokes.lastIndex) })
                }
                3 -> androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    items(filterNames.size) { i -> Chip(filterNames[i], cur.filter == i) { cur.filter = i } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ToolBtn(Ic.Crop, tool == 1) { tool = if (tool == 1) 0 else 1 }
                ToolBtn(Ic.Rotate, false) { cur.rot = (cur.rot + 1) % 4; cur.strokes.clear() }
                ToolBtn(Ic.Brush, tool == 2) { tool = if (tool == 2) 0 else 2 }
                ToolBtn(Ic.Sliders, tool == 3) { tool = if (tool == 3) 0 else 3 }
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
                scope.launch {
                    busy = true
                    try {
                        items.forEachIndexed { i, it ->
                            val (bytes, size) = withContext(Dispatchers.Default) { export(it) }
                            val id = Repo.uploadMedia(ref, bytes, "image/jpeg")
                            Repo.send(ref, "image", JSONObject().put("m", id).put("w", size.width).put("h", size.height).put("c", if (i == 0) caption.trim() else "").toString(), if (i == 0) replyId else null, if (caption.isNotBlank() && i == 0) caption.trim() else "Photo")
                        }
                        Nav.pendingReply = null
                        Nav.pop()
                    } catch (e: Exception) { Nav.say(e.message ?: "Could not send photo") }
                    busy = false
                }
            }, contentAlignment = Alignment.Center) {
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else Icon(Ic.Send, We.onPrimary, 22.dp)
            }
        }
    }
}

@Composable private fun Chip(t: String, on: Boolean, click: () -> Unit) {
    Text(t, color = if (on) Color.Black else Color.White, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Color.White else Color(0xFF2A3440)).clickable(onClick = click).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable private fun ToolBtn(ic: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, click: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(if (on) We.primary else Color(0xFF2A3440)).clickable(onClick = click), contentAlignment = Alignment.Center) { Icon(ic, Color.White, 24.dp) }
}

@Composable
private fun EditCanvas(item: EditItem, drawing: Boolean, ink: Color) {
    val view = remember(item.rot, item.crop) { geometry(item.base, item.rot, item.crop) }
    val img = remember(view) { view.asImageBitmap() }
    val cf = remember(item.filter) { matrixFor(item.filter)?.let { ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(it.array)) } }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val aspect = view.width.toFloat() / view.height
    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(aspect).clip(RoundedCornerShape(6.dp)).onSizeChanged { size = it }.then(if (drawing) Modifier.pointerInput(drawing, ink) {
            var cur: Stroke? = null
            detectDragGestures(onDragStart = { o ->
                val st = Stroke(ink, mutableStateListOf(Offset(o.x / size.width, o.y / size.height))); item.strokes.add(st); cur = st
            }, onDrag = { c, _ -> cur?.pts?.add(Offset((c.position.x / size.width).coerceIn(0f, 1f), (c.position.y / size.height).coerceIn(0f, 1f))) })
        } else Modifier)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawImage(img, dstSize = androidx.compose.ui.unit.IntSize(this.size.width.toInt(), this.size.height.toInt()), colorFilter = cf)
                item.strokes.forEach { st ->
                    val p = Path()
                    st.pts.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x * this.size.width, o.y * this.size.height) else p.lineTo(o.x * this.size.width, o.y * this.size.height) }
                    drawPath(p, st.color, style = DrawStroke(width = this.size.width * 0.009f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (st.pts.size == 1) drawCircle(st.color, this.size.width * 0.0045f, Offset(st.pts[0].x * this.size.width, st.pts[0].y * this.size.height))
                }
            }
        }
    }
}

