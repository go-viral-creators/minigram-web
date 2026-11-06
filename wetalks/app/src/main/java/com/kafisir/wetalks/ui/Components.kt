package com.kafisir.wetalks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import com.kafisir.wetalks.ui.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.alpha
import com.kafisir.wetalks.R

@Composable
fun Icon(v: ImageVector, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Image(v, null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun Logo(size: Dp) { Image(painterResource(R.drawable.ic_logo), null, Modifier.size(size)) }

fun Modifier.tap(onClick: () -> Unit): Modifier = clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

private val avatarColors = listOf(0xFF7C5CFF, 0xFFFF3D71, 0xFF22A7F0, 0xFFF59E0B, 0xFF12B886, 0xFFF2603A, 0xFF8B5CF6, 0xFF0EA5E9).map { Color(it) }

@Composable
fun Avatar(name: String, photo: String, size: Dp, seed: String = name, square: Boolean = false) {
    val shape = if (square) RoundedCornerShape(size / 4) else CircleShape
    val bmp = remember(photo) { Pics.decodeB64(photo) }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        val c = avatarColors[(seed.hashCode() and 0x7fffffff) % avatarColors.size]
        Box(Modifier.size(size).clip(shape).background(c), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, style = TextStyle(fontFamily = Inter, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = (size.value / 2.4f).sp))
        }
    }
}

/** Cobalt app bar: solid blue (dark: slate), white title, status-bar aware. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, leading: @Composable (() -> Unit)? = null,
           titleClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(We.appBar).statusBarsPadding().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(Ic.Back, We.onAppBar, onBack)
        else Spacer(Modifier.width(12.dp))
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f).then(if (titleClick != null) Modifier.tap(titleClick) else Modifier)) {
            Text(title, color = We.onAppBar, style = if (subtitle == null) T.title else T.name.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = We.onAppBar.copy(alpha = .8f), style = T.small.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun IconBtn(v: ImageVector, tint: Color, onClick: () -> Unit, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(v, tint, size) }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Box(modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) We.primary else We.primary.copy(alpha = .4f))
        .clickable(enabled = enabled && !loading, onClick = onClick), contentAlignment = Alignment.Center) {
        if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = We.onPrimary, strokeWidth = 2.5.dp)
        else Text(text, color = We.onPrimary, style = T.btn)
    }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, We.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { icon(); Spacer(Modifier.width(10.dp)) }
        Text(text, color = We.text, style = T.btn.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, leading: ImageVector? = null, password: Boolean = false,
          keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, prefix: String? = null, trailing: @Composable (() -> Unit)? = null, error: String? = null) {
    var show by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = We.text2, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().heightIn48().clip(RoundedCornerShape(12.dp)).background(We.field)
            .border(1.dp, if (error != null) We.danger else We.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { Icon(leading, We.text2, 20.dp); Spacer(Modifier.width(10.dp)) }
            if (prefix != null) { Text(prefix, color = We.text, style = T.body); Spacer(Modifier.width(6.dp)) }
            BasicTextField(value, onChange, Modifier.weight(1f).padding(vertical = 14.dp), singleLine = singleLine, textStyle = T.body.copy(color = We.text),
                cursorBrush = SolidColor(We.primary), keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
                visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None)
            if (password) IconBtn(if (show) Ic.EyeOff else Ic.Eye, We.text2, { show = !show }, 20.dp, Modifier.size(36.dp))
            trailing?.invoke()
        }
        if (error != null) Text(error, color = We.danger, style = T.small, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
    }
}

private fun Modifier.heightIn48() = this.then(Modifier.height(50.dp))

@Composable
fun Divider(start: Dp = 0.dp) { Box(Modifier.padding(start = start).fillMaxWidth().height(1.dp).background(We.line)) }

@Composable
fun SectionLabel(t: String) { Text(t.uppercase(), color = We.primary, style = T.tiny.copy(letterSpacing = 1.sp), modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)) }

@Composable
fun Badge(n: Long, muted: Boolean = false) {
    if (n <= 0) return
    Box(Modifier.defaultMinSize(20.dp, 20.dp).clip(CircleShape).background(if (muted) We.text2 else We.primary).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(if (n > 99) "99+" else n.toString(), color = Color.White, style = T.tiny.copy(fontSize = 11.sp))
    }
}

@Composable
fun Row2(icon: ImageVector, title: String, sub: String? = null, tint: Color = We.text, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, if (tint == We.text) We.text2 else tint, 24.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            if (sub != null) Text(sub, color = We.text2, style = T.small)
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(48.dp).height(28.dp).clip(CircleShape).background(if (on) We.primary else We.line).clickable { onChange(!on) }.padding(3.dp)) {
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { Sfx.play("pop") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = We.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(36.dp).height(4.dp).clip(CircleShape).background(We.line))
        }) { Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) { content() } }
}

@Composable
fun SheetItem(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, if (danger) We.danger else We.text2, 22.dp); Spacer(Modifier.width(18.dp))
        Text(text, color = if (danger) We.danger else We.text, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, sub: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(We.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) { Icon(icon, We.primary, 38.dp) }
        Spacer(Modifier.height(18.dp))
        Text(title, color = We.text, style = T.name.copy(fontSize = 18.sp))
        Spacer(Modifier.height(6.dp))
        Text(sub, color = We.text2, style = T.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(18.dp)); action() }
    }
}

fun timeShort(ts: Long): String {
    if (ts <= 0) return ""
    val now = System.currentTimeMillis()
    val f = if (now - ts < 24 * 3600_000L && java.text.SimpleDateFormat("d", java.util.Locale.US).format(ts) == java.text.SimpleDateFormat("d", java.util.Locale.US).format(now))
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    else if (now - ts < 6 * 24 * 3600_000L) java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
    else java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
    return f.format(ts)
}
