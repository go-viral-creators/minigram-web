package com.kafisir.wetalks.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.kafisir.wetalks.ui.Nav
import com.kafisir.wetalks.ui.RBrowser
import com.kafisir.wetalks.ui.We

private class Hit(val s: Int, val e: Int, val kind: Int)   // 0 link, 1 e-mail, 2 phone

private val phoneRe = Regex("""(?<![\w@./-])\+?\d[\d\s().-]{5,18}\d(?![\w@])""")
private val dateRe = Regex("""\d{4}-\d{2}-\d{2}|\d{1,2}[./]\d{1,2}[./]\d{2,4}""")

private fun findHits(text: String): List<Hit> {
    val hits = ArrayList<Hit>()
    fun free(s: Int, e: Int) = hits.none { s < it.e && e > it.s }
    val em = android.util.Patterns.EMAIL_ADDRESS.matcher(text)
    while (em.find()) if (free(em.start(), em.end())) hits += Hit(em.start(), em.end(), 1)
    val wu = android.util.Patterns.WEB_URL.matcher(text)
    while (wu.find()) if (free(wu.start(), wu.end())) hits += Hit(wu.start(), wu.end(), 0)
    for (m in phoneRe.findAll(text)) {
        val digits = m.value.count { it.isDigit() }
        if (digits in 7..15 && !dateRe.matches(m.value.trim()) && free(m.range.first, m.range.last + 1)) hits += Hit(m.range.first, m.range.last + 1, 2)
    }
    return hits.sortedBy { it.s }
}

/** Adds [text] to the builder with links, e-mail addresses and phone numbers turned into tappable spans. */
fun AnnotatedString.Builder.appendLinkified(text: String, ctx: android.content.Context, accent: Color) {
    var last = 0
    val style = TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline))
    for (h in findHits(text)) {
        append(text.substring(last, h.s))
        val shown = text.substring(h.s, h.e)
        val action: () -> Unit = when (h.kind) {
            0 -> { { Nav.push(RBrowser(if (shown.startsWith("http", true)) shown else "https://$shown")) } }
            1 -> { { try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$shown")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Nav.say("No e-mail app found") } } }
            else -> { { try { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + shown.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Nav.say("No phone app found") } } }
        }
        withLink(LinkAnnotation.Clickable("l", style) { action() }) { append(shown) }
        last = h.e
    }
    append(text.substring(last))
}

/** Turns links, e-mail addresses and phone numbers into tappable spans. */
@Composable
fun linkified(text: String, @Suppress("UNUSED_PARAMETER") color: Color = Color.Unspecified): AnnotatedString {
    val ctx = LocalContext.current
    val accent = We.primary
    return remember(text, accent) { buildAnnotatedString { appendLinkified(text, ctx, accent) } }
}
