package com.kafisir.wetalks

import com.kafisir.wetalks.data.ChatExport
import com.kafisir.wetalks.data.Kind
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile

class ChatExportTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val fmt = ChatExport.Fmt(Locale.UK, utc, "dd/MM/yyyy")
    private val t0 = 1741788300000L    // 12 March 2025 14:05 UTC

    @Test fun stampIsStable() {
        assertEquals("[12/03/2025, 14:05]", fmt.stamp(t0))
        assertEquals("[03/12/2025, 14:05]", ChatExport.Fmt(Locale.US, utc, "MM/dd/yyyy").stamp(t0))
        assertEquals("20250312", fmt.day(t0)); assertEquals("2025-03-12", fmt.isoDay(t0))
    }

    @Test fun datePatternFromLocale() {
        assertEquals("dd/MM/yyyy", ChatExport.normalizePattern("d/M/yy"))
        assertEquals("MM/dd/yyyy", ChatExport.normalizePattern("M/d/yy"))
        assertEquals("dd.MM.yyyy", ChatExport.normalizePattern("dd.MM.yy"))
        assertEquals("yyyy/MM/dd", ChatExport.normalizePattern("y/MM/dd"))
        assertEquals("dd/MM/yyyy", ChatExport.normalizePattern("d MMM G"))
        val p = ChatExport.datePattern(Locale.US); assertTrue(p, p.contains("yyyy") && p.contains("MM") && p.contains("dd"))
    }

    @Test fun rangeFilterAndShortcuts() {
        val r = ChatExport.wholeDays(t0, t0, utc)
        assertEquals(1741737600000L, r.from); assertEquals(1741823999999L, r.to)
        assertTrue(r.contains(t0)); assertFalse(r.contains(r.from!! - 1)); assertFalse(r.contains(r.to!! + 1))
        assertTrue(r.contains(r.from!!) && r.contains(r.to!!))
        assertTrue(ChatExport.Range().contains(0) && ChatExport.Range().isAll)
        val last7 = ChatExport.lastDays(7, t0, utc)
        assertEquals(1741737600000L - 6 * ChatExport.DAY, last7.from); assertNull(last7.to)
        assertEquals(ChatExport.startOfDay(t0, utc), ChatExport.lastDays(1, t0, utc).from)
        assertNotNull(ChatExport.rangeProblem(ChatExport.Range(10, 5), t0))
        assertNotNull(ChatExport.rangeProblem(ChatExport.Range(t0 + ChatExport.DAY, null), t0))
        assertNull(ChatExport.rangeProblem(ChatExport.Range(5, 10), t0))
    }

    @Test fun pickerDaysRoundTrip() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val picked = ChatExport.pickerMillis(t0, ist)
        assertEquals(1741737600000L, picked)
        assertEquals(ChatExport.startOfDay(t0, ist), ChatExport.dayFromPicker(picked, ist))
    }

    @Test fun pushIdSeek() {
        val k = ChatExport.pushIdFloor(t0)
        assertEquals(20, k.length); assertEquals(t0, ChatExport.pushIdTime(k))
        assertTrue(ChatExport.pushIdFloor(t0) < ChatExport.pushIdFloor(t0 + 1))
        assertNull(ChatExport.startKeyFor(ChatExport.Range()))
        assertEquals(ChatExport.pushIdFloor(t0 - 3 * ChatExport.DAY), ChatExport.startKeyFor(ChatExport.Range(t0, null)))
    }

    private fun it(text: String, type: String = "text", name: String = "Alice", ts: Long = t0, id: String = "i" + text.hashCode() + type + ts, mediaId: String = "", fileName: String = "",
                   unreadable: Boolean = false, edited: Boolean = false, fwd: String? = null, replyName: String? = null, replyText: String? = null) =
        ChatExport.Item(id, ts, name, type, text, unreadable, edited, fwd, replyName, replyText, fileName = fileName, mediaId = mediaId)

    @Test fun textLines() {
        assertEquals("[12/03/2025, 14:05] Alice: Hello\n", ChatExport.messageText(it("Hello"), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: one\ntwo\n\nfour\n", ChatExport.messageText(it("one\r\ntwo\r\rfour"), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: x\n [01/01/2025, 10:00] Bob: fake\n", ChatExport.messageText(it("x\n[01/01/2025, 10:00] Bob: fake"), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: ab\n", ChatExport.messageText(it("a\u0000b\u0007"), fmt))
        assertEquals("[12/03/2025, 14:05] Unknown: x\n", ChatExport.messageText(it("x", name = ""), fmt))
    }

    @Test fun markersAndMedia() {
        assertEquals("[12/03/2025, 14:05] Alice: [Forwarded from Carol] hi\n", ChatExport.messageText(it("hi", fwd = "Carol"), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: [Forwarded] hi\n", ChatExport.messageText(it("hi", fwd = ""), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: [Reply to Bob: \"see you\"] ok\n", ChatExport.messageText(it("ok", replyName = "Bob", replyText = "see you"), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: <attached: IMG-1.jpg>\ncaption\n", ChatExport.messageText(it("caption", "image"), fmt, ChatExport.mediaPlaceholder("IMG-1.jpg")))
        assertEquals("[12/03/2025, 14:05] Alice: <Media omitted>\n", ChatExport.messageText(it("", "voice"), fmt, ChatExport.mediaPlaceholder(null)))
        assertEquals("<Media not available: a.pdf>", ChatExport.mediaPlaceholder("a.pdf", failed = true))
        assertEquals("[12/03/2025, 14:05] Alice: <sticker: \uD83D\uDE00>\n", ChatExport.messageText(it("\uD83D\uDE00", "sticker"), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: <This message could not be decrypted>\n", ChatExport.messageText(it("", unreadable = true), fmt))
        assertEquals("[12/03/2025, 14:05] Alice: typo <This message was edited>\n", ChatExport.messageText(it("typo", edited = true), fmt))
    }

    @Test fun systemLines() {
        assertEquals("Voice call \u00b7 2:31", ChatExport.callText(false, "ended", 151, true))
        assertEquals("Missed video call", ChatExport.callText(true, "missed", 0, false))
        assertEquals("Voice call \u00b7 no answer", ChatExport.callText(false, "missed", 0, true))
        assertEquals("Video call \u00b7 declined", ChatExport.callText(true, "declined", 0, false))
        val call = ChatExport.Item("c", t0, "Alice", "call", "", callVideo = false, callState = "ended", dur = 151)
        assertEquals("[12/03/2025, 14:05] Voice call \u00b7 2:31\n", ChatExport.messageText(call, fmt))
        assertEquals("[12/03/2025, 14:05] A message was pinned\n", ChatExport.systemText(t0, ChatExport.pinNotice(), fmt))
        assertEquals("Disappearing messages were turned on: 7 days", ChatExport.disappearingNotice(7 * 86400L))
        assertEquals("Disappearing messages were turned on: 1 hour", ChatExport.disappearingNotice(3600))
        assertEquals("Disappearing messages were turned off", ChatExport.disappearingNotice(0))
        assertEquals("Photo: hi", ChatExport.previewOf("image", "hi")); assertEquals("Voice message", ChatExport.previewOf("voice", ""))
    }

    @Test fun fileNames() {
        assertEquals("a_b.txt", ChatExport.safeName("a:b.txt"))
        assertEquals("_CON.txt", ChatExport.safeName("CON.txt"))
        assertEquals("file", ChatExport.safeName("...")); assertEquals("file", ChatExport.safeName(null)); assertEquals("x", ChatExport.safeName("", "x"))
        assertEquals("evil.sh", ChatExport.zipEntryName("../../evil.sh")); assertEquals("passwd", ChatExport.zipEntryName("/etc/passwd"))
        assertEquals("win.exe", ChatExport.zipEntryName("C:\\x\\win.exe")); assertFalse(ChatExport.zipEntryName("..").contains(".."))
        assertTrue(ChatExport.safeName("x".repeat(300) + ".pdf").let { it.length <= 100 && it.endsWith(".pdf") })
        assertEquals("WeTalks chat with Alice 2025-03-12.zip", ChatExport.exportFileName("Alice", t0, true, fmt))
        assertFalse(ChatExport.exportFileName("a/b\\c:d", t0, false, fmt).let { it.contains("/") || it.contains("\\") || it.contains(":") })
        assertTrue(ChatExport.exportFileName("Team?", t0, false, fmt, group = true).endsWith(".txt"))
    }

    @Test fun namerIsUniqueAndNumbered() {
        val n = ChatExport.Namer(fmt)
        assertEquals("IMG-20250312-0001.jpg", n.assign("image", t0, "", "jpg"))
        assertEquals("IMG-20250312-0002.jpg", n.assign("image", t0 + 5, "", "JPG"))
        assertEquals("VOICE-20250312-0001.m4a", n.assign("voice", t0, "", "m4a"))
        assertEquals("IMG-20250313-0001.png", n.assign("image", t0 + ChatExport.DAY, "", "png"))
        assertEquals("report.pdf", n.assign("file", t0, "report.pdf", "pdf"))
        assertEquals("report (2).pdf", n.assign("file", t0, "report.pdf", "pdf"))
        assertEquals("Report (3).pdf", n.assign("file", t0, "Report.pdf", "pdf"))
        assertEquals("IMG-20250312-0003.bin", n.assign("image", t0, "", ""))
    }

    @Test fun extensions() {
        assertEquals("jpg", ChatExport.extFor(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "", ""))
        assertEquals("png", ChatExport.extFor(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), "", ""))
        assertEquals("gif", ChatExport.extFor("GIF89a".toByteArray(), "", ""))
        assertEquals("webp", ChatExport.extFor("RIFF....WEBP".toByteArray(), "", ""))
        assertEquals("m4a", ChatExport.extFor("....ftypisom".toByteArray(), "", "", "voice"))
        assertEquals("m4a", ChatExport.extFor(ByteArray(0), "", "", "voice"))
        assertEquals("pdf", ChatExport.extFor(ByteArray(0), "", "Doc.PDF"))
        assertEquals("png", ChatExport.extFor(ByteArray(0), "image/png", ""))
        assertEquals("bin", ChatExport.extFor(ByteArray(0), "", ""))
    }

    @Test fun sizesAndSpace() {
        assertEquals("no media", ChatExport.sizeLabel(0)); assertEquals("less than 1 MB", ChatExport.sizeLabel(500_000))
        assertEquals("about 3.5 MB", ChatExport.sizeLabel(3_670_016)); assertEquals("about 42 MB", ChatExport.sizeLabel(44_040_192))
        assertEquals("about 120 MB", ChatExport.sizeLabel(124_829_120)); assertEquals("about 1.5 GB", ChatExport.sizeLabel(1_610_612_736))
        assertEquals(1_000_000L, ChatExport.estimateBytes("file", 0, 0, 0, 0)); assertEquals(5_000_000L, ChatExport.estimateBytes("video", 5_000_000, 0, 0, 0))
        assertEquals(3_000_000L, ChatExport.estimateBytes("image", 0, 4000, 4000, 0)); assertEquals(200_000L, ChatExport.estimateBytes("image", 0, 0, 0, 0))
        assertEquals(62_000L, ChatExport.estimateBytes("voice", 0, 0, 0, 12)); assertEquals(0L, ChatExport.estimateBytes("text", 0, 0, 0, 0))
        val need = ChatExport.neededBytes(100_000_000, 1000, true)
        assertTrue(need > 110_000_000 + ChatExport.SPACE_MARGIN)
        assertTrue(ChatExport.neededBytes(100_000_000, 1000, false) < need)
        assertFalse(ChatExport.enoughSpace(need - 1, need)); assertTrue(ChatExport.enoughSpace(need, need))
        assertTrue(ChatExport.noSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(ChatExport.noSpace(RuntimeException("x", java.io.IOException("No space left on device"))))
        assertFalse(ChatExport.noSpace(java.io.IOException("broken pipe")))
    }

    @Test fun ownerPolicy() {
        assertNull(ChatExport.blockedReason(Kind.GROUP, false, false))
        assertEquals("Export is turned off by the group owner.", ChatExport.blockedReason(Kind.GROUP, true, false))
        assertEquals("Export is turned off by the channel owner.", ChatExport.blockedReason(Kind.CHANNEL, true, false))
        assertNull(ChatExport.blockedReason(Kind.GROUP, true, true))
        assertNull(ChatExport.blockedReason(Kind.DM, true, false))
    }

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("wtexport").toFile()
    private fun blob(b: ByteArray) = ChatExport.Blob(b.size.toLong(), b.copyOf(minOf(64, b.size)), { ByteArrayInputStream(b) })

    @Test fun writesZipWithMediaAndSkipsBrokenFiles() = runBlocking {
        val d = tmp(); val txt = File(d, "chat.tmp"); val zip = File(d, "out.zip")
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val src = ChatExport.MediaSource { m -> when (m.mediaId) { "ok" -> blob(jpg); "pdf" -> blob("%PDF-1".toByteArray()); "gone" -> throw IllegalStateException("deleted"); else -> throw java.io.FileNotFoundException("x") } }
        val w = ChatExport.Writer(txt, zip, fmt, src)
        w.add(it("hello\nworld"))
        w.add(it("look", "image", mediaId = "ok"))
        w.system(t0 + 60_000, ChatExport.pinNotice())
        w.add(it("", "file", mediaId = "pdf", fileName = "../my:doc.pdf"))
        w.add(it("", "image", mediaId = "gone", ts = t0 + 120_000))
        w.add(it("", "file", mediaId = "gone", fileName = "lost.zip"))
        w.add(ChatExport.Item("x", t0, "Bob", "call", "", callState = "missed"))
        val sum = w.finish()
        assertEquals(6, sum.messages); assertEquals(2, sum.mediaFiles); assertEquals(2, sum.skipped.size)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue(names.toString(), "IMG-20250312-0001.jpg" in names && "my_doc.pdf" in names && ChatExport.TXT_NAME in names && ChatExport.NOTES_NAME in names)
            assertFalse(names.any { it.contains("/") || it.contains("..") })
            assertArrayEquals(jpg, z.getInputStream(z.getEntry("IMG-20250312-0001.jpg")).readBytes())
            val text = z.getInputStream(z.getEntry(ChatExport.TXT_NAME)).readBytes().toString(Charsets.UTF_8)
            assertTrue(text, text.startsWith("[12/03/2025, 14:05] Alice: hello\nworld\n"))
            assertTrue(text.contains("Alice: <attached: IMG-20250312-0001.jpg>\nlook\n"))
            assertTrue(text.contains("[12/03/2025, 14:06] A message was pinned\n"))
            assertTrue(text.contains("<attached: my_doc.pdf>"))
            assertTrue(text.contains("<Media not available: lost.zip>"))
            assertTrue(text.contains("[12/03/2025, 14:05] Missed voice call"))
            val notes = z.getInputStream(z.getEntry(ChatExport.NOTES_NAME)).readBytes().toString(Charsets.UTF_8)
            assertTrue(notes, notes.contains("lost.zip") && notes.contains("deleted"))
            Regex("<attached: (.+?)>").findAll(text).forEach { assertTrue(it.groupValues[1], it.groupValues[1] in names) }
        }
        d.deleteRecursively(); Unit
    }

    @Test fun textOnlyExportOmitsMedia() = runBlocking {
        val d = tmp(); val txt = File(d, "t.txt")
        val w = ChatExport.Writer(txt, null, fmt, null)
        w.add(it("a")); w.add(it("pic", "image", mediaId = "m")); w.add(it("", "voice", mediaId = "v"))
        val sum = w.finish()
        assertEquals(3, sum.messages); assertEquals(0, sum.mediaFiles)
        assertEquals("[12/03/2025, 14:05] Alice: a\n[12/03/2025, 14:05] Alice: <Media omitted>\npic\n[12/03/2025, 14:05] Alice: <Media omitted>\n", txt.readText())
        d.deleteRecursively(); Unit
    }

    @Test fun zipStopsWhenFull() = runBlocking {
        val d = tmp()
        val w = ChatExport.Writer(File(d, "c.tmp"), File(d, "o.zip"), fmt, { blob(ByteArray(10)) }, zipLimit = 15)
        w.add(it("a", "image", mediaId = "1")); w.add(it("b", "image", mediaId = "2", ts = t0 + 1)); w.add(it("c", "image", mediaId = "3", ts = t0 + 2))
        val s = w.finish()
        assertEquals(1, s.mediaFiles); assertTrue(s.zipFull); assertEquals(2, s.skipped.size)
        d.deleteRecursively(); Unit
    }

    @Test fun cancelStopsTheWriter() {
        val d = tmp()
        val w = ChatExport.Writer(File(d, "c.txt"), null, fmt, null)
        runBlocking { val j = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { w.add(it("x")) }; j.cancel(); j.join(); assertTrue(j.isCancelled) }
        w.abort(); d.deleteRecursively()
    }
}
