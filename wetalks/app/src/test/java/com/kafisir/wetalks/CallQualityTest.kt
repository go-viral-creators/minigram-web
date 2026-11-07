package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The quality logic fed with SIMULATED stat sequences (one sample = one 2-second stats round). These tests show what the app
 * would decide; they say nothing about how real networks behave. The decisions are printed so they can be read in the test report.
 */
class CallQualityTest {
    private fun s(rtt: Double? = 40.0, loss: Double? = 0.0, jit: Double? = 5.0, avail: Double? = 2000.0) = NetSample(rtt, loss, jit, avail)
    private fun rep(n: Int, x: NetSample) = List(n) { x }

    private class Trace(val rows: List<QualityUpdate>) {
        val pausedAt get() = rows.indexOfFirst { it.video == VideoAction.PAUSE }
        val resumedAt get() = rows.indexOfFirst { it.video == VideoAction.RESUME }
        val levels get() = rows.map { it.level.bars }
    }

    private fun run(name: String, samples: List<NetSample>, sending: Boolean = true, mon: QualityMonitor = QualityMonitor()): Trace {
        val rows = samples.map { mon.feed(it, sending) }
        val sb = StringBuilder("[$name] bars=" + rows.joinToString("") { it.level.bars.toString() })
        rows.forEachIndexed { i, r -> if (r.video != VideoAction.NONE) sb.append("  ${r.video}@sample${i + 1}(t=${(i + 1) * 2}s)") }
        sb.append("  final caps: video ${rows.last().caps.videoMaxBps / 1000}kbps x1/${rows.last().caps.videoScaleDown} @${rows.last().caps.videoFps}fps, audio ${rows.last().caps.audioMaxBps / 1000}kbps")
        println(sb)
        return Trace(rows)
    }

    @Test fun wifiStaysExcellentAndNeverTouchesVideo() {
        val t = run("wifi", rep(30, s()))
        assertTrue(t.levels.all { it == 4 }); assertEquals(-1, t.pausedAt)
        assertEquals(1_200_000, t.rows.last().caps.videoMaxBps)
    }

    @Test fun threeGKeepsVideoButAtLowerQuality() {
        val t = run("3G (rtt 250, loss 1%, jitter 30, 400 kbit/s)", rep(20, s(rtt = 250.0, loss = 1.0, jit = 30.0, avail = 400.0)))
        assertEquals(3, t.rows.last().level.bars); assertEquals(-1, t.pausedAt)
        assertTrue(t.rows.last().caps.videoMaxBps <= 600_000); assertTrue(t.rows.last().caps.videoScaleDown >= 1.5)
    }

    @Test fun twoGPausesVideoAndKeepsAudioBudget() {
        val t = run("2G/EDGE (rtt 900, loss 3%, jitter 90, 50 kbit/s)", rep(12, s(rtt = 900.0, loss = 3.0, jit = 90.0, avail = 50.0)))
        assertEquals(1, t.rows.last().level.bars)
        assertTrue("video paused after about 3 bad samples", t.pausedAt in 2..4)
        assertEquals(20_000, t.rows.last().caps.audioMaxBps); assertTrue(t.rows.last().videoPaused)
    }

    @Test fun packetLossLevels() {
        val five = run("loss 5%", rep(20, s(loss = 5.0)))
        assertEquals(2, five.rows.last().level.bars); assertEquals(-1, five.pausedAt)       // fair: video is thinned, not dropped
        val twelve = run("loss 12%", rep(20, s(loss = 12.0)))
        assertEquals(1, twelve.rows.last().level.bars); assertTrue(twelve.pausedAt >= 0)
        val thirty = run("loss 30%", rep(20, s(loss = 30.0)))
        assertEquals(1, thirty.rows.last().level.bars); assertTrue(thirty.pausedAt in 0..3)
    }

