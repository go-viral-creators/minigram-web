package com.kafisir.wetalks

import com.kafisir.wetalks.voice.NeuralTts
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NeuralTtsTest {
    private fun zip(vararg e: Pair<String, ByteArray>): File {
        val f = File.createTempFile("ntts", ".zip").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z -> e.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }
    private fun dir(): File = java.nio.file.Files.createTempDirectory("nttsd").toFile().apply { deleteOnExit() }

    @Test fun extractsNormalPack() {
        val d = dir(); NeuralTts.extractZip(zip("model.int8.onnx" to ByteArray(1000) { 1 }, "espeak-ng-data/en_dict" to ByteArray(50) { 2 }), d, 10_000, 100)
        assertEquals(1000, File(d, "model.int8.onnx").length()); assertEquals(50, File(d, "espeak-ng-data/en_dict").length())
    }

    @Test fun refusesPathEscape() {
        assertThrows(IllegalStateException::class.java) { NeuralTts.extractZip(zip("../evil.txt" to ByteArray(5)), dir(), 10_000, 100) }
        assertThrows(IllegalStateException::class.java) { NeuralTts.extractZip(zip("a/../../evil.txt" to ByteArray(5)), dir(), 10_000, 100) }
        // an absolute name is treated as relative: it lands inside the target folder, never at the real path
        val d = dir(); NeuralTts.extractZip(zip("/etc/evil.txt" to ByteArray(5)), d, 10_000, 100)
        assertTrue(File(d, "etc/evil.txt").exists()); assertFalse(File("/etc/evil.txt").length() == 5L)
    }

    @Test fun refusesZipBombsAndTooManyFiles() {
        assertThrows(IllegalStateException::class.java) { NeuralTts.extractZip(zip("big.bin" to ByteArray(50_000)), dir(), 10_000, 100) }
        assertThrows(IllegalStateException::class.java) { NeuralTts.extractZip(zip(*Array(20) { "f$it" to ByteArray(1) }), dir(), 10_000, 10) }
    }

    @Test fun sha256IsCorrect() {
        val f = File.createTempFile("sha", ".bin").apply { writeBytes("abc".toByteArray()); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", NeuralTts.sha256(f))
    }

    private fun man(url: String = "/models/tts/supertonic3-int8-v1.zip", sha: String = "a".repeat(64), bytes: Long = 125_000_000, un: Long = 166_000_000, id: String = "supertonic3-int8-v1") =
        JSONObject("""{"packs":[{"id":"$id","url":"$url","sha256":"$sha","bytes":$bytes,"unpacked_bytes":$un}]}""")

    @Test fun manifestIsValidated() {
        assertEquals("https://wetalks.fireclashpro.com/models/tts/supertonic3-int8-v1.zip", NeuralTts.validatePack(man()).url)
        for (bad in listOf(man(url = "https://evil.example/x.zip"), man(url = "/models/tts/../../x.zip"), man(url = "/other/x.zip"), man(url = "/models/tts/x.exe"), man(sha = "zz"), man(bytes = 5), man(bytes = 900_000_000, un = 900_000_001), man(id = "other")))
            assertThrows(Exception::class.java) { NeuralTts.validatePack(bad) }
    }

    @Test fun textIsPreparedAndSplit() {
        assertEquals("Hello link now", NeuralTts.prepare("Hello 😀 https://example.com/x now"))
        val long = "Hello there. " + "word ".repeat(120) + "! Next sentence?"
        val c = NeuralTts.chunks(long); assertTrue(c.all { it.length <= 221 }); assertTrue(c.size >= 3)
        assertEquals(listOf("नमस्ते। आप कैसे हैं?"), NeuralTts.chunks("नमस्ते। आप कैसे हैं?"))
    }
}
