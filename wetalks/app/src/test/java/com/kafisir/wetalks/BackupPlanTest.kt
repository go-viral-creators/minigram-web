package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import com.kafisir.wetalks.data.BackupPlan.Info
import org.junit.Assert.*
import org.junit.Test

class BackupPlanTest {
    private val day = 86_400_000L
    private fun info(id: String, t: Long, gen: String, seq: Int) = Info(id, t, gen, seq, seq == 0, 100)

    @Test fun markMovesOnlyOverOldReadableMessages() {
        val start = 10 * day
        val t = BackupPlan.MarkTracker(null, start)
        t.add("a", 1 * day, true); t.add("b", 2 * day, true)
        t.add("c", start - 1000, true)           // inside the last day: stays inside the overlap
        t.add("d", 3 * day, true)                // after a blocked one nothing moves
        assertEquals("b", t.mark!!.key)
    }

    @Test fun unreadableMessageHoldsTheMarkBack() {
        val t = BackupPlan.MarkTracker(BackupPlan.Mark("a", 1), 10 * day)
        t.add("b", 2 * day, false); t.add("c", 3 * day, true)
        assertEquals("a", t.mark!!.key)
    }

    @Test fun incrementalSelection() {
        val m = BackupPlan.Mark("k5", 5)
        assertFalse(BackupPlan.isNew("k5", m)); assertFalse(BackupPlan.isNew("k4", m)); assertTrue(BackupPlan.isNew("k6", m)); assertTrue(BackupPlan.isNew("a", null))
    }

    @Test fun firstBackupIsFullThenIncrementalThenNewFull() {
        val now = 100 * day
        assertTrue(BackupPlan.next(emptyList(), now).full)
        val h = listOf(info("f1", now - day, "f1", 0))
        val n = BackupPlan.next(h, now); assertFalse(n.full); assertEquals(1, n.seq); assertEquals("f1", n.base); assertEquals("f1", n.gen)
        assertTrue(BackupPlan.next(h, now, forceFull = true).full)
        assertTrue("full backup older than 35 days", BackupPlan.next(listOf(info("f1", now - 40 * day, "f1", 0)), now).full)
        val many = (0..10).map { info("b$it", now - day + it, "f1", it) }
        assertTrue("10 updates already", BackupPlan.next(many, now).full)
    }

    @Test fun missingMiddlePartForcesNewFull() {
        val now = 50 * day
        val h = listOf(info("f1", now - 3 * day, "f1", 0), info("b2", now - day, "f1", 2))
        assertFalse(BackupPlan.intact(h)); assertTrue(BackupPlan.next(h, now).full)
    }

    @Test fun retentionKeepsNewestSetsWhole() {
        val h = listOf(info("f1", 1 * day, "f1", 0), info("b1", 2 * day, "f1", 1), info("f2", 10 * day, "f2", 0), info("b2", 11 * day, "f2", 1), info("f3", 20 * day, "f3", 0))
        assertEquals(setOf("f1", "b1"), BackupPlan.toDelete(h, 2).map { it.id }.toSet())
        assertEquals(setOf("f1", "b1", "f2", "b2"), BackupPlan.toDelete(h, 1).map { it.id }.toSet())
        assertTrue(BackupPlan.toDelete(h, 3).isEmpty())
        assertEquals("never fewer than one set", 4, BackupPlan.toDelete(h, 0).size)
    }

    @Test fun scheduleNextRun() {
        val now = 100 * day
        assertNull(BackupPlan.nextRun(BackupFreq.OFF, 0, now))
        assertEquals(now, BackupPlan.nextRun(BackupFreq.DAILY, 0, now))
        assertEquals(now - 2 * day + day + 0, (now - 2 * day) + day)
        assertEquals(now, BackupPlan.nextRun(BackupFreq.DAILY, now - 2 * day, now))       // overdue: now
        assertEquals(now + 6 * day, BackupPlan.nextRun(BackupFreq.WEEKLY, now - day, now))
        assertEquals(6 * day, BackupPlan.initialDelayMs(BackupFreq.WEEKLY, now - day, now))
        assertTrue(BackupPlan.overdue(BackupFreq.DAILY, now - 3 * day, now, 0)); assertFalse(BackupPlan.overdue(BackupFreq.DAILY, now - day, now, 0))
        assertFalse(BackupPlan.overdue(BackupFreq.OFF, 0, now, 0))
        assertEquals(BackupFreq.OFF, BackupFreq.of("garbage"))
    }

    @Test fun journalRoundTripAndResumeRules() {
        val j = BackupPlan.Journal("r1", 1000, false, "g1", 3, "b2", true, listOf("m1", "m2"))
        j.done["c1"] = BackupPlan.ChatDone(ChatPart("c1", "dm", "chats/x", 4, 1, 9, "k9", 9), "c_1.part")
        val back = BackupPlan.Journal.parse(j.toJson())!!
        assertEquals(listOf("c2"), back.pending(listOf("c1", "c2"))); assertEquals(listOf("m1", "m2"), back.mediaPlan); assertEquals(4, back.done["c1"]!!.part.count)
        assertTrue(back.usable(false, "g1", 3, "b2", true, 2000))
        assertFalse("too old", back.usable(false, "g1", 3, "b2", true, 1000 + 25 * 3_600_000L))
        assertFalse("other kind", back.usable(true, null, 0, null, true, 2000)); assertFalse(back.usable(false, "g1", 4, "b2", true, 2000))
        assertNull(BackupPlan.Journal.parse("garbage"))
    }

    @Test fun marksComeFromTheSetInOrder() {
        fun man(seq: Int, key: String) = BackupManifest("i$seq", 1, "", "a", "g", seq, null, seq == 0, false, listOf(ChatPart("c", "dm", "", 0, 0, 0, key, seq.toLong())), emptyList(), emptyList())
        assertEquals("k2", BackupPlan.marks(listOf(man(1, "k2"), man(0, "k1")))["c"]!!.key)
    }
}
