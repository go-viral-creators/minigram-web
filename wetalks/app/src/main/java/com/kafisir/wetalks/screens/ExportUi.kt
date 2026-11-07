package com.kafisir.wetalks.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.DateFormat
import java.util.TimeZone

/** Live value of `chat_settings/<cid>/noExport` (false while unknown). */
@Composable
fun rememberNoExport(cid: String): State<Boolean> {
    val s = remember(cid) { mutableStateOf(false) }
    DisposableEffect(cid) {
        val r = Repo.db.getReference("chat_settings/$cid/noExport")
        val l = object : ValueEventListener {
            override fun onDataChange(d: DataSnapshot) { s.value = d.getValue(Boolean::class.java) == true }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.addValueEventListener(l)
        onDispose { r.removeEventListener(l) }
    }
    return s
}

/** The owner's switch in group / channel settings. Only the owner sees it (the database rules also only let the owner write it). */
@Composable
fun ExportSwitchRows(cid: String, kind: Kind) {
    val off by rememberNoExport(cid)
    val scope = rememberCoroutineScope()
    SectionLabel("Export")
    Row2(Ic.Download, "Members can export this chat", if (off) "Off. Members see: Export is turned off by the ${if (kind == Kind.GROUP) "group" else "channel"} owner." else "On. Anyone who can read the chat can save it as a file.",
        trailing = { Toggle(!off) { allow ->
            scope.launch {
                try { Repo.db.getReference("chat_settings/$cid/noExport").setValue(if (allow) null else true).await() }
                catch (e: Exception) { Nav.say(Repo.explain(e, "Saving the export setting")) }
            }
        } })
    Text(ChatExport.POLICY_NOTE, color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) We.primary else We.field).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(initial: Long?, zone: TimeZone, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val st = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initial?.let { ChatExport.pickerMillis(it, zone) })
    androidx.compose.material3.DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { Text("OK", color = We.primary, style = T.btn, modifier = Modifier.clickable { st.selectedDateMillis?.let { onPick(ChatExport.dayFromPicker(it, zone)) }; onDismiss() }.padding(16.dp)) },
        dismissButton = { Text("Cancel", color = We.text2, style = T.btn, modifier = Modifier.clickable(onClick = onDismiss).padding(16.dp)) }) {
        androidx.compose.material3.DatePicker(st)
    }
}

/**
 * The "Export chat" sheet: pick the dates, choose whether to include media, see the size, start. The work itself runs in the background
 * (a WorkManager job with a notification), so this sheet can be closed; opening it again shows the progress or the finished file.
 */