    @Test fun recoversAfterTheNetworkIsGoodAgain() {
        val t = run("2G for 20 s then Wi-Fi", rep(10, s(rtt = 900.0, loss = 15.0, jit = 90.0, avail = 40.0)) + rep(30, s()))
        assertTrue(t.pausedAt >= 0); assertTrue("video comes back", t.resumedAt > t.pausedAt)
        assertTrue("not at once: about 8 good samples are needed", t.resumedAt >= 10 + 7)
        assertEquals(4, t.rows.last().level.bars); assertFalse(t.rows.last().videoPaused)
    }

    @Test fun flappingDoesNotMakeTheBarsOrVideoJump() {
        val flap = (0 until 40).map { if (it % 2 == 0) s() else s(rtt = 700.0, loss = 20.0, jit = 100.0, avail = 40.0) }
        val t = run("flapping good/bad every 2 s", flap)
        assertEquals("video is never paused by alternating samples", -1, t.pausedAt)
        val changes = t.levels.zipWithNext().count { it.first != it.second }
        assertTrue("bars change rarely (changes=$changes)", changes <= 4)
    }

    @Test fun repeatedDropsMakeTheRestoreSlower() {
        val mon = QualityMonitor()
        val bad = s(rtt = 900.0, loss = 20.0, jit = 90.0, avail = 30.0)
        val a = run("cycle 1", rep(8, bad) + rep(20, s()), mon = mon)
        assertTrue(a.pausedAt >= 0 && a.resumedAt > a.pausedAt)
        // a few samples after video came back the network is bad again, then good again
        val b = run("cycle 2 (soon after a restore)", rep(8, bad) + rep(10, s()) + rep(20, s()), mon = mon)
        val p = b.pausedAt; val r = b.resumedAt
        assertTrue(p >= 0)
        println("cycle 2: paused at sample ${p + 1}, restored at ${if (r < 0) "never" else (r + 1)}")
        val c = b.rows.drop(p + 1).indexOfFirst { it.video == VideoAction.RESUME }
        assertTrue("second restore must wait longer than 8 good samples (took ${c + 1})", c >= 14 || c < 0)
    }

    @Test fun theCameraChoiceOfThePersonIsRespected() {
        // camera off (or voice call): nothing is sent, so the monitor never pauses or resumes anything
        val t = run("camera off on 2G", rep(20, s(rtt = 900.0, loss = 20.0, jit = 90.0, avail = 30.0)), sending = false)
        assertTrue(t.rows.all { it.video == VideoAction.NONE }); assertFalse(t.rows.last().videoPaused)
    }

    @Test fun bitrateEstimateIsIgnoredForVoiceCalls() {
        val t = run("voice call, tiny estimate but healthy path", rep(10, s(avail = 10.0)), sending = false)
        assertEquals(4, t.rows.last().level.bars)
    }

    @Test fun missingStatsDoNotChangeAnything() {
        val mon = QualityMonitor()
        mon.feed(s(), true)
        val u = mon.feed(NetSample(null, null, null, null), true)
        assertEquals(4, u.level.bars); assertEquals(VideoAction.NONE, u.video)
    }

    @Test fun capsKeepAudioFirstAndFollowTheEstimate() {
        for (l in NetLevel.values()) {
            val c = CallQuality.caps(l, null)
            assertTrue(c.audioMaxBps >= 20_000); assertTrue(c.videoScaleDown >= 1.0); assertTrue(c.videoMaxBps > c.audioMaxBps || l == NetLevel.POOR)
        }
        val low = CallQuality.caps(NetLevel.EXCELLENT, 150.0)           // 150 kbit/s estimate: video must fit next to audio
        assertTrue(low.videoMaxBps in 50_000..90_000)
        assertEquals(1_200_000, CallQuality.caps(NetLevel.EXCELLENT, 9000.0).videoMaxBps)
    }

    // ------------------------------------------------------------------------------------------ stats -> sample
    private fun row(type: String, id: String, vararg kv: Pair<String, Any?>) = StatRow(type, id, mapOf(*kv))

