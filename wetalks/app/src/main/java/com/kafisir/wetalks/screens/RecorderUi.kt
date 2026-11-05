package com.kafisir.wetalks.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.ui.*

/**
 * The hold-to-record mic. The touch target never moves (so the gesture is stable); only the visible button follows the
 * finger, grows while recording, pulses with the voice level and turns into a bin when it is about to be cancelled.
 */
@Composable
fun MicGesture(recording: Boolean, cancel: Boolean, level: Float, dragX: Float, dragY: Float,
               onStart: (() -> Boolean)? = null, onMove: ((Float, Float) -> Unit)? = null, onUp: ((Float) -> Unit)? = null) {
    val sc by animateFloatAsState(if (recording) 1.55f else 1f, spring(dampingRatio = .55f, stiffness = 380f), label = "mic")
    val lv by animateFloatAsState(level, tween(110), label = "lv")
    val ringColor = if (cancel) We.danger else We.primary
    val onStartS by rememberUpdatedState(onStart); val onMoveS by rememberUpdatedState(onMove); val onUpS by rememberUpdatedState(onUp)
    Box(Modifier.size(46.dp).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val started = onStartS?.invoke() ?: true
            if (!started) return@awaitEachGesture
            while (true) {
                val ev = awaitPointerEvent(); val ch = ev.changes.first()
                val dx = ch.position.x - down.position.x; val dy = ch.position.y - down.position.y
                if (ch.changedToUp()) { onUpS?.invoke(dx); break }
                onMoveS?.invoke(dx, dy); ch.consume()
            }
        }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { translationX = dragX; translationY = dragY; scaleX = sc; scaleY = sc }
            .drawBehind { if (recording) drawCircle(ringColor.copy(alpha = .20f), radius = size.minDimension / 2 * (1.15f + lv * .9f)) }
            .size(46.dp).clip(CircleShape).background(if (cancel) We.danger else We.primary), contentAlignment = Alignment.Center) {
            Icon(if (cancel) Ic.Trash else Ic.Mic, We.onPrimary, 22.dp)
        }
    }
}

/** Left part of the bar while recording: timer + "slide to cancel" (follows the finger), or the locked hands-free controls. */
@Composable
fun RowScope.RecorderBar(secs: Int, levels: List<Float>, locked: Boolean, paused: Boolean, willCancel: Boolean, dragX: Float, cancelPx: Float,
                         onTrash: () -> Unit, onPauseToggle: () -> Unit) {
    val primary = We.primary
    val inf = rememberInfiniteTransition(label = "rec")
    val blink by inf.animateFloat(1f, .25f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "blink")
    val bob by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    if (locked) IconBtn(Ic.Trash, We.danger, onTrash) else Spacer(Modifier.width(10.dp))
    Row(Modifier.weight(1f).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (willCancel && !locked) Icon(Ic.Trash, We.danger, 18.dp) else Box(Modifier.size(11.dp).clip(CircleShape).background(We.danger.copy(alpha = if (paused) 1f else blink)))
        Spacer(Modifier.width(10.dp))
        Text("%d:%02d".format(secs / 60, secs % 60), color = We.text, style = T.name)
        Spacer(Modifier.width(12.dp))
        if (locked) {
            Canvas(Modifier.weight(1f).height(28.dp)) {
                val n = 34; val gap = 3.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
                val shown = levels.takeLast(n)
                for (i in 0 until n) {
                    val idx = i - (n - shown.size)
                    val v = if (idx >= 0) shown[idx] else 0f
                    val h = (size.height * (.12f + v * .88f)).coerceIn(3.dp.toPx(), size.height)
                    drawRoundRect(if (idx >= 0) primary else primary.copy(alpha = .25f), Offset(i * (w + gap), (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
                }
            }
            IconBtn(if (paused) Ic.Mic else Ic.Pause, We.primary, onPauseToggle, 24.dp)
        } else {
            val a = (1f - (-dragX / cancelPx)).coerceIn(0f, 1f)
            Row(Modifier.weight(1f).graphicsLayer { translationX = dragX * .45f; alpha = if (willCancel) 1f else a }, verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = if (willCancel) We.danger else We.text2, style = T.title, modifier = Modifier.graphicsLayer { translationX = -10f * bob })
                Spacer(Modifier.width(6.dp))
                Text(if (willCancel) "Release to cancel" else "Slide to cancel", color = if (willCancel) We.danger else We.text2, style = T.body)
            }
        }
    }
}

/** Floating "slide up to lock" pill above the mic. */
@Composable
fun LockPill(progress: Float, dragY: Float, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "lock")
    val bob by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Column(modifier.graphicsLayer { translationY = dragY * .3f }.shadow(8.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(We.surface).padding(vertical = 12.dp, horizontal = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Ic.Lock, if (progress > .7f) We.primary else We.text2, 20.dp, Modifier.graphicsLayer { val s = 1f + progress * .25f; scaleX = s; scaleY = s })
        Spacer(Modifier.height(8.dp))
        Text("︿", color = We.text2, style = T.body, modifier = Modifier.graphicsLayer { translationY = -8f * bob; alpha = 1f - progress * .6f })
    }
}
