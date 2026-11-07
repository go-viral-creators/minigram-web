package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kafisir.wetalks.data.ChatLock
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Msg
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.data.SearchBackfill
import com.kafisir.wetalks.data.SearchIndex
import com.kafisir.wetalks.data.SearchIndexer
import com.kafisir.wetalks.data.SearchQuery
import com.kafisir.wetalks.data.SearchSettings
import com.kafisir.wetalks.ui.Ic
import com.kafisir.wetalks.ui.IconBtn
import com.kafisir.wetalks.ui.Icon
import com.kafisir.wetalks.ui.Sheet
import com.kafisir.wetalks.ui.Text
import com.kafisir.wetalks.ui.SheetItem
import com.kafisir.wetalks.ui.T
import com.kafisir.wetalks.ui.We
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Two small icons the shared icon set does not have (same drawing style). */
internal object SIc {
    private fun icon(name: String, vararg d: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        d.forEach { addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()
    val ChevronUp: ImageVector by lazy { icon("ChevronUp", "M5 15l7-7l7 7") }
    val Calendar: ImageVector by lazy { icon("Calendar", "M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1M4 10h16M8 3v4M16 3v4") }
}

// ------------------------------------------------------------------------------------------ highlight in message bubbles

/** The words being searched for in the open chat; null when the chat search is closed. Bubbles read it to mark the matches. */
val LocalSearchQuery = androidx.compose.runtime.compositionLocalOf<SearchQuery.Parsed?> { null }

private val MARK_BG = Color(0xFFFFD84D)

/** The same text with every match of [p] marked (yellow, dark text so it reads on any bubble colour). Links and other styles are kept. */
fun AnnotatedString.withSearch(p: SearchQuery.Parsed?): AnnotatedString {
    if (p == null || !p.hasText || text.isEmpty()) return this
    val r = SearchQuery.ranges(text, p)
    if (r.isEmpty()) return this
    val src = this
    return buildAnnotatedString {
        append(src)
        for (x in r) addStyle(SpanStyle(background = MARK_BG, color = Color(0xFF1B1B1B)), x.first, x.last + 1)
    }
}

@Composable
fun marked(a: AnnotatedString): AnnotatedString {
    val p = LocalSearchQuery.current
    return if (p == null) a else remember(a, p) { a.withSearch(p) }
}

// ------------------------------------------------------------------------------------------ opening a chat from the global search

/** Hands the search words to the chat that a result opens, so the chat shows the matches and next / previous right away. */
object SearchBridge {
    class Pending(val chatId: String, val query: String, val mid: String)
    var pending by mutableStateOf<Pending?>(null)
    fun open(ref: ChatRef, query: String, mid: String) {
        pending = Pending(ref.id, query, mid)
        com.kafisir.wetalks.ui.Nav.openChatAt(ref, mid)
    }
}

// ------------------------------------------------------------------------------------------ search inside one chat

@Stable
class ChatSearchState(val ref: ChatRef) {
    var open by mutableStateOf(false); private set
    var text by mutableStateOf("")
    /** Start of the first day / end of the last day (epoch ms), or null. */
    var from by mutableStateOf<Long?>(null)
    var to by mutableStateOf<Long?>(null)
    var hits by mutableStateOf<List<SearchQuery.Hit>>(emptyList()); private set
    var pos by mutableIntStateOf(0); private set
    /** Goes up each time the chat should scroll to [currentId]. */
    var jumpTick by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var capped by mutableStateOf(false); private set

    /** What to look for, or null when closed / nothing typed yet. */
    val query: SearchQuery.Parsed? by derivedStateOf { if (!open) null else SearchQuery.withRange(SearchQuery.parse(text), from, to).takeIf { !it.isEmpty } }
    val currentId: String? get() = if (open) hits.getOrNull(pos)?.mid else null
    val counter: String get() = when {
        query == null -> ""
        busy && hits.isEmpty() -> "…"
        hits.isEmpty() -> "No results"
        else -> "${pos + 1} of ${hits.size}" + if (capped) "+" else ""
    }

    private var sig = ""
    private var startMid: String? = null

    fun show(initial: String = "", mid: String? = null) {
        open = true
        if (initial.isNotEmpty() || text.isEmpty()) text = initial
        startMid = mid
    }
    fun close() { open = false; hits = emptyList(); pos = 0; sig = ""; startMid = null }

    /** Older match (towards the past). */
    fun older() { if (hits.isEmpty()) return; pos = (pos + 1).coerceAtMost(hits.size - 1); jumpTick++ }
    /** Newer match. */
    fun newer() { if (hits.isEmpty()) return; pos = (pos - 1).coerceAtLeast(0); jumpTick++ }

    /** Finds the matches: the messages on screen (always, also for a locked chat) plus the whole indexed history of this chat. */
    suspend fun refresh(loaded: List<Msg>) {
        val p = query
        if (p == null) { hits = emptyList(); pos = 0; capped = false; sig = ""; return }
        busy = true
        try {
            val mem = withContext(Dispatchers.Default) {
                loaded.asSequence().filter { !it.pending && !it.deleted && it.ts > 0 && p.inRange(it.ts) && SearchQuery.indexText(it.type, it.text, false, it.unreadable, false) != null && SearchQuery.matches(it.text, p) }
                    .map { SearchQuery.Hit(ref.id, it.id, it.ts, 0, it.from) }.toList()
            }
            val idx = if (SearchSettings.enabled && !ChatLock.isLocked(ref.id)) try { SearchIndex.chatHits(ref.id, p) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } else emptyList()
            val cut = Repo.cutoff(ref.id); val blocked = Repo.blockedUsers.value
            val merged = SearchQuery.union(idx, mem).filter { it.ts > cut && !Repo.isHidden(it.mid) && it.from !in blocked }
            capped = idx.size >= 3000
            val prev = currentId
            val s = text + "|" + from + "|" + to
            hits = merged
            if (s != sig) {
                sig = s
                val sm = startMid; startMid = null
                val at = if (sm != null) merged.indexOfFirst { it.mid == sm } else -1
                pos = if (at >= 0) at else 0
                if (sm == null || at < 0) jumpTick++            // a new search goes to the newest match; opened from a result, the chat is already on its way there
            } else {
                val at = if (prev != null) merged.indexOfFirst { it.mid == prev } else -1
                pos = if (at >= 0) at else pos.coerceIn(0, (merged.size - 1).coerceAtLeast(0))
            }
        } finally { busy = false }
    }
}

@Composable
fun rememberChatSearch(ref: ChatRef): ChatSearchState {
    val st = remember(ref.id) { ChatSearchState(ref) }
    val pending = SearchBridge.pending
    LaunchedEffect(pending) {
        if (pending != null && pending.chatId == ref.id) { st.show(pending.query, pending.mid); SearchBridge.pending = null }
    }
    return st
}

/** Runs the search while the person types (after a short pause) and when the messages on screen change. */
@Composable
fun ChatSearchEffects(cs: ChatSearchState, loaded: List<Msg>) {
    LaunchedEffect(cs.open, cs.text, cs.from, cs.to) {
        if (!cs.open) return@LaunchedEffect
        kotlinx.coroutines.delay(220)
        cs.refresh(loaded)
    }
    LaunchedEffect(loaded.size) { if (cs.open && cs.query != null) cs.refresh(loaded) }
}

private val DAY = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

private fun pickDate(ctx: android.content.Context, initial: Long?, done: (java.time.LocalDate) -> Unit) {
    val c = Calendar.getInstance().apply { if (initial != null) timeInMillis = initial }
    android.app.DatePickerDialog(ctx, { _, y, m, d -> done(java.time.LocalDate.of(y, m + 1, d)) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.maxDate = System.currentTimeMillis()
    }.show()
}

/** Replaces the chat's top bar while searching: back, the box, "3 of 12", previous / next, and the date filter. */
@Composable
fun ChatSearchBar(cs: ChatSearchState) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    var dates by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    Column(Modifier.fillMaxWidth().background(We.appBar).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, We.onAppBar, { cs.close() })
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (cs.text.isEmpty()) Text0("Search in chat", We.onAppBar.copy(alpha = .6f))
                BasicTextField(cs.text, { cs.text = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = T.body.copy(color = We.onAppBar), cursorBrush = SolidColor(We.onAppBar),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            }
            if (cs.text.isNotEmpty()) IconBtn(Ic.Close, We.onAppBar, { cs.text = "" }, 20.dp, Modifier.size(40.dp))
            IconBtn(SIc.Calendar, if (cs.from != null || cs.to != null) We.onAppBar else We.onAppBar.copy(alpha = .75f), { dates = true }, 22.dp, Modifier.size(44.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text0(cs.counter.ifEmpty { if (cs.from == null && cs.to == null) "Type to search" else "Pick a day or type words" }, We.onAppBar.copy(alpha = .85f))
                cs.from?.let { Chip("From " + DAY.format(Date(it))) { cs.from = null } }
                cs.to?.let { Chip("To " + DAY.format(Date(it))) { cs.to = null } }
            }
            IconBtn(SIc.ChevronUp, We.onAppBar.copy(alpha = if (cs.pos < cs.hits.size - 1) 1f else .35f), { cs.older() }, 24.dp, Modifier.size(44.dp))
            IconBtn(Ic.ChevronDown, We.onAppBar.copy(alpha = if (cs.pos > 0) 1f else .35f), { cs.newer() }, 24.dp, Modifier.size(44.dp))
        }
        if (SearchSettings.enabled && !ChatLock.isLocked(cs.ref.id) && SearchBackfill.running && SearchBackfill.fraction < 1f)
            Text0("Older messages are still being added to search (${(SearchBackfill.fraction * 100).toInt()}%)", We.onAppBar.copy(alpha = .7f), Modifier.padding(start = 16.dp, bottom = 6.dp))
    }
    if (dates) Sheet({ dates = false }) {
        Text0("Search by date", We.text, Modifier.padding(start = 24.dp, bottom = 6.dp), title = true)
        SheetItem(SIc.Calendar, "From date" + (cs.from?.let { " · " + DAY.format(Date(it)) } ?: "")) { dates = false; pickDate(ctx, cs.from) { cs.from = SearchQuery.dayStart(it) } }
        SheetItem(SIc.Calendar, "To date" + (cs.to?.let { " · " + DAY.format(Date(it)) } ?: "")) { dates = false; pickDate(ctx, cs.to) { cs.to = SearchQuery.dayEnd(it) } }
        SheetItem(SIc.Calendar, "Just one day") { dates = false; pickDate(ctx, cs.from) { cs.from = SearchQuery.dayStart(it); cs.to = SearchQuery.dayEnd(it) } }
        if (cs.from != null || cs.to != null) SheetItem(Ic.Close, "Clear dates") { dates = false; cs.from = null; cs.to = null }
    }
}

@Composable
private fun Text0(s: String, color: Color, modifier: Modifier = Modifier, title: Boolean = false) =
    Text(s, color = color, style = if (title) T.title else T.small.copy(fontSize = 13.sp), modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Chip(label: String, onClear: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(We.onAppBar.copy(alpha = .18f)).clickable(onClick = onClear).padding(start = 10.dp, end = 6.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text0(label, We.onAppBar); Spacer(Modifier.width(4.dp)); Icon(Ic.Close, We.onAppBar, 13.dp)
    }
}

internal fun dayText(ts: Long): String = DAY.format(Date(ts))
