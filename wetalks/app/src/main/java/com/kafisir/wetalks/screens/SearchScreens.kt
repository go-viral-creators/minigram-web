package com.kafisir.wetalks.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.ChatItem
import com.kafisir.wetalks.data.ChatLock
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Kind
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.data.SearchBackfill
import com.kafisir.wetalks.data.SearchIndex
import com.kafisir.wetalks.data.SearchIndexer
import com.kafisir.wetalks.data.SearchQuery
import com.kafisir.wetalks.data.SearchSettings
import com.kafisir.wetalks.ui.Avatar
import com.kafisir.wetalks.ui.Divider
import com.kafisir.wetalks.ui.Ic
import com.kafisir.wetalks.ui.IconBtn
import com.kafisir.wetalks.ui.Icon
import com.kafisir.wetalks.ui.Nav
import com.kafisir.wetalks.ui.OutlineButton
import com.kafisir.wetalks.ui.RNewChat
import com.kafisir.wetalks.ui.Row2
import com.kafisir.wetalks.ui.SectionLabel
import com.kafisir.wetalks.ui.T
import com.kafisir.wetalks.ui.Text
import com.kafisir.wetalks.ui.Toggle
import com.kafisir.wetalks.ui.We
import com.kafisir.wetalks.ui.timeShort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private class MsgResult(val ref: ChatRef, val row: SearchIndex.Row, val ranges: List<IntRange>, val snippet: String, val shifted: List<IntRange>)

/** The name shown for a chat, read without composing (used to find chats and people by name). */
private fun chatName(ref: ChatRef): String = when (ref.kind) {
    Kind.DM -> if (ref.peer == Repo.uid) "Saved Messages" else Repo.user(ref.peer!!).value?.let { it.name + " " + it.username } ?: ""
    Kind.GROUP -> Repo.group(ref.id).value?.name ?: ""
    Kind.CHANNEL -> Repo.channel(ref.id).value?.let { it.name + " " + it.handle } ?: ""
}