@Composable
fun ExportSheet(ref: ChatRef, title: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { TimeZone.getDefault() }
    val now = remember { System.currentTimeMillis() }
    var preset by remember { mutableStateOf("all") }
    var fromDay by remember { mutableStateOf<Long?>(null) }
    var toDay by remember { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf(false) }
    var again by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    val range = when (preset) { "7" -> ChatExport.lastDays(7, now, zone); "30" -> ChatExport.lastDays(30, now, zone); "custom" -> ChatExport.wholeDays(fromDay, toDay, zone); else -> ChatExport.Range() }
    val problem = ChatExport.rangeProblem(range, now)
    val locked = ChatLock.isLocked(ref.id)
    val unlocked = ChatLock.isOpen(ref.id)

    // may this person export? (read fresh from the server: the group owner can turn it off)
    val policy by produceState<Pair<Boolean, String?>>(false to null, ref.id) { value = true to ExportSource.blockedReason(ref) }
    val infos by remember(ref.id) { ExportManager.watch(ctx, ref.id) }.collectAsState(emptyList())
    val info = infos.lastOrNull()
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED
    LaunchedEffect(running) { if (running) starting = false }

    // the size estimate (only needed when media is included)
    var scan by remember { mutableStateOf<ExportSource.Scan?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var counted by remember { mutableLongStateOf(0L) }
    var scanErr by remember { mutableStateOf<String?>(null) }
    val ready = policy.first && policy.second == null && unlocked
    LaunchedEffect(range, media, running, ready) {
        scan = null; scanErr = null
        if (media && !running && ready && problem == null) {
            scanning = true; counted = 0
            try { scan = ExportSource.scan(ref, range) { counted = it } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { scanErr = Repo.explain(e, "Counting the media") }
            finally { scanning = false }
        }
    }

    val needed = ChatExport.neededBytes(scan?.mediaBytes ?: 0, scan?.messages ?: 0, media)
    val free = remember(scan, media) { ExportFiles.free(ctx) }
    val roomOk = ChatExport.enoughSpace(free, needed)
    val canStart = ready && problem == null && !scanning && roomOk && !starting && (!media || scan != null)

    Sheet(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            Text("Export chat", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
            Text(if (locked) "Locked chat" else title, color = We.text2, style = T.small, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
            val state = info?.state
            when {
                !unlocked -> Text("Unlock this chat first, then try again.", color = We.text2, style = T.body, modifier = Modifier.padding(24.dp))
                !policy.first -> Text("Checking…", color = We.text2, style = T.body, modifier = Modifier.padding(24.dp))
                policy.second != null -> {
                    Row2(Ic.Lock, policy.second!!, "Only the owner can turn this on or off.")
                    Text(ChatExport.POLICY_NOTE, color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                running -> {
                    val msgs = info!!.progress.getLong("msgs", 0L); val files = info.progress.getInt("files", 0)
                    Text(if (state == WorkInfo.State.ENQUEUED) "Waiting to start (needs an internet connection)…" else "Exporting… " + "%,d messages".format(msgs) + if (files > 0) " · %,d files".format(files) else "",
                        color = We.text, style = T.body, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp), color = We.primary)
                    Text("You can close this and keep using the app. A notification shows the progress and has a Cancel button.", color = We.text2, style = T.small, modifier = Modifier.padding(24.dp, 12.dp))
                    OutlineButton("Cancel export", { ExportManager.cancel(ctx, ref.id) }, Modifier.padding(horizontal = 24.dp))
                }
                state == WorkInfo.State.SUCCEEDED && !again -> {
                    val out = info!!.outputData; val path = out.getString("path").orEmpty(); val f = File(path)
                    if (path.isEmpty() || !f.exists()) {
                        Text("The exported file was removed by the phone to free space. Please export again.", color = We.text2, style = T.body, modifier = Modifier.padding(24.dp))
                        PrimaryButton("Export again", { again = true }, Modifier.padding(horizontal = 24.dp))
                    } else {
                        Text("Export ready", color = We.text, style = T.name, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                        Text(f.name + " · " + ChatExport.exactSize(f.length()), color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp))
                        Text(out.getString("text").orEmpty(), color = We.text, style = T.body, modifier = Modifier.padding(24.dp, 10.dp))
                        PrimaryButton("Share", {
                            try { ctx.startActivity(ExportFiles.shareIntent(ctx, f, if (locked) null else title, out.getBoolean("zip", false))) }
                            catch (e: ActivityNotFoundException) { Nav.say("No app found to share this file") }
                            catch (e: Exception) { Nav.say(Repo.explain(e, "Sharing the export")) }
                        }, Modifier.padding(horizontal = 24.dp))
                        Spacer(Modifier.height(10.dp))
                        OutlineButton("Export again", { again = true }, Modifier.padding(horizontal = 24.dp))
                    }
                }
                state == WorkInfo.State.FAILED && !again -> {
                    Text(info!!.outputData.getString("error") ?: "The export failed.", color = We.danger, style = T.body, modifier = Modifier.padding(24.dp))
                    PrimaryButton("Try again", { again = true }, Modifier.padding(horizontal = 24.dp))
                }
                else -> {
                    SectionLabel("Dates")
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("All", preset == "all") { preset = "all" }
                        Chip("Last 7 days", preset == "7") { preset = "7" }
                        Chip("Last 30 days", preset == "30") { preset = "30" }
                        Chip("Pick dates", preset == "custom") { preset = "custom" }
                    }
                    if (preset == "custom") {
                        val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
                        Row2(Ic.Clock, "From", fromDay?.let { df.format(it) } ?: "The first message", onClick = { picking = "from" })
                        Row2(Ic.Clock, "To", toDay?.let { df.format(it) } ?: "Today", onClick = { picking = "to" })
                        if (problem != null) Text(problem, color = We.danger, style = T.small, modifier = Modifier.padding(horizontal = 24.dp))
                    }
                    SectionLabel("Media")
                    Row2(Ic.Image, "Include photos, voice and files", if (media) "Makes one .zip file with the text and the media" else "Makes one .txt file with the text only", trailing = { Toggle(media) { media = it } })
                    if (media) Text(when {
                        scanning -> "Counting… %,d messages so far".format(counted)
                        scanErr != null -> scanErr!!
                        scan != null -> "%,d messages · ".format(scan!!.messages) + (if (scan!!.mediaItems == 0L) "no media" else "%,d media files, ".format(scan!!.mediaItems) + ChatExport.sizeLabel(scan!!.mediaBytes) + " of media (an estimate)")
                        else -> ""
                    }, color = We.text2, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    if (!roomOk) Text("Not enough free space: about ${ChatExport.sizeLabel(needed).removePrefix("about ")} needed, ${ChatExport.exactSize(free)} free.", color = We.danger, style = T.small, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Ic.Alert, We.text2, 18.dp); Spacer(Modifier.width(10.dp))
                        Text("The exported file is not encrypted. Anyone who gets it can read the chat.", color = We.text2, style = T.small)
                    }
                    PrimaryButton("Start export", {
                        starting = true; again = false
                        ExportManager.start(ctx, ExportRequest(ref.id, ref.kind, ref.peer, title, range, media, locked, scan?.mediaBytes ?: 0, scan?.messages ?: 0))
                    }, Modifier.padding(horizontal = 24.dp), enabled = canStart, loading = starting)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    picking?.let { which ->
        DayPicker(if (which == "from") fromDay else toDay, zone, { d -> if (which == "from") fromDay = d else toDay = d }, { picking = null })
    }
}
