package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupFormatTest {
    private val key = BackupKey.derive("correct horse battery")     // one 600 000-round derivation, reused
    private fun msg(id: String, ts: Long, text: String = "hello $id") = Msg(id, "u1", ts, "text", text)

    private fun write(key: BackupKey?, big: Boolean = false): Pair<ByteArray, BackupManifest> {
        val out = ByteArrayOutputStream()
        val w = BackupWriter(out, key)
        val lines = (1..50).joinToString("\n") { BackupMsgCodec.encode(msg("m$it", 1000L + it)) }
        w.addBytes(BackupFormat.chatEntry("chatA"), lines.toByteArray())
        val media = if (big) ByteArray(300_000) { (it * 31).toByte() } else ByteArray(2000) { it.toByte() }
        w.addStream(BackupFormat.mediaEntry("pic1"), ByteArrayInputStream(media), compress = false)
        w.addBytes("settings.json", "{\"v\":1}".toByteArray())
        val m = w.finish { e -> BackupManifest("id1", 5L, "1.0", "acct", "id1", 0, null, true, true,
            listOf(ChatPart("chatA", "dm", BackupFormat.chatEntry("chatA"), 50, 1001, 1050, "m50", 1050)), listOf(MediaPart("pic1", BackupFormat.mediaEntry("pic1"), media.size.toLong())), e) }
        return out.toByteArray() to m
    }

    private fun read(b: ByteArray, k: BackupKey?): Pair<BackupManifest, Map<String, ByteArray>> {
        val got = LinkedHashMap<String, ByteArray>()
        val m = BackupReader.read(ByteArrayInputStream(b), { k }) { n, s -> got[n] = s.readBytes() }
        return m to got
    }

    @Test fun plainRoundTrip() {
        val (b, m) = write(null)
        val (m2, got) = read(b, null)
        assertEquals(m.toJson().toString(), m2.toJson().toString())
        assertEquals(50, String(got[BackupFormat.chatEntry("chatA")]!!).lines().size)
        assertEquals(2000, got[BackupFormat.mediaEntry("pic1")]!!.size)
        assertEquals("{\"v\":1}", String(got["settings.json"]!!))
    }

    @Test fun encryptedRoundTripAndHeaderRecordsKdf() {
        val (b, _) = write(key, big = true)                 // 300 kB: several 64 KiB chunks
        val h = BackupReader.peek(ByteArrayInputStream(b))
        assertTrue(h.encrypted); assertTrue(h.iter >= 600_000); assertEquals(16, h.salt.size)
        assertFalse("plaintext must not appear", String(b, Charsets.ISO_8859_1).contains("hello m1"))
        val (m, got) = read(b, key)
        assertEquals(300_000, got[BackupFormat.mediaEntry("pic1")]!!.size)
        assertEquals("id1", m.id)
    }

    @Test fun wrongPassphraseIsDetectedCleanly() {
        val (b, _) = write(key)
        val h = BackupReader.peek(ByteArrayInputStream(b))
        val wrong = BackupFormat.keyFor(h, "not the passphrase")
        try { read(b, wrong); fail() } catch (e: BackupException) { assertEquals(BackupException.Kind.WRONG_PASSPHRASE, e.kind) }
    }

    @Test fun encryptedBackupNeedsAKey() {
        val (b, _) = write(key)
        try { read(b, null); fail() } catch (e: BackupException) { assertEquals(BackupException.Kind.NEEDS_PASSPHRASE, e.kind) }
    }

    @Test fun aFlippedByteIsCaught() {
        for (k in listOf<BackupKey?>(null, key)) {
            val (b, _) = write(k, big = true)
            // plain zip: the trailing central directory is never used by the reader (every entry is checked by its own CRC and the manifest hashes), so only the data region is flipped
            for (pos in if (k == null) listOf(b.size / 2, b.size / 3) else listOf(b.size / 2, b.size / 3, b.size - 40)) {
                val bad = b.clone(); bad[pos] = (bad[pos].toInt() xor 0x01).toByte()
                try { read(bad, k); fail("flip at $pos undetected (encrypted=${k != null})") } catch (e: BackupException) { assertEquals(BackupException.Kind.CORRUPT, e.kind) }
            }
        }
    }

    @Test fun truncatedFileIsCaught() {
        for (k in listOf<BackupKey?>(null, key)) {
            val (b, _) = write(k, big = true)
            for (cut in if (k == null) listOf(b.size / 2, b.size / 3) else listOf(b.size - 1, b.size - 100, b.size / 2)) {
                try { read(b.copyOf(cut), k); fail("cut at $cut accepted") } catch (e: BackupException) { /* any clean BackupException is right */ }
            }
        }
    }

    @Test fun notABackup() {
        try { read("hello world, not a backup".toByteArray(), null); fail() } catch (e: BackupException) { assertEquals(BackupException.Kind.CORRUPT, e.kind) }
    }

    @Test fun lowIterationCountIsRefused() {
        try { BackupKey.derive("x".repeat(10), iter = 1000); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun messageLinesRoundTrip() {
        val m = Msg("k1", "u2", 1234, "image", "caption", "media9", 3, "r1", false, true, mapOf("u1" to "👍"), false, false, listOf(1, 2, 3), LinkPreview("http://a", "t", "d", "i"),
            "image/jpeg", 10, 20, "u3", "n.jpg", 99, "key", "thumb", "alb")
        val back = BackupMsgCodec.decode(BackupMsgCodec.encode(m))!!
        assertEquals(m, back)
        assertFalse(BackupMsgCodec.keep(m.copy(deleted = true))); assertFalse(BackupMsgCodec.keep(m.copy(unreadable = true))); assertFalse(BackupMsgCodec.keep(m.copy(pending = true)))
        assertNull(BackupMsgCodec.decode("not json"))
    }

    @Test fun entryNamesCannotEscape() {
        assertFalse(BackupFormat.validEntryName("../../etc/passwd")); assertFalse(BackupFormat.validEntryName("chats/../x"))
        assertTrue(BackupFormat.validEntryName(BackupFormat.chatEntry("a/b")))
    }
}
