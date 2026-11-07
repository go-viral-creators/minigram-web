package com.kafisir.wetalks

import com.kafisir.wetalks.data.SdpTools
import org.junit.Assert.*
import org.junit.Test

class SdpToolsTest {
    private val sdp = listOf(
        "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126",
        "a=rtpmap:111 opus/48000/2",
        "a=rtcp-fb:111 transport-cc",
        "a=fmtp:111 minptime=10;useinbandfec=1",
        "a=rtpmap:63 red/48000/2",
        "a=fmtp:63 111/111",
        "a=rtpmap:9 G722/8000",
        "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "a=rtpmap:96 VP8/90000",
        "a=fmtp:96 x-google-start-bitrate=300",
        "",
    ).joinToString("\r\n")

    private fun fmtp(s: String, pt: String) = s.split("\r\n", "\n").first { it.startsWith("a=fmtp:$pt ") }.substringAfter(" ").split(";").associate { it.substringBefore("=") to it.substringAfter("=", "") }

    @Test fun mergesIntoTheExistingOpusFmtpLine() {
        val out = SdpTools.tuneOpus(sdp)
        val p = fmtp(out, "111")
        assertEquals("1", p["useinbandfec"]); assertEquals("1", p["usedtx"]); assertEquals("10", p["minptime"])
        assertEquals("0", p["stereo"]); assertEquals("0", p["sprop-stereo"]); assertEquals("32000", p["maxaveragebitrate"])
        // other codecs and the RED line are untouched; line endings and the trailing newline are kept
        assertTrue(out.contains("a=fmtp:63 111/111\r\n")); assertTrue(out.contains("a=fmtp:96 x-google-start-bitrate=300\r\n"))
        assertTrue(out.endsWith("\r\n")); assertFalse(out.contains("\n") && out.replace("\r\n", "").contains("\n"))
        assertEquals(sdp.split("\r\n").size, out.split("\r\n").size)         // no line added when an fmtp line already existed
    }

    @Test fun addsAnFmtpLineWhenThereIsNone() {
        val s = "m=audio 9 RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\na=sendrecv\r\n"
        val out = SdpTools.tuneOpus(s)
        val lines = out.split("\r\n")
        assertEquals("a=rtpmap:111 opus/48000/2", lines[1]); assertTrue(lines[2].startsWith("a=fmtp:111 ")); assertEquals("a=sendrecv", lines[3])
        assertEquals("1", fmtp(out, "111")["useinbandfec"])
    }

    @Test fun twoOpusPayloadTypesAndSeveralFmtpLines() {
        val s = listOf("m=audio 9 RTP/SAVPF 111 109", "a=rtpmap:111 opus/48000/2", "a=fmtp:111 useinbandfec=0;stereo=1", "a=rtpmap:109 opus/48000/2", "a=rtpmap:0 PCMU/8000", "a=fmtp:0 foo=bar").joinToString("\n")
        val out = SdpTools.tuneOpus(s)
        assertEquals("1", fmtp(out, "111")["useinbandfec"]); assertEquals("0", fmtp(out, "111")["stereo"])      // existing values are replaced
        assertEquals("1", fmtp(out, "109")["usedtx"])                                                         // the one without a line gets one
        assertTrue(out.contains("a=fmtp:0 foo=bar"))
        assertFalse(out.contains("\r"))                                                                       // plain \n kept
    }

    @Test fun noOpusLineMeansNoChange() {
        val s = "v=0\r\nm=audio 9 RTP/SAVPF 0 8\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=fmtp:8 foo=1\r\n"
        assertEquals(s, SdpTools.tuneOpus(s)); assertFalse(SdpTools.hasOpus(s))
        assertEquals("", SdpTools.tuneOpus(""))
    }

    @Test fun idempotent() {
        val once = SdpTools.tuneOpus(sdp)
        assertEquals(once, SdpTools.tuneOpus(once))
        val bare = "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\n"
        assertEquals(SdpTools.tuneOpus(bare), SdpTools.tuneOpus(SdpTools.tuneOpus(bare)))
    }

    @Test fun aLowerBitrateRequestedByThePeerIsKept() {
        val s = "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 maxaveragebitrate=16000\n"
        assertEquals("16000", fmtp(SdpTools.tuneOpus(s), "111")["maxaveragebitrate"])
        val hi = "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 maxaveragebitrate=510000\n"
        assertEquals("32000", fmtp(SdpTools.tuneOpus(hi), "111")["maxaveragebitrate"])
    }

    @Test fun otherParametersAndTheirOrderSurvive() {
        val s = "m=audio 9 RTP/SAVPF 111\na=rtpmap:111 opus/48000/2\na=fmtp:111 maxplaybackrate=16000; cbr=1\n"
        val line = SdpTools.tuneOpus(s).lines().first { it.startsWith("a=fmtp") }
        assertTrue(line.startsWith("a=fmtp:111 maxplaybackrate=16000;cbr=1;")); assertTrue(line.contains("usedtx=1"))
    }
}
