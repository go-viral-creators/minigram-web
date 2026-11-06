package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.kafisir.wetalks.ui.T
import com.kafisir.wetalks.ui.Text
import com.kafisir.wetalks.ui.We

/**
 * Replaces the system "Cut / Copy / Paste" bubble in the message box with a Telegram-style strip that also formats the selection:
 * Bold, Italic, Strike, Spoiler, Mono, Code block, Quote, Link. The text field asks for it through [showMenu].
 */
class FormatToolbar(private val markdown: () -> Boolean) : TextToolbar {
    private var rect by mutableStateOf<Rect?>(null)
    private var cut: (() -> Unit)? = null
    private var copy: (() -> Unit)? = null
    private var paste: (() -> Unit)? = null
    private var all: (() -> Unit)? = null
    override val status: TextToolbarStatus get() = if (rect != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        this.rect = rect; copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested; all = onSelectAllRequested
    }

    override fun hide() { rect = null }

    /** Draw this once, anywhere inside the screen: it floats above the selection. */
    @Composable
    fun Content(wrap: (String, String) -> Unit, lines: ((Int) -> String) -> Unit) {
        val r = rect ?: return
        val md = markdown()
        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
        val pos = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (r.left.toInt() + (r.width.toInt() - popupContentSize.width) / 2).coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
                var y = r.top.toInt() - popupContentSize.height - gap
                if (y < 8) y = r.bottom.toInt() + gap
                return IntOffset(x, y)
            }
        }
        Popup(popupPositionProvider = pos, properties = PopupProperties(focusable = false, clippingEnabled = true)) {
            Row(Modifier.widthIn(max = 340.dp).shadow(8.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(We.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                @Composable fun B(label: String, bold: Boolean = false, italic: Boolean = false, strike: Boolean = false, mono: Boolean = false, f: () -> Unit) =
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable { f() }.padding(horizontal = 11.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = We.text, style = T.small.copy(fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Medium, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (strike) TextDecoration.LineThrough else null, fontFamily = if (mono) FontFamily.Monospace else null), maxLines = 1)
                    }
                cut?.let { c -> B("Cut") { c(); rect = null } }
                copy?.let { c -> B("Copy") { c(); rect = null } }
                paste?.let { c -> B("Paste") { c(); rect = null } }
                if (md && copy != null) {
                    Box(Modifier.padding(horizontal = 2.dp).background(We.line).padding(0.5.dp).padding(vertical = 12.dp))
                    B("B", bold = true) { wrap("**", "**"); rect = null }
                    B("I", italic = true) { wrap("*", "*"); rect = null }
                    B("S", strike = true) { wrap("~~", "~~"); rect = null }
                    B("Spoiler") { wrap("||", "||"); rect = null }
                    B("Mono", mono = true) { wrap("`", "`"); rect = null }
                    B("Code", mono = true) { wrap("```\n", "\n```"); rect = null }
                    B("Quote") { lines { "> " }; rect = null }
                    B("Link") { wrap("[", "](https://)"); rect = null }
                }
                all?.let { c -> B("All") { c() } }
            }
        }
    }
}
