package com.kafisir.wetalks.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun mb(b: Long) = if (b < 1048576) "%.0f KB".format(b / 1024.0) else if (b < 1L shl 30) "%.1f MB".format(b / 1048576.0) else "%.2f GB".format(b / 1073741824.0)
private fun whenText(ts: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

@Composable
private fun Chips(options: List<Pair<String, Boolean>>, pick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (n, on) -> Text(n, color = if (on) We.onPrimary else We.text, style = T.small.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) We.primary else We.field).clickable { pick(i) }.padding(horizontal = 16.dp, vertical = 9.dp)) }
    }
}

@Composable
private fun Note(t: String, danger: Boolean = false) = Text(t, color = if (danger) We.danger else We.text2, style = T.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

/** Me -> Data & storage -> Chat backup. */
@Composable
fun BackupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { BackupStore.ensure(ctx); DriveAuth.init(ctx) }
    val progress by BackupStore.progress.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val files by produceState<List<BackupFiles.Local>>(emptyList(), tick, progress == null) { value = withContext(Dispatchers.IO) { BackupStore.local(ctx) } }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var passErr by remember { mutableStateOf<String?>(null) }
    var busyPass by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<BackupFiles.Local?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<BackupFiles.Local?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = saving; saving = null
        if (uri != null && f != null) scope.launch {
            try { withContext(Dispatchers.IO) { BackupStore.copyTo(ctx, f.file, uri) }; Nav.say("Backup copy saved") }
            catch (e: Exception) { Nav.toast.value = Repo.explain(e, "Saving the copy") }
        }
    }
    val total = files.sumOf { it.size }

    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Chat backup", onBack = { Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Status")
            val last = BackupStore.lastSuccess
            Row2(Ic.Archive, if (last > 0) "Last backup " + whenText(last) else "No backup yet",
                if (files.isEmpty()) "Nothing stored on this phone" else "${files.size} file(s) · ${mb(total)} on this phone")
            if (BackupStore.lastMessage.isNotEmpty()) Note(BackupStore.lastMessage)
            if (BackupPlan.overdue(BackupStore.freq, last, System.currentTimeMillis(), BackupStore.scheduledSince)) Note("The automatic backup has not worked for a while. Check that background activity is allowed for WeTalks.", true)
            val p = progress
            if (p != null) {
                Note(p.stage + (if (p.chatsTotal > 0) " (${p.chatsDone}/${p.chatsTotal})" else "") + (if (p.messages > 0) " · ${p.messages} messages" else ""))
                Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Cancel", { BackupScheduler.cancel(ctx) }) }
            } else Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { PrimaryButton("Back up now", { BackupScheduler.backUpNow(ctx); Nav.say("Backup started") }) }

            SectionLabel("Automatic backup")
            Chips(BackupFreq.entries.map { it.label to (BackupStore.freq == it) }) { BackupStore.setFreq(ctx, BackupFreq.entries[it]) }
            BackupPlan.nextRun(BackupStore.freq, last, System.currentTimeMillis())?.let { Note("Next backup: " + if (it <= System.currentTimeMillis() + 60_000) "soon" else whenText(it)) }
            Row2(Ic.Globe, "Only on Wi-Fi", "Do not use mobile data", trailing = { Toggle(BackupStore.wifiOnly) { BackupStore.setWifiOnly(ctx, it) } })
            Row2(Ic.Clock, "Only while charging", null, trailing = { Toggle(BackupStore.charging) { BackupStore.setCharging(ctx, it) } })
            Row2(Ic.Image, "Include photos and voice notes", "Only the ones saved on this phone. Makes the backup bigger.", trailing = { Toggle(BackupStore.includeMedia) { BackupStore.setIncludeMedia(ctx, it) } })
            Note("Backups are made in the background and carry on where they stopped if the phone stops them. Locked chats and channels are not included.")

            SectionLabel("Protect with a passphrase")
            if (BackupStore.encrypted) {
                Row2(Ic.Lock, "Backups are encrypted", "AES-256 with your passphrase. If you forget it, nobody can open them.", trailing = { Text("Turn off", color = We.danger, style = T.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable { BackupStore.clearPassphrase(ctx); tick++ }.padding(8.dp)) })
                Note("Older backup files keep the passphrase they were made with.")
            } else {
                Note("Without a passphrase the backup file contains your messages as readable text. Anyone who gets the file can read it. Use a passphrase that is different from your key-backup passphrase.", true)
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(pass, { pass = it; passErr = null }, "New passphrase (8+ characters)", password = true)
                    Field(pass2, { pass2 = it; passErr = null }, "Repeat passphrase", password = true, error = passErr)
                    PrimaryButton("Turn on encryption", {
                        when {
                            pass.length < 8 -> passErr = "Use at least 8 characters."
                            pass != pass2 -> passErr = "The two passphrases are not the same."
                            else -> { busyPass = true; scope.launch { try { BackupStore.setPassphrase(ctx, pass); pass = ""; pass2 = ""; Nav.say("Encryption is on") } catch (e: Exception) { passErr = Repo.explain(e, "Setting the passphrase") }; busyPass = false } }
                        }
                    }, loading = busyPass, enabled = !busyPass)
                }
            }

            SectionLabel("Keep")
            Chips((1..3).map { "$it set" + (if (it > 1) "s" else "") to (BackupStore.keepSets == it) }) { BackupStore.setKeepSets(ctx, it + 1) }
            Note("A set is one full backup plus the small ones made after it. Older sets are deleted automatically.")

            SectionLabel("Backups on this phone")
            if (files.isEmpty()) Note("None yet.")
            files.forEach { l ->
                Row2(Ic.File, whenText(l.manifest.created) + if (l.manifest.full) " · full" else " · update",
                    mb(l.size) + " · ${l.manifest.messageCount} messages" + (if (l.manifest.media.isNotEmpty()) " · ${l.manifest.media.size} media" else "") + (if (l.encrypted) " · encrypted" else " · not encrypted"),
                    onClick = { menu = l })
            }
            if (files.isNotEmpty()) Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Delete all backups on this phone", { confirmAll = true }) }

            SectionLabel("Restore")
            Row2(Ic.Download, "Restore a backup", "From a backup file on this phone, a USB drive or cloud storage", onClick = { Nav.push(RRestoreBackup) })

            SectionLabel("Google Drive")
            Row2(Ic.Globe, "Back up to Google Drive", DriveAuth.NOT_SET_UP + ". The app owner must finish the Google setup first.", trailing = { Toggle(false) { Nav.toast.value = DriveAuth.NOT_SET_UP + "." } })
            Note("Your backup file is also kept on this phone. Use \"Save a copy\" on a backup to put it in your own Drive or on a computer.")
        }
    }

    menu?.let { l ->
        Sheet(onDismiss = { menu = null }) {
            Text(whenText(l.manifest.created), color = We.text, style = T.title, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SheetItem(Ic.Download, "Save a copy") { saving = l; menu = null; create.launch(l.file.name) }
            SheetItem(Ic.Share, "Share") {
                menu = null
                try {
                    val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", l.file)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share backup"))
                } catch (e: Exception) { Nav.toast.value = Repo.explain(e, "Sharing the backup") }
            }
            SheetItem(Ic.Trash, "Delete this backup", danger = true) { menu = null; BackupFiles.delete(l); tick++ }
        }
    }
    if (confirmAll) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmAll = false }, containerColor = We.surface,
        title = { Text("Delete all backups?", color = We.text) }, text = { Text("This removes the backup files from this phone. Copies you saved elsewhere stay. Your chats are not changed.", color = We.text2) },
        confirmButton = { Text("Delete", color = We.danger, modifier = Modifier.clickable { confirmAll = false; BackupStore.deleteAll(ctx); tick++ }.padding(12.dp)) },
        dismissButton = { Text("Cancel", color = We.text, modifier = Modifier.clickable { confirmAll = false }.padding(12.dp)) })
}

