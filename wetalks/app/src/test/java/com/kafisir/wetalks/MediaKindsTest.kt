package com.kafisir.wetalks

import com.kafisir.wetalks.data.MediaKinds
import com.kafisir.wetalks.data.MediaLedger
import org.junit.Assert.*
import org.junit.Test

class MediaKindsTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(off: Int, s: String, size: Int = 16) = ByteArray(size).also { s.forEachIndexed { i, c -> it[off + i] = c.code.toByte() } }

    @Test fun sniffsCachedPictures() {
        assertEquals(MediaKinds.PHOTO, MediaKinds.sniff(b(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(MediaKinds.PHOTO, MediaKinds.sniff(b(0x89, 'P'.code, 'N'.code, 'G'.code)))
        assertEquals(MediaKinds.GIF, MediaKinds.sniff(ascii(0, "GIF89a")))
        val webp = ascii(0, "RIFF").also { "WEBP".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() } }
        assertEquals(MediaKinds.PHOTO, MediaKinds.sniff(webp))
    }

    @Test fun sniffsVoiceAndUnknown() {
        assertEquals(MediaKinds.VOICE, MediaKinds.sniff(ascii(4, "ftypmp42")))
        assertEquals(MediaKinds.VOICE, MediaKinds.sniff(ascii(0, "OggS")))
        assertEquals(MediaKinds.PHOTO, MediaKinds.sniff(ascii(4, "ftypheic")))
        assertEquals(MediaKinds.OTHER, MediaKinds.sniff(ByteArray(0)))
        assertEquals(MediaKinds.OTHER, MediaKinds.sniff(b(1, 2, 3)))
        assertEquals(MediaKinds.OTHER, MediaKinds.sniff(ascii(0, "hello world", 11)))
    }

    @Test fun shortInputNeverCrashes() { for (n in 0..5) MediaKinds.sniff(ByteArray(n) { 0x47 }) }

    @Test fun typesFromExtensionsAndMessages() {
        assertEquals(MediaKinds.VIDEO, MediaKinds.ofExtension("MP4")); assertEquals(MediaKinds.AUDIO, MediaKinds.ofExtension("mp3"))
        assertEquals(MediaKinds.DOC, MediaKinds.ofExtension("pdf")); assertEquals(MediaKinds.OTHER, MediaKinds.ofExtension("exe"))
        assertEquals(MediaKinds.PHOTO, MediaKinds.ofMessageType("image")); assertEquals(MediaKinds.DOC, MediaKinds.ofMessageType("file")); assertNull(MediaKinds.ofMessageType("text"))
    }

    @Test fun byteLabels() {
        assertEquals("0 B", MediaKinds.bytes(0)); assertEquals("1023 B", MediaKinds.bytes(1023)); assertEquals("1 KB", MediaKinds.bytes(1024))
        assertEquals("2.5 MB", MediaKinds.bytes((2.5 * 1048576).toLong())); assertEquals("1.0 GB", MediaKinds.bytes(1L shl 30))
    }

    @Test fun barSharesAddUpAndSkipEmpty() {
        val s = MediaKinds.shares(listOf("a" to 300L, "b" to 0L, "c" to 100L))
        assertEquals(listOf("a", "c"), s.map { it.first })
        assertEquals(1f, s.sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
        assertEquals(.75f, s[0].second, 0.0001f)
        assertTrue(MediaKinds.shares(emptyList()).isEmpty()); assertTrue(MediaKinds.shares(listOf("a" to 0L)).isEmpty())
        assertTrue(MediaKinds.shares(listOf("a" to -5L, "b" to 10L)).let { it.size == 1 && it[0].first == "b" })
    }

    @Test fun howManyToDelete() {
        assertEquals(1, MediaKinds.countToFree(listOf(50L, 20L, 10L), 40)); assertEquals(2, MediaKinds.countToFree(listOf(50L, 20L, 10L), 60))
        assertEquals(3, MediaKinds.countToFree(listOf(50L, 20L, 10L), 1000)); assertEquals(0, MediaKinds.countToFree(emptyList(), 5))
    }

    @Test fun uploadSizeIsStampedOnlyIntoMediaPayloads() {
        MediaLedger.rememberUpload("abc", 2_400_000)
        val j = org.json.JSONObject(MediaLedger.stampSize("image", """{"m":"abc","c":"hi"}"""))
        assertEquals(2_400_000L, j.getLong("s")); assertEquals("hi", j.getString("c"))
        assertEquals("""{"m":"abc"}""", MediaLedger.stampSize("text", """{"m":"abc"}"""))
        assertEquals("""{"m":"zzz"}""", MediaLedger.stampSize("voice", """{"m":"zzz"}"""))           // size unknown: unchanged
        assertEquals("""{"m":"abc","s":5}""", MediaLedger.stampSize("gif", """{"m":"abc","s":5}"""))    // already has one: unchanged
        assertEquals("not json", MediaLedger.stampSize("image", "not json"))
    }
}
