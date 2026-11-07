package com.kafisir.wetalks

import com.kafisir.wetalks.data.CallEntry
import com.kafisir.wetalks.data.CallLog
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class CallLogTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val h = 3_600_000L
    private fun e(id: String, peer: String = "bob", out: Boolean = false, st: String = "missed", ts: Long = 0, video: Boolean = false, dur: Int = 0) = CallEntry(id, peer, out, video, st, ts, dur)

    @Test fun callerSideOutcomes() {
        // receiver offline / app closed: the call never rang
        assertEquals("unavailable", CallLog.outgoingStatus(0, "missed", peerRang = false, byMe = false))
        // it rang and nobody answered
        assertEquals("noanswer", CallLog.outgoingStatus(0, "missed", peerRang = true, byMe = false))
        // I hung up before they answered (even if it was ringing)
        assertEquals("cancelled", CallLog.outgoingStatus(0, "missed", peerRang = true, byMe = true))
        assertEquals("declined", CallLog.outgoingStatus(0, "declined", peerRang = true, byMe = false))
        assertEquals("busy", CallLog.outgoingStatus(0, "busy", peerRang = false, byMe = false))
        assertEquals("ended", CallLog.outgoingStatus(95, "ended", peerRang = true, byMe = true))
    }

    @Test fun receiverSideOutcomes() {
        assertEquals("missed", CallLog.incomingStatus(0, "missed"))      // caller cancelled or it timed out
        assertEquals("declined", CallLog.incomingStatus(0, "declined"))
        assertEquals("missed", CallLog.incomingStatus(0, "busy"))        // I was on another call
        assertEquals("ended", CallLog.incomingStatus(12, "ended"))
    }

    @Test fun labelsAndDurations() {
        assertEquals("1:05", CallLog.duration(65)); assertEquals("1:02:03", CallLog.duration(3723)); assertEquals("0:00", CallLog.duration(0))
        assertEquals("Missed", CallLog.label(e("1"))); assertEquals("2:00", CallLog.label(e("2", st = "ended", dur = 120)))
        assertEquals("No answer", CallLog.label(e("3", out = true, st = "noanswer"))); assertEquals("Unavailable", CallLog.label(e("4", out = true, st = "unavailable")))
        assertEquals("Cancelled", CallLog.label(e("5", out = true, st = "cancelled")))
    }

    @Test fun consecutiveSameCallsAreGroupedOtherwiseNot() {
        val day = 20_000L * 24 * h
        val list = listOf(e("a", ts = day + 5 * h), e("b", ts = day + 4 * h), e("c", ts = day + 3 * h),            // 3 missed from bob today
            e("d", peer = "carol", ts = day + 2 * h),                                                              // someone else breaks the run
            e("e", ts = day + 1 * h),                                                                              // bob again: a new row
            e("f", ts = day - 1 * h), e("g", ts = day - 2 * h))                                                    // yesterday: its own group
        val g = CallLog.group(list, utc)
        assertEquals(listOf(3, 1, 1, 2), g.map { it.count })
        assertEquals("a", g[0].first.id)
        // a different outcome, direction or kind is not grouped
        val mixed = CallLog.group(listOf(e("1", ts = day), e("2", st = "declined", ts = day), e("3", out = true, st = "ended", ts = day), e("4", video = true, ts = day)), utc)
        assertEquals(4, mixed.size)
    }

    @Test fun missedFilterAndSearch() {
        val names = mapOf("bob" to "Bob Marley", "carol" to "Carol")
        val list = listOf(e("1", peer = "bob"), e("2", peer = "bob", out = true, st = "noanswer"), e("3", peer = "carol", st = "ended", dur = 9), e("4", peer = "carol"))
        assertEquals(listOf("1", "4"), CallLog.filter(list, true, "", { names.getValue(it) }).map { it.id })              // outgoing no-answer is not a "missed call"
        assertEquals(listOf("1", "2"), CallLog.filter(list, false, "marley", { names.getValue(it) }).map { it.id })
        assertEquals(listOf("4"), CallLog.filter(list, true, "CAR", { names.getValue(it) }).map { it.id })
        assertEquals(4, CallLog.filter(list, false, "  ", { names.getValue(it) }).size)
    }
}