/** Restore from one or more backup files (a backup is a set of files: one full plus updates; they can be picked together, in any order). */
@Composable
fun RestoreBackupScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var needPass by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picked = uris; error = null; status = ""
        scope.launch {
            needPass = withContext(Dispatchers.IO) { uris.any { u -> try { ctx.contentResolver.openInputStream(u)?.use { BackupReader.peek(it).encrypted } == true } catch (e: Exception) { false } } }
        }
    }
    fun name(u: Uri) = try { ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (e: Exception) { null } ?: (u.lastPathSegment ?: "backup")

    fun start() {
        running = true; error = null; status = "Starting"
        job = scope.launch {
            val me = Repo.uid
            try {
                if (me.isEmpty()) throw BackupException(BackupException.Kind.WRONG_ACCOUNT, "Sign in first.")
                val store = ArchiveStore.get(); val account = BackupFormat.accountTag(me)
                var msgs = 0; var chats = 0; var media = 0; var settingsNote = ""
                val self = coroutineContext[Job]!!
                val keys = HashMap<String, BackupKey>()
                for ((i, u) in picked.withIndex()) {
                    status = "Checking and restoring file ${i + 1} of ${picked.size}"
                    val r = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(u)!!.use { raw ->
                            BackupRestore(store, account).restore(raw, { h ->
                                if (!h.encrypted) null else keys.getOrPut(BackupFormat.b64(h.salt)) { BackupFormat.keyFor(h, pass.ifEmpty { throw BackupException(BackupException.Kind.NEEDS_PASSPHRASE, "Enter the passphrase of this backup.") }) }
                            }, check = { self.ensureActive() })
                        }
                    }
                    msgs += r.messagesAdded; chats += r.chats; media += r.media
                    val s = BackupSettingsRestore.apply(ctx, r.settings); if (s.isNotEmpty()) settingsNote = s
                }
                status = "Restored $msgs messages in $chats chats" + (if (media > 0) " and $media photos and voice notes" else "") + "." + (if (settingsNote.isNotEmpty()) " Also restored: $settingsNote." else "") + (if (msgs == 0) " (They were already on this phone.)" else "")
            } catch (e: kotlinx.coroutines.CancellationException) { status = "Stopped. Nothing from the stopped file was added."
            } catch (e: BackupException) {
                if (e.kind == BackupException.Kind.WRONG_PASSPHRASE) pass = ""
                status = ""; error = e.message
            } catch (e: Exception) { status = ""; error = Repo.explain(e, "Restoring the backup") }
            running = false
        }
    }

    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar("Restore chat backup", onBack = { if (!running) Nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Note("Choose the backup file(s). If a backup is a full file plus updates, pick all of them together. Messages are added to a read-only archive on this phone and shown in your chats where the server no longer has them. Nothing is sent to other people and nothing on the server is changed or duplicated.")
            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) { OutlineButton("Choose backup file(s)", { if (!running) pick.launch(arrayOf("*/*")) }) }
            picked.forEach { Row2(Ic.File, name(it), null) }
            if (needPass && picked.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Field(pass, { pass = it }, "Backup passphrase", password = true) }
            if (picked.isNotEmpty()) Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                if (running) OutlineButton("Cancel", { job?.cancel() }) else PrimaryButton("Restore", { start() }, enabled = !needPass || pass.isNotEmpty())
            }
            if (status.isNotEmpty()) Note(status)
            error?.let { Note(it, true) }
        }
    }
}