/** Search across all chats: chat and person names, then message text from the on-phone index. */
@Composable
fun GlobalSearchScreen() {
    SearchIndexer.ensure()
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val chats by remember { Repo.chats() }.collectAsState(emptyList())
    val results = remember { mutableStateListOf<MsgResult>() }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val parsed = remember(q) { SearchQuery.parse(q) }
    val byId = remember(chats) { chats.associateBy { it.ref.id } }
    val listState = rememberLazyListState()

    val names: List<ChatItem> = remember(parsed, chats) {
        if (!parsed.hasText) emptyList() else chats.filter { !ChatLock.isLocked(it.ref.id) && SearchQuery.matches(chatName(it.ref), parsed) }.take(8)
    }

    suspend fun loadPage(reset: Boolean) {
        val p = parsed
        if (!SearchSettings.enabled || p.isEmpty) { if (reset) results.clear(); more = false; return }
        loading = true
        try {
            if (reset) { results.clear(); more = false }
            var cursor = results.lastOrNull()?.let { SearchQuery.Cursor(it.row.ts, it.row.rowId) }
            val blocked = Repo.blockedUsers.value
            val locked = ChatLock.all
            var added = 0; var tries = 0
            while (added < 20 && tries < 5) {
                tries++
                val page = SearchIndex.search(p, null, locked, cursor, 40)
                for (r in page) {
                    val ref = byId[r.chat]?.ref ?: continue                   // chats that are gone are not shown
                    if (r.from in blocked || Repo.isHidden(r.mid) || r.ts <= Repo.cutoff(r.chat)) continue
                    val ranges = SearchQuery.ranges(r.body, p)
                    val sn = SearchQuery.snippet(r.body, ranges)
                    results.add(MsgResult(ref, r, ranges, sn.text, sn.ranges)); added++
                }
                more = page.size >= 40
                if (page.isNotEmpty()) cursor = SearchQuery.Cursor(page.last().ts, page.last().rowId)
                if (!more) break
            }
            searched = q
        } finally { loading = false }
    }

    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    LaunchedEffect(q, chats.size) { delay(250); loadPage(true) }
    LaunchedEffect(listState, results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collect { last ->
            if (more && !loading && last >= results.size + names.size - 3) loadPage(false)
        }
    }

    Column(Modifier.fillMaxSize().background(We.bg)) {
        Row(Modifier.fillMaxWidth().background(We.appBar).statusBarsPadding().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, We.onAppBar, { Nav.pop() })
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (q.isEmpty()) Text("Search chats and messages", color = We.onAppBar.copy(alpha = .6f), style = T.body)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, textStyle = T.body.copy(color = We.onAppBar),
                    cursorBrush = SolidColor(We.onAppBar), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            }
            if (q.isNotEmpty()) IconBtn(Ic.Close, We.onAppBar, { q = "" }, 20.dp, Modifier.size(40.dp))
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            item(key = "people") {
                Row2(Ic.UserPlus, "Find people", "Search by name, username or phone number", We.primary, { Nav.pop(); Nav.push(RNewChat) })
                Divider()
            }
            if (q.isBlank()) item(key = "hint") {
                Text("Search words in all your chats. Put words in quotes to find them together, or add a day like on:2025-01-31, after:2025-01-01 or before:2025-02-01.",
                    color = We.text2, style = T.small, modifier = Modifier.padding(20.dp))
                if (!SearchSettings.enabled) Text("Message search is off. Turn it on in Data & storage.", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (names.isNotEmpty()) {
                item(key = "namesLabel") { SectionLabel("Chats and people") }
                itemsIndexed(names, key = { _, c -> "n_" + c.ref.id }) { _, c ->
                    val (n, photo, seed) = titleOf(c.ref)
                    Row(Modifier.fillMaxWidth().clickable { Nav.push(com.kafisir.wetalks.ui.RChat(c.ref)) }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(n, photo, 44.dp, seed); Spacer(Modifier.width(14.dp))
                        Text(n, color = We.text, style = T.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (results.isNotEmpty()) {
                item(key = "msgLabel") { SectionLabel("Messages") }
                itemsIndexed(results, key = { _, r -> "m_" + r.row.rowId }) { _, r ->
                    val (n, photo, seed) = titleOf(r.ref)
                    val sender = if (r.ref.kind == Kind.GROUP && r.row.from.isNotEmpty()) (if (r.row.from == Repo.uid) "You" else Repo.user(r.row.from).collectAsState().value?.name ?: "") else ""
                    Row(Modifier.fillMaxWidth().clickable { SearchBridge.open(r.ref, q, r.row.mid) }.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
                        Avatar(n, photo, 44.dp, seed); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (r.ref.kind == Kind.DM && r.ref.peer == Repo.uid) "Saved Messages" else n, color = We.text, style = T.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(timeShort(r.row.ts), color = We.text2, style = T.tiny)
                            }
                            val text = remember(r) {
                                buildAnnotatedString {
                                    append(r.snippet)
                                    for (x in r.shifted) addStyle(androidx.compose.ui.text.SpanStyle(background = androidx.compose.ui.graphics.Color(0xFFFFD84D), color = androidx.compose.ui.graphics.Color(0xFF1B1B1B)), x.first, x.last + 1)
                                }
                            }
                            if (sender.isNotEmpty()) Text(sender, color = We.primary, style = T.small)
                            Text(text, color = We.text2, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (loading) item(key = "busy") { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = We.primary, strokeWidth = 2.dp) } }
            else if (q.isNotBlank() && !parsed.isEmpty && results.isEmpty() && names.isEmpty() && searched == q) item(key = "none") {
                Text("Nothing found", color = We.text, style = T.name, modifier = Modifier.padding(start = 20.dp, top = 24.dp))
                Text(if (SearchBackfill.running && SearchBackfill.fraction < 1f) "Older messages are still being added to search (${(SearchBackfill.fraction * 100).toInt()}%). Try again in a moment." else "Locked chats are not searched here. Open a locked chat and use the search inside it.",
                    color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
        }
    }
}

/** "Message search" block for Data & storage: on / off, progress, size, clear. */
@Composable
fun SearchIndexSettings() {
    SearchIndexer.ensure()
    var tick by remember { mutableStateOf(0) }
    val stats by androidx.compose.runtime.produceState<SearchIndex.Stats?>(null, tick, SearchSettings.enabled) { value = SearchIndex.stats() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun mb(b: Long) = if (b < 1048576) "%.0f KB".format(b / 1024.0) else "%.1f MB".format(b / 1048576.0)
    SectionLabel("Message search")
    Row2(Ic.Search, "Search index on this phone", if (SearchSettings.enabled) "On · words of your chats are indexed here so search is fast" else "Off · nothing is indexed", trailing = {
        Toggle(SearchSettings.enabled) { SearchSettings.turn(it); tick++ }
    })
    stats?.let { s ->
        val prog = if (SearchSettings.enabled && SearchBackfill.fraction < 1f && SearchBackfill.running) " · adding older messages (${(SearchBackfill.fraction * 100).toInt()}%)" else ""
        Text("${s.messages} messages from ${s.chats} chats · ${mb(s.bytes)}$prog", color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    }
    Text("The index stays in this app on this phone. It is built from your decrypted messages, never leaves the phone, skips locked chats and is erased when you sign out or turn it off.",
        color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Clear search index", { scope.launch { SearchIndex.clear(); SearchIndexer.clearAll(); tick++; Nav.say("Search index cleared") } }) }
}
