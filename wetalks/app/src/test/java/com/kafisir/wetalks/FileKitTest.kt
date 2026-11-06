package com.kafisir.wetalks

import com.kafisir.wetalks.data.FileKit
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

class FileKitTest {
    private fun tmp(): File = File.createTempFile("fkit", ".bin").apply { deleteOnExit() }
    private fun pdf(n: Int) = "%PDF-1.7\n".toByteArray() + ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun picked(size: Long) = FileKit.Picked("a.pdf", "pdf", "file", "application/pdf", size)

    @Test fun roundTripKeepsEveryByte() {
        val plain = pdf(3 * 1024 * 1024 + 17); val key = FileKit.newKey(); val enc = tmp(); val dec = tmp()
        val n = FileKit.encryptStream(ByteArrayInputStream(plain), picked(plain.size.toLong()), key, enc)
        assertEquals(plain.size.toLong(), n)
        assertTrue(enc.length() > plain.size)
        FileKit.decrypt(enc, key, dec, n, "pdf")
        assertArrayEquals(plain, dec.readBytes())
    }

    @Test fun tamperedOrWrongKeyIsRejected() {
        val plain = pdf(200_000); val key = FileKit.newKey(); val enc = tmp()
        val n = FileKit.encryptStream(ByteArrayInputStream(plain), picked(plain.size.toLong()), key, enc)
        val bad = enc.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val badF = tmp().apply { writeBytes(bad) }
        assertThrows(Exception::class.java) { FileKit.decrypt(badF, key, tmp(), n, "pdf") }
        assertThrows(Exception::class.java) { FileKit.decrypt(enc, FileKit.newKey(), tmp(), n, "pdf") }
        val truncated = tmp().apply { writeBytes(enc.readBytes().copyOf(enc.length().toInt() - 100)) }
        assertThrows(Exception::class.java) { FileKit.decrypt(truncated, key, tmp(), n, "pdf") }
    }

    @Test fun wrongClaimedSizeIsRejected() {
        val plain = pdf(5000); val key = FileKit.newKey(); val enc = tmp()
        FileKit.encryptStream(ByteArrayInputStream(plain), picked(plain.size.toLong()), key, enc)
        assertThrows(Exception::class.java) { FileKit.decrypt(enc, key, tmp(), 999, "pdf") }
    }

    @Test fun overThirtyMegabytesIsRefused() {
        val big = object : java.io.InputStream() { var left = 31L * 1024 * 1024; override fun read(): Int = if (left-- > 0) 0x41 else -1
            override fun read(b: ByteArray, o: Int, l: Int): Int { if (left <= 0) return -1; val n = minOf(l.toLong(), left).toInt(); java.util.Arrays.fill(b, o, o + n, 0x41); left -= n; return n } }
        assertThrows(IllegalArgumentException::class.java) { FileKit.encryptStream(big, FileKit.Picked("a.txt", "txt", "file", "text/plain", 0), FileKit.newKey(), tmp()) }
    }

    @Test fun programsAreNeverAccepted() {
        val exe = "MZ\u0090\u0000".toByteArray() + ByteArray(60)
        assertThrows(IllegalArgumentException::class.java) { FileKit.encryptStream(ByteArrayInputStream(exe), picked(64), FileKit.newKey(), tmp()) }
        val elf = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(60)
        assertFalse(FileKit.looksRight("txt", elf, elf.size)); assertFalse(FileKit.looksRight("pdf", elf, elf.size))
        val script = "#!/bin/sh\nrm -rf".toByteArray(); assertFalse(FileKit.looksRight("txt", script, script.size))
    }

    @Test fun contentMustMatchExtension() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(60)
        assertFalse(FileKit.looksRight("pdf", png, png.size)); assertFalse(FileKit.looksRight("mp4", png, png.size)); assertFalse(FileKit.looksRight("zip", png, png.size))
        val mp4 = ByteArray(4) + "ftypisom".toByteArray() + ByteArray(40)
        assertTrue(FileKit.looksRight("mp4", mp4, mp4.size))
        assertTrue(FileKit.looksRight("pdf", "%PDF-1.4".toByteArray(), 8))
        assertTrue(FileKit.looksRight("mp3", "ID3\u0004".toByteArray(), 4))
        assertTrue(FileKit.looksRight("zip", "PK\u0003\u0004".toByteArray(), 4))
        assertFalse(FileKit.looksRight("txt", byteArrayOf(65, 0, 66), 3))
    }

    @Test fun onlyKnownTypesHaveAKind() {
        for (bad in listOf("apk", "exe", "bat", "sh", "js", "jar", "html", "svg", "dll", "so", "msi", "scr", "vbs", "ps1", "lnk", "")) assertNull(".$bad", FileKit.kindOf(bad))
        assertEquals("video", FileKit.kindOf("mp4")); assertEquals("audio", FileKit.kindOf("mp3")); assertEquals("file", FileKit.kindOf("pdf"))
    }

    @Test fun namesAreCleaned() {
        assertEquals("evil.pdf", FileKit.cleanName("../../etc/evil.pdf"))
        assertEquals("a.pdf", FileKit.cleanName("C:\\Users\\x\\a.pdf"))
        assertEquals("fdp.exe", FileKit.cleanName("\u202Eexe.pdf".reversed().let { "fdp\u202E.exe" }).replace("\u202E", ""))
        assertEquals("file", FileKit.cleanName("..."))
        assertEquals("file", FileKit.cleanName(null))
        val long = FileKit.cleanName("a".repeat(300) + ".pdf"); assertTrue(long.length <= 80); assertTrue(long.endsWith(".pdf"))
        assertFalse(FileKit.cleanName("a<b>c|d?.txt").any { it in "<>|?" })
    }
}
