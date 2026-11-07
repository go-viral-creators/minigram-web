package com.kafisir.wetalks.data

import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Exporting a chat to a text file (and a zip with the media), as plain Kotlin with no Android and no Firebase, so every rule is unit tested.
 *
 * Text format (like WhatsApp's, stable so other tools can read it):
 *     [12/03/2025, 14:05] Alice: Hello
 *     [12/03/2025, 14:06] Bob: first line
 *     second line of the same message
 *     [12/03/2025, 14:07] Bob: <attached: IMG-20250312-0001.jpg>
 *     [12/03/2025, 14:09] Voice call · 2:31
 * The date follows the phone's language (day/month order), the year has 4 digits, the time is 24-hour. Deleted messages are left out.
 */
object ChatExport {
    const val TXT_NAME = "_chat.txt"
    const val NOTES_NAME = "_export_notes.txt"
    const val DAY = 86_400_000L

    // ------------------------------------------------------------------------------------------ date range
    /** Both ends are inclusive and optional (null = no limit). */
    data class Range(val from: Long? = null, val to: Long? = null) {
        fun contains(ts: Long) = (from == null || ts >= from) && (to == null || ts <= to)
        val isAll get() = from == null && to == null
    }

    private fun cal(ms: Long, zone: TimeZone): Calendar = GregorianCalendar(zone).apply { timeInMillis = ms }
    fun startOfDay(ms: Long, zone: TimeZone): Long = cal(ms, zone).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun endOfDay(ms: Long, zone: TimeZone): Long = cal(startOfDay(ms, zone), zone).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis - 1

    /** "Last [n] days": today and the n-1 days before it, from midnight. */
    fun lastDays(n: Int, now: Long, zone: TimeZone): Range = Range(cal(startOfDay(now, zone), zone).apply { add(Calendar.DAY_OF_MONTH, -(n - 1).coerceAtLeast(0)) }.timeInMillis, null)

    /** A day picked by the person (any moment inside it) to the range that covers whole days. */
    fun wholeDays(fromDay: Long?, toDay: Long?, zone: TimeZone) = Range(fromDay?.let { startOfDay(it, zone) }, toDay?.let { endOfDay(it, zone) })

    /** The Material date picker answers with midnight UTC of the chosen calendar day: that day, in this phone's time zone. */
    fun dayFromPicker(utcMidnight: Long, zone: TimeZone): Long {
        val u = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
        return GregorianCalendar(zone).apply { clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 0, 0, 0) }.timeInMillis
    }

    /** The opposite: a local day to the midnight UTC value the Material date picker wants as its start value. */
    fun pickerMillis(localMs: Long, zone: TimeZone): Long {
        val l = GregorianCalendar(zone).apply { timeInMillis = localMs }
        return GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH), 0, 0, 0) }.timeInMillis
    }

    /** A readable problem with the chosen range, or null. */
    fun rangeProblem(r: Range, now: Long): String? = when {
        r.from != null && r.to != null && r.from > r.to -> "The first date is after the last date."
        r.from != null && r.from > now -> "The first date is in the future."
        else -> null
    }

    /** Message ids are Firebase push ids: the first 8 characters are the time in milliseconds. This gives the smallest id that can belong to [ts]. */
    private const val PUSH = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"
    fun pushIdFloor(ts: Long): String {
        var t = ts.coerceAtLeast(0); val c = CharArray(8)
        for (i in 7 downTo 0) { c[i] = PUSH[(t % 64).toInt()]; t /= 64 }
        return String(c) + "-".repeat(12)
    }
    fun pushIdTime(id: String): Long? {
        if (id.length != 20) return null
        var t = 0L
        for (i in 0 until 8) { val v = PUSH.indexOf(id[i]); if (v < 0) return null; t = t * 64 + v }
        return t
    }
    /** Where reading may start for a range. Ids come from each phone's clock, so a safety margin of 3 days is kept; the exact time filter still runs on every message. */
    fun startKeyFor(r: Range): String? = r.from?.let { if (it <= 3 * DAY) null else pushIdFloor(it - 3 * DAY) }

    // ------------------------------------------------------------------------------------------ date and time text
    /** The phone's short date pattern with 2-digit day and month and a 4-digit year ("dd/MM/yyyy", "MM/dd/yyyy", "dd.MM.yyyy"...). */
    fun datePattern(locale: Locale): String {
        val raw = (java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern() ?: return "dd/MM/yyyy"
        return normalizePattern(raw)
    }
    fun normalizePattern(p: String): String {
        val out = StringBuilder(); var i = 0; var quoted = false; var hasY = false; var hasM = false; var hasD = false
        while (i < p.length) {
            val ch = p[i]
            if (ch == '\'') { quoted = !quoted; i++; continue }          // text in quotes is dropped: a stable pattern has no words
            if (quoted) { i++; continue }
            if (ch.isLetter()) {
                var j = i; while (j < p.length && p[j] == ch) j++
                when (ch) { 'y', 'u' -> { out.append("yyyy"); hasY = true }; 'M', 'L' -> { out.append("MM"); hasM = true }; 'd' -> { out.append("dd"); hasD = true }; else -> return "dd/MM/yyyy" }
                i = j
            } else { out.append(ch); i++ }
        }
        return if (hasY && hasM && hasD) out.toString().trim() else "dd/MM/yyyy"
    }

    /** Formats time stamps. Not thread-safe: one per export. Digits are always 0-9 and the calendar is always Gregorian, whatever the phone's language. */
    class Fmt(locale: Locale = Locale.getDefault(), val zone: TimeZone = TimeZone.getDefault(), pattern: String? = null) {
        private val loc = Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()
        private fun f(p: String) = SimpleDateFormat(p, loc).apply { calendar = GregorianCalendar(zone); timeZone = zone }
        private val dateF = f(pattern ?: datePattern(locale)); private val timeF = f("HH:mm"); private val dayF = f("yyyyMMdd"); private val isoF = f("yyyy-MM-dd")
        fun stamp(ts: Long): String { val d = java.util.Date(ts); return "[" + dateF.format(d) + ", " + timeF.format(d) + "]" }
        fun day(ts: Long): String = dayF.format(java.util.Date(ts))
        fun isoDay(ts: Long): String = isoF.format(java.util.Date(ts))
    }

    // ------------------------------------------------------------------------------------------ one message as text
    /** A message ready to write. [text] is already decrypted. [tag] lets the caller keep its own object for fetching media. */
    class Item(
        val id: String, val ts: Long, val name: String, val type: String, val text: String,
        val unreadable: Boolean = false, val edited: Boolean = false,
        val fwdName: String? = null, val replyName: String? = null, val replyText: String? = null,
        val mime: String = "", val fileName: String = "", val dur: Int = 0, val w: Int = 0, val h: Int = 0, val size: Long = 0,
        val callVideo: Boolean = false, val callState: String = "", val callMine: Boolean = false,
        val mediaId: String = "", val tag: Any? = null,
    ) {
        val hasMedia get() = mediaId.isNotEmpty() && type in MEDIA_TYPES
    }
    val MEDIA_TYPES = setOf("image", "gif", "voice", "video", "audio", "file", "sticker")

    /** Removes control characters (keeps new lines and tabs) and turns every kind of new line into \n. */
    fun clean(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' -> { sb.append('\n'); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                c == '\n' || c == '\t' -> sb.append(c)
                c.code < 32 || c.code == 0x7f || c == ' ' || c == ' ' -> if (c == ' ' || c == ' ') sb.append('\n')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private val HEADER_LIKE = Regex("^\\[\\d")
    /** The lines of a body. A later line that would look like the start of a new message gets one space in front, so readers never see a fake message. */
    fun bodyLines(text: String): List<String> = clean(text).split('\n').mapIndexed { i, l -> if (i > 0 && HEADER_LIKE.containsMatchIn(l)) " $l" else l }

    fun quote(s: String, max: Int = 60): String {
        val one = clean(s).replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max - 1).trimEnd() + "…"
    }

    fun callText(video: Boolean, state: String, dur: Int, mine: Boolean): String {
        val kind = if (video) "Video call" else "Voice call"
        return when (state) {
            "ended" -> kind + " · " + CallLog.duration(dur)
            "declined" -> "$kind · declined"
            else -> if (mine) "$kind · no answer" else "Missed " + kind.lowercase()
        }
    }
    /** A short text for a message that is quoted in a reply. */
    fun previewOf(type: String, text: String): String = when (type) {
        "image" -> "Photo" + if (text.isNotBlank()) ": $text" else ""
        "voice" -> "Voice message"; "sticker" -> "Sticker"; "gif" -> "GIF"; "video" -> "Video"; "audio" -> "Audio"; "file" -> "Document"; "call" -> "Call"
        else -> text
    }
    fun pinNotice() = "A message was pinned"
    fun disappearingNotice(seconds: Long): String = when {
        seconds <= 0 -> "Disappearing messages were turned off"
        seconds % 86_400 == 0L -> "Disappearing messages were turned on: " + (seconds / 86_400).let { if (it == 1L) "1 day" else "$it days" }
        seconds % 3600 == 0L -> "Disappearing messages were turned on: " + (seconds / 3600).let { if (it == 1L) "1 hour" else "$it hours" }
        else -> "Disappearing messages were turned on"
    }

    /** What stands for the media in the text: the file name when it is in the zip, else "<Media omitted>". [placeholder] is null when there is no media. */
    fun mediaPlaceholder(fileName: String?, failed: Boolean = false): String = when {
        failed -> "<Media not available" + (if (fileName != null) ": $fileName" else "") + ">"
        fileName != null -> "<attached: $fileName>"
        else -> "<Media omitted>"
    }

    /** The full text of one message (one or more lines, each ending with \n). [placeholder] replaces the media; null for plain text messages. */
    fun messageText(m: Item, fmt: Fmt, placeholder: String? = null): String {
        val head = fmt.stamp(m.ts)
        if (m.type == "call") return head + " " + callText(m.callVideo, m.callState, m.dur, m.callMine) + "\n"
        val tags = StringBuilder()
        if (m.fwdName != null) tags.append("[Forwarded").append(if (m.fwdName.isNotEmpty()) " from ${quote(m.fwdName, 40)}" else "").append("] ")
        if (m.replyName != null) tags.append("[Reply to ${quote(m.replyName, 40)}").append(if (!m.replyText.isNullOrBlank()) ": \"" + quote(m.replyText, 60) + "\"" else "").append("] ")
        val lines = ArrayList<String>()
        when {
            m.unreadable -> lines += "<This message could not be decrypted>"
            m.type == "sticker" && placeholder == null -> lines += "<sticker" + (if (m.text.isNotBlank() && m.text.length <= 16) ": ${clean(m.text).trim()}" else "") + ">"
            placeholder != null -> { lines += placeholder; if (m.text.isNotBlank()) lines += bodyLines(m.text) }
            else -> lines += bodyLines(m.text)
        }
        if (m.edited && !m.unreadable) lines[lines.lastIndex] = lines.last() + " <This message was edited>"
        lines[0] = tags.toString() + lines[0]
        val sb = StringBuilder(head).append(' ').append(quote(m.name, 80).ifEmpty { "Unknown" }).append(": ").append(lines[0]).append('\n')
        for (i in 1 until lines.size) sb.append(lines[i]).append('\n')
        return sb.toString()
    }
    fun systemText(ts: Long, text: String, fmt: Fmt) = fmt.stamp(ts) + " " + quote(text, 200) + "\n"

    // ------------------------------------------------------------------------------------------ file names
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")
    private const val ILLEGAL = "\\/:*?\"<>|‮‭‎‏"

    /** A name that is safe on Android, Windows, macOS and inside a zip: no folders, no illegal or control characters, not reserved, at most [max] characters, extension kept. */
    fun safeName(raw: String?, fallback: String = "file", max: Int = 100): String {
        var n = (raw ?: "").map { if (it in ILLEGAL || it.code < 32 || it.code == 0x7f) '_' else it }.joinToString("")
        n = n.trim().trim('.', ' ').replace(Regex("_{2,}"), "_")
        val dot = n.lastIndexOf('.')
        var base = if (dot > 0) n.substring(0, dot) else n
        var ext = if (dot > 0) n.substring(dot) else ""
        if (ext.length > 12) { base = n; ext = "" }
        base = base.trim().trim('.', ' ', '_')
        if (base.isEmpty()) base = fallback
        if (base.uppercase() in RESERVED) base = "_$base"
        if (base.length + ext.length > max) base = base.take((max - ext.length).coerceAtLeast(1)).trimEnd('.', ' ')
        return base + ext
    }

    /** The name of the file that is shared, for example "WeTalks chat with Alice 2025-03-12.zip". */
    fun exportFileName(title: String?, whenMs: Long, zip: Boolean, fmt: Fmt, group: Boolean = false): String {
        val t = safeName(title, "chat", 50).removeSuffix(".")
        return safeName("WeTalks chat " + (if (group) "" else "with ") + t + " " + fmt.isoDay(whenMs), "WeTalks chat") + if (zip) ".zip" else ".txt"
    }

    /** A zip entry name: one flat name, no folder parts, never starting with a dot or slash, never ".." */
    fun zipEntryName(raw: String): String = safeName(raw.substringAfterLast('/').substringAfterLast('\\'), "file")

    /** Hands out unique names, case-insensitively (zip tools on Windows and macOS ignore case). */
    class Namer(private val fmt: Fmt) {
        private val used = HashSet<String>()
        private val counters = HashMap<String, Int>()
        val usedCount get() = used.size

        private fun unique(name: String): String {
            if (used.add(name.lowercase())) return name
            val dot = name.lastIndexOf('.').let { if (it <= 0) name.length else it }
            val base = name.substring(0, dot); val ext = name.substring(dot)
            var k = 2
            while (true) { val c = "$base ($k)$ext"; if (used.add(c.lowercase())) return c; k++ }
        }

        /** Photos, GIFs and voice messages get "IMG-20250312-0001.jpg"; files that came with their own name keep it (cleaned). */
        fun assign(type: String, ts: Long, originalName: String, ext: String): String {
            val e = ext.lowercase().trim('.').filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
            if ((type == "video" || type == "audio" || type == "file") && originalName.isNotBlank()) return unique(zipEntryName(originalName))
            val prefix = when (type) { "image" -> "IMG"; "gif" -> "GIF"; "voice" -> "VOICE"; "sticker" -> "STK"; "video" -> "VID"; "audio" -> "AUD"; else -> "DOC" }
            val key = prefix + "-" + fmt.day(ts)
            val n = (counters[key] ?: 0) + 1; counters[key] = n
            return unique(key + "-" + "%04d".format(n) + "." + e)
        }
    }

    /** The extension for downloaded bytes: from the first bytes, else from the mime type, else from the original name. */
    fun extFor(head: ByteArray, mime: String, originalName: String, type: String = ""): String {
        fun at(off: Int, s: String) = head.size >= off + s.length && s.indices.all { head[off + it] == s[it].code.toByte() }
        return when {
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "jpg"
            head.size >= 4 && head[0] == 0x89.toByte() && at(1, "PNG") -> "png"
            at(0, "GIF8") -> "gif"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(4, "ftyp") && (type == "voice" || mime.startsWith("audio/") || at(8, "M4A")) -> "m4a"
            else -> when (mime.lowercase()) {
                "image/jpeg", "image/jpg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"
                "audio/mp4", "audio/aac", "audio/x-m4a" -> "m4a"; "audio/ogg", "audio/opus" -> "ogg"; "audio/mpeg" -> "mp3"
                else -> originalName.substringAfterLast('.', "").lowercase().take(5)
            }
        }.ifEmpty { if (type == "voice") "m4a" else "bin" }
    }

    // ------------------------------------------------------------------------------------------ size and space
    /** A rough size of one media item, for the estimate shown before starting. Real files can differ. */
    fun estimateBytes(type: String, size: Long, w: Int, h: Int, dur: Int): Long = when (type) {
        "image" -> if (w > 0 && h > 0) (w.toLong() * h * 3 / 10).coerceIn(20_000L, 3_000_000L) else 200_000L
        "gif" -> 400_000L
        "sticker" -> 40_000L
        "voice" -> dur.coerceAtLeast(1) * 5_000L + 2_000L          // AAC at 40 kbit/s
        "video", "audio", "file" -> if (size > 0) size else 1_000_000L
        else -> 0L
    }

    /** "about 120 MB" */
    fun sizeLabel(bytes: Long): String {
        val mb = bytes / 1048576.0
        return when {
            bytes <= 0 -> "no media"
            mb < 1 -> "less than 1 MB"
            mb < 10 -> "about " + String.format(Locale.US, "%.1f", mb) + " MB"
            mb < 100 -> "about " + Math.round(mb) + " MB"
            mb < 1000 -> "about " + (Math.round(mb / 10.0) * 10) + " MB"
            else -> "about " + String.format(Locale.US, "%.1f", mb / 1024) + " GB"
        }
    }
    fun exactSize(bytes: Long): String = when { bytes >= 1048576 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0); bytes >= 1024 -> (bytes / 1024).toString() + " KB"; else -> "$bytes B" }

    const val SPACE_MARGIN = 50L * 1048576          // always keep this much free
    /** The room an export needs: the media (twice while it is zipped from a temp copy would be worse, so media goes straight into the zip), the text, and a margin. */
    fun neededBytes(mediaBytes: Long, messages: Long, withMedia: Boolean): Long = (if (withMedia) mediaBytes + mediaBytes / 10 else 0L) + messages * 160 * (if (withMedia) 2 else 1) + SPACE_MARGIN
    fun enoughSpace(free: Long, needed: Long) = free >= needed
    fun noSpace(e: Throwable): Boolean = generateSequence(e) { it.cause }.any { (it.message ?: "").contains("ENOSPC", true) || (it.message ?: "").contains("No space left", true) }

    /** The zip format used here has 32-bit offsets when it is small; stop adding media before the zip gets near 4 GB. */
    const val ZIP_LIMIT = 3_800_000_000L

    // ------------------------------------------------------------------------------------------ who may export
    /** The reason this person may not export, or null. Only groups and channels can be restricted, and only by their owner; the owner may still export. */
    fun blockedReason(kind: Kind, noExport: Boolean, isOwner: Boolean): String? = when {
        !noExport || isOwner || kind == Kind.DM -> null
        kind == Kind.GROUP -> "Export is turned off by the group owner."
        else -> "Export is turned off by the channel owner."
    }
    const val POLICY_NOTE = "This is a switch the app obeys. Someone with a changed app could still copy what they can read here, just like screenshots."

    // ------------------------------------------------------------------------------------------ writing the files
    /** Gives the file of one media item. [release] removes any temp copy. */
    class Blob(val size: Long, val head: ByteArray, val open: () -> InputStream, val release: () -> Unit = {})
    fun interface MediaSource { suspend fun fetch(m: Item): Blob }
    class Skipped(val name: String, val reason: String)
    class Summary(val messages: Int, val mediaFiles: Int, val mediaBytes: Long, val unreadable: Int, val skipped: List<Skipped>, val zipFull: Boolean)

    /**
     * Writes the chat text to [txt] as items arrive, and, when [zip] is set, the media into it (the text goes in last as "_chat.txt").
     * Reading stops at once when the coroutine is cancelled. Call [finish] (or [abort]) exactly once.
     */
    class Writer(private val txt: File, private val zip: File?, private val fmt: Fmt, private val media: MediaSource?, private val zipLimit: Long = ZIP_LIMIT) {
        private val out = BufferedWriter(OutputStreamWriter(FileOutputStream(txt), Charsets.UTF_8), 64 * 1024)
        private val zos: ZipOutputStream? = zip?.let { ZipOutputStream(java.io.BufferedOutputStream(FileOutputStream(it), 64 * 1024)).apply { setLevel(Deflater.NO_COMPRESSION); setComment("WeTalks chat export") } }
        private val namer = Namer(fmt)
        private val skipped = ArrayList<Skipped>()
        private var messages = 0; private var files = 0; private var bytes = 0L; private var bad = 0; private var zipFull = false
        private var zipBytes = 0L

        /** Stage a line that is not a message (a pin, a setting change). */
        fun system(ts: Long, text: String) { out.write(systemText(ts, text, fmt)) }

        suspend fun add(m: Item) {
            coroutineContext.ensureActive()
            if (m.unreadable) bad++
            var placeholder: String? = null
            if (m.hasMedia && !m.unreadable) {
                placeholder = if (zos == null || media == null) mediaPlaceholder(null) else try {
                    if (zipFull) throw IllegalStateException("the zip file is full")
                    val b = media.fetch(m)
                    try {
                        if (zipBytes + b.size > zipLimit) { zipFull = true; throw IllegalStateException("the zip file is full") }
                        val name = namer.assign(m.type, m.ts, m.fileName, extFor(b.head, m.mime, m.fileName, m.type))
                        val e = ZipEntry(name).apply { time = m.ts }
                        zos.putNextEntry(e)
                        try { b.open().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; zos.write(buf, 0, n); bytes += n; zipBytes += n } } }
                        finally { zos.closeEntry() }
                        files++
                        mediaPlaceholder(name)
                    } finally { b.release() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: java.io.IOException) { if (noSpace(e)) throw e; fail(m, e)
                } catch (e: Exception) { fail(m, e) }
            }
            out.write(messageText(m, fmt, placeholder))
            messages++
        }

        private fun fail(m: Item, e: Exception): String {
            val label = m.fileName.ifEmpty { m.type + " from " + fmt.stamp(m.ts) }
            skipped += Skipped(label, (e.message ?: e.javaClass.simpleName).take(120))
            return mediaPlaceholder(label, failed = true)
        }

        fun finish(): Summary {
            out.flush(); out.close()
            if (zos != null) {
                try {
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION)
                    zos.putNextEntry(ZipEntry(TXT_NAME)); FileInputStream(txt).use { it.copyTo(zos) }; zos.closeEntry()
                    if (skipped.isNotEmpty() || zipFull) {
                        zos.putNextEntry(ZipEntry(NOTES_NAME))
                        zos.write(notes().toByteArray(Charsets.UTF_8)); zos.closeEntry()
                    }
                } finally { zos.close() }
            }
            return Summary(messages, files, bytes, bad, skipped, zipFull)
        }

        fun notes(): String = buildString {
            append("These media files could not be added to the export:\n")
            for (s in skipped) append("- ").append(s.name).append(": ").append(s.reason).append('\n')
            if (zipFull) append("The zip file reached its size limit, so later media was left out.\n")
        }

        /** Closes everything after an error or a cancel; the caller deletes the files. */
        fun abort() { try { out.close() } catch (_: Exception) {}; try { zos?.close() } catch (_: Exception) {} }
    }
}