    @Test fun readsSelectedPairAndComputesLossOverTheLastRoundOnly() {
        val r = StatsReader()
        fun rows(recv: Long, lost: Long) = listOf(
            row("transport", "T1", "selectedCandidatePairId" to "P2"),
            row("candidate-pair", "P1", "state" to "succeeded", "currentRoundTripTime" to 0.9, "availableOutgoingBitrate" to 1000.0),
            row("candidate-pair", "P2", "state" to "succeeded", "nominated" to true, "currentRoundTripTime" to 0.120, "availableOutgoingBitrate" to 350_000.0),
            row("inbound-rtp", "I1", "kind" to "audio", "packetsReceived" to recv, "packetsLost" to lost, "jitter" to 0.012),
            row("inbound-rtp", "I2", "kind" to "video", "packetsReceived" to 99999L, "packetsLost" to 99999L, "jitter" to 9.0),
        )
        val first = r.read(rows(1000, 500))                       // lots of old loss since the call began: first round cannot judge a rate
        assertEquals(120.0, first.rttMs!!, 0.01); assertEquals(350.0, first.availKbps!!, 0.01); assertEquals(12.0, first.jitterMs!!, 0.01); assertNull(first.lossPct)
        val second = r.read(rows(1090, 510))                       // 90 received, 10 lost in this round = 10%
        assertEquals(10.0, second.lossPct!!, 0.01)
        val third = r.read(rows(1190, 510))                        // no new loss
        assertEquals(0.0, third.lossPct!!, 0.01)
    }

    @Test fun usesTheRemoteReportForMyOutgoingLossAndRtt() {
        val r = StatsReader()
        val x = r.read(listOf(row("remote-inbound-rtp", "R1", "kind" to "audio", "roundTripTime" to 0.4, "fractionLost" to 0.08, "jitter" to 0.03)))
        assertEquals(400.0, x.rttMs!!, 0.01); assertEquals(8.0, x.lossPct!!, 0.01); assertEquals(30.0, x.jitterMs!!, 0.01); assertNull(x.availKbps)
        val none = r.read(emptyList()); assertNull(none.rttMs); assertNull(none.lossPct)
    }

    @Test fun counterResetsAndTinyRoundsDoNotGiveWildLoss() {
        val r = StatsReader()
        fun rows(recv: Long, lost: Long) = listOf(row("inbound-rtp", "I1", "kind" to "audio", "packetsReceived" to recv, "packetsLost" to lost))
        r.read(rows(1000, 10))
        assertNull(r.read(rows(1002, 12)).lossPct)                 // 4 packets in the round: too few to judge
        assertEquals(0.0, r.read(rows(50, 0)).lossPct ?: 0.0, 0.0)   // counters went down (new stream after an ICE restart): clamped, no negative loss
    }

    // ------------------------------------------------------------------------------------------ extra TURN servers from the database
    @Test fun iceConfigAcceptsGoodEntriesAndSkipsBadOnes() {
        val parsed = IceConfig.parse(listOf(
            mapOf("urls" to "turn:a.example.org:3478, turns:a.example.org:443?transport=tcp", "username" to "u", "credential" to "c"),
            mapOf("urls" to listOf("stun:s.example.org:3478")),                                   // stun needs no login
            mapOf("urls" to "turn:nologin.example.org:3478"),                                      // turn without login: skipped
            mapOf("urls" to "http://evil.example.org", "username" to "u", "credential" to "c"),    // not an ICE url
            mapOf("urls" to listOf(5, "javascript:alert(1)"), "username" to "u", "credential" to "c"),
            mapOf("username" to "u"),
        ))
        assertEquals(2, parsed.size)
        assertEquals(listOf("turn:a.example.org:3478", "turns:a.example.org:443?transport=tcp"), parsed[0].urls)
        assertEquals("c", parsed[0].credential); assertNull(parsed[1].username)
        assertEquals(6, IceConfig.parse(List(20) { mapOf("urls" to "stun:s$it.example.org:3478") }).size)
    }
}
