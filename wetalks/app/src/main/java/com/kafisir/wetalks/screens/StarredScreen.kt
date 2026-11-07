package com.kafisir.wetalks.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.ChatLock
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Kind
import com.kafisir.wetalks.data.Msg
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.data.Starred
import com.kafisir.wetalks.ui.Avatar
import com.kafisir.wetalks.ui.Divider
import com.kafisir.wetalks.ui.Empty
import com.kafisir.wetalks.ui.Ic
import com.kafisir.wetalks.ui.IconBtn
import com.kafisir.wetalks.ui.Nav
import com.kafisir.wetalks.ui.T
import com.kafisir.wetalks.ui.Text
import com.kafisir.wetalks.ui.TopBar
import com.kafisir.wetalks.ui.We
import com.kafisir.wetalks.ui.timeShort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Starred messages of all chats (or one chat), newest message first. Tap one to open the chat at that message. */
@Composable
fun StarredScreen(only: ChatRef?) {
    androidx.compose.runtime.LaunchedEffect(Unit) { Starred.watch() }
    val chats by remember { Repo.chats() }.collectAsState(null)
    val entries = Starred.entries
    val loaded = remember { mutableStateMapOf<String, Msg>() }
    val gone = remember { mutableStateMapOf<String, Boolean>() }
    val refs = chats?.associate { it.ref.id to it.ref }.orEmpty()
    // locked chats stay hidden here until they are unlocked in the chat itself
    val shown = entries.filter { (only == null || it.cid == only.id) && !ChatLock.isLocked(it.cid) && refs.containsKey(it.cid) }

    LaunchedEffect(shown.map { it.mid }, chats != null) {
        val todo = shown.filter { it.mid !in loaded && it.mid !in gone }
        val dead = ArrayList<Starred.Entry>()
        for (batch in todo.chunked(6)) {
            coroutineScope {
                batch.map { e -> async {
                    val ref = refs[e.cid] ?: return@async
                    when (val r = Starred.load(ref, e.mid)) {
                        is Starred.Loaded.Ok -> loaded[e.mid] = r.msg
                        Starred.Loaded.Gone -> { gone[e.mid] = true; dead.add(e) }
                        Starred.Loaded.Failed -> {}
                    }
                } }.awaitAll()
            }
        }
        if (dead.isNotEmpty()) Starred.drop(dead)               // the message was deleted: forget the star quietly
    }

    val rows = shown.mapNotNull { e -> loaded[e.mid]?.let { e to it } }.sortedByDescending { it.second.ts }

    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(if (only == null) "Starred messages" else "Starred in this chat", onBack = { Nav.pop() })
        if (rows.isEmpty()) {
            if (shown.isEmpty() || chats != null && todoDone(shown.map { it.mid }, loaded.keys, gone.keys))
                Empty(Ic.Star, "No starred messages", "Press and hold a message, then choose Star. Starred messages from all your phones appear here.")
            else Empty(Ic.Star, "Loading…", "Reading your starred messages.")
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.first.mid }) { (e, m) ->
                val ref = refs[e.cid]!!
                val (n, photo, seed) = titleOf(ref)
                val who = if (m.from == Repo.uid) "You" else Repo.user(m.from).collectAsState().value?.name ?: ""
                Row(Modifier.fillMaxWidth().clickable { Nav.openChatAt(ref, e.mid) }.padding(start = 16.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.Top) {
                    Avatar(n, photo, 44.dp, seed); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (ref.kind == Kind.DM && ref.peer == Repo.uid) "Saved Messages" else n, color = We.text, style = T.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(timeShort(m.ts), color = We.text2, style = T.tiny)
                        }
                        if (who.isNotEmpty()) Text(who, color = We.primary, style = T.small)
                        Text(previewText(m.type, m.text), color = We.text2, style = T.small, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(Ic.Star, We.primary, { Starred.set(e.cid, e.mid, false) }, 20.dp)
                }
                Divider(start = 74.dp)
            }
        }
    }
}

private fun todoDone(mids: List<String>, ok: Set<String>, gone: Set<String>) = mids.all { it in ok || it in gone } && ok.isEmpty()
