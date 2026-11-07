package com.kafisir.wetalks.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class PcmEditTest {
    private val sr = 24000
    private fun tone(ms: Int) = FloatArray(sr * ms / 1000) { 0.5f * sin(it * 0.1f) }

    @Test fun silenceAroundSpeechIsCut() {
        val x = FloatArray(sr / 2) + tone(400) + FloatArray(sr / 2)           // 0.5 s silence, 0.4 s tone, 0.5 s silence
        val y = PcmEdit.trim(x, sr)
        assertTrue("trimmed to about the tone plus margins, was ${y.size}", y.size in (sr * 400 / 1000)..(sr * 480 / 1000))
        assertTrue(abs(y.first()) < 0.01f && abs(y.last()) < 0.01f)           // faded: no click at the joins
    }

    @Test fun pureSilenceBecomesEmpty() = assertEquals(0, PcmEdit.trim(FloatArray(sr), sr).size)

    @Test fun speechIsKeptIntact() {
        val t = tone(300); val y = PcmEdit.trim(t, sr)
        assertEquals(t.size, y.size)
        assertEquals(t[t.size / 2], y[y.size / 2], 1e-6f)
    }

    @Test fun concatKeepsOrder() = assertArrayEquals(floatArrayOf(1f, 2f, 3f), PcmEdit.concat(listOf(floatArrayOf(1f), floatArrayOf(2f, 3f))), 0f)
}
