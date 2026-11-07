package com.kafisir.wetalks.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.FileKit
import com.kafisir.wetalks.ui.*
import java.io.File

private val CodeBg = Color(0xFF0D1830)
private val CodeHead = Color(0xFF16254A)
private val CodeText = Color(0xFFE6EDFF)
private val ColKw = Color(0xFF8AB4FF); private val ColStr = Color(0xFF9BE7A8); private val ColCom = Color(0xFF7D8FB0); private val ColNum = Color(0xFFFFCF7A)

/** Chat text with Markdown: bold, italic, strike, code, spoilers, links, headings, lists, quotes, tables and code blocks with Copy / Save / Preview. */
@Composable
fun MarkdownText(text: String, color: Color, style: TextStyle) {
    val blocks = remember(text) { Markdown.parse(text) }
    var reveal by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { blocks.forEach { Block(it, color, style, reveal) { reveal = true } } }
}

@Composable
private fun rich(runs: List<Markdown.Run>, color: Color, reveal: Boolean, onReveal: () -> Unit): AnnotatedString = marked(richPlain(runs, color, reveal, onReveal))

@Composable
private fun richPlain(runs: List<Markdown.Run>, color: Color, reveal: Boolean, onReveal: () -> Unit): AnnotatedString {
    val ctx = LocalContext.current
    val accent = We.primary
    val codeBg = We.primary.copy(alpha = .14f)
    return remember(runs, reveal, accent, color) {
        buildAnnotatedString {
            val linkStyle = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
            for (r in runs) {
                val deco = if (r.strike) TextDecoration.LineThrough else null
                var st = SpanStyle(fontWeight = if (r.bold) FontWeight.Bold else null, fontStyle = if (r.italic) FontStyle.Italic else null, textDecoration = deco)
                if (r.code) st = st.copy(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.5.sp)
                when {
                    r.spoiler && !reveal -> withLink(LinkAnnotation.Clickable("s") { onReveal() }) { withStyle(SpanStyle(color = Color.Transparent, background = color.copy(alpha = .75f))) { append(r.text) } }
                    r.link != null -> withLink(LinkAnnotation.Clickable("l", linkStyle) { openLink(ctx, r.link) }) { withStyle(st) { append(r.text) } }
                    r.code -> withStyle(st) { append(r.text) }
                    else -> withStyle(st) { appendLinkified(r.text, ctx, accent) }
                }
            }
        }
    }
}

private fun openLink(ctx: Context, url: String) {
    when {
        url.startsWith("http", true) -> Nav.push(RBrowser(url))
        else -> try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Nav.say("Can't open that link") }
    }
}

@Composable
private fun Block(b: Markdown.Block, color: Color, style: TextStyle, reveal: Boolean, onReveal: () -> Unit) {
    when (b) {
        is Markdown.Block.Para -> Text(rich(b.runs, color, reveal, onReveal), color = color, style = style)
        is Markdown.Block.Heading -> Text(rich(b.runs, color, reveal, onReveal), color = color, style = style.copy(fontWeight = FontWeight.Bold, fontSize = style.fontSize * (1.55f - 0.12f * (b.level - 1)).coerceAtLeast(1.05f), lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified))
        is Markdown.Block.Item -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
            Text(when (b.check) { true -> "☑"; false -> "☐"; else -> b.marker }, color = if (b.check == true) We.primary else color, style = style, modifier = Modifier.widthIn(min = 18.dp).padding(end = 6.dp))
            Text(rich(b.runs, color, reveal, onReveal), color = color, style = style.copy(textDecoration = if (b.check == true) TextDecoration.None else null), modifier = Modifier.weight(1f, fill = false))
        }
        is Markdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(We.primary)); Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { b.blocks.forEach { Block(it, color.copy(alpha = .85f), style, reveal, onReveal) } }
        }
        Markdown.Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(color.copy(alpha = .25f)))
        is Markdown.Block.Code -> CodeBlock(b.lang, b.text)
        is Markdown.Block.Table -> TableBlock(b, color, style, reveal, onReveal)
    }
}

@Composable
private fun TableBlock(t: Markdown.Block.Table, color: Color, style: TextStyle, reveal: Boolean, onReveal: () -> Unit) {
    val line = color.copy(alpha = .25f)
    val cols = t.header.size
    Column(Modifier.horizontalScroll(rememberScrollState()).clip(RoundedCornerShape(8.dp)).border(1.dp, line, RoundedCornerShape(8.dp))) {
        for ((ri, row) in (listOf(t.header) + t.rows).withIndex()) {
            Row(Modifier.height(IntrinsicSize.Min).then(if (ri == 0) Modifier.background(We.primary.copy(alpha = .14f)) else Modifier)) {
                for (c in 0 until cols) {
                    val cell = row.getOrNull(c) ?: emptyList()
                    Box(Modifier.widthIn(min = 70.dp).fillMaxHeight().border(.5.dp, line).padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Text(rich(cell, color, reveal, onReveal), color = color, style = style.copy(fontSize = style.fontSize * .92f, fontWeight = if (ri == 0) FontWeight.Bold else style.fontWeight),
                            textAlign = when (t.align.getOrNull(c)) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val ctx = LocalContext.current
    val spans = remember(code, lang) { Highlight.spans(code, lang) }
    val text = remember(code, spans) {
        buildAnnotatedString {
            append(code)
            for (s in spans) addStyle(SpanStyle(color = when (s.kind) { Highlight.KEYWORD -> ColKw; Highlight.STRING -> ColStr; Highlight.COMMENT -> ColCom; else -> ColNum }, fontStyle = if (s.kind == Highlight.COMMENT) FontStyle.Italic else null), s.start, s.end)
        }
    }
    val isHtml = lang == "html" || lang == "htm"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CodeBg)) {
        Row(Modifier.fillMaxWidth().background(CodeHead).padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((lang.ifEmpty { "code" }).uppercase(), color = ColKw, style = T.tiny.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
            if (isHtml) IconBtn(Ic.Eye, CodeText, { Nav.push(RHtml(code)) }, 18.dp, Modifier.size(36.dp))
            IconBtn(Ic.Download, CodeText, {
                val name = "snippet." + Highlight.extension(lang)
                val f = File(ctx.cacheDir, name); f.writeText(code)
                Nav.say(if (FileKit.saveToDownloads(ctx, f, name, "text/plain")) "Saved to Downloads/WeTalks/$name" else "Could not save")
            }, 18.dp, Modifier.size(36.dp))
            IconBtn(Ic.Copy, CodeText, { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", code)); Nav.say("Code copied") }, 18.dp, Modifier.size(36.dp))
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(text, color = CodeText, style = T.small.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp), softWrap = false)
        }
    }
}

/** Safe preview of an HTML snippet: no scripts, no network. */
@Composable
fun HtmlPreviewScreen(html: String) {
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("HTML preview", onBack = { Nav.pop() }, subtitle = "Scripts and network are off")
        androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
            android.webkit.WebView(c).apply {
                settings.javaScriptEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.blockNetworkLoads = true
                settings.domStorageEnabled = false; setBackgroundColor(android.graphics.Color.WHITE)
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
    }
}
