package com.kafisir.wetalks

import com.kafisir.wetalks.data.DisappearRules as R
import com.kafisir.wetalks.data.Kind
import org.junit.Assert.*
import org.junit.Test

class DisappearRulesTest {
    @Test fun timers() {
        assertEquals(listOf(0, 86400, 604800, 7776000), R.CHOICES)
        assertTrue(R.isValidTimer(86400)); assertFalse(R.isValidTimer(3600)); assertFalse(R.isValidTimer(-1)); assertFalse(R.isValidTimer(86400L * 100000))
        assertEquals("Off", R.label(0)); assertEquals("24 hours", R.label(86400)); assertEquals("7 days", R.label(604800)); assertEquals("90 days", R.label(7776000))
    }
    @Test fun expAndExpired() {
        assertEquals(0L, R.computeExp(1000, 0))
        assertEquals(1000L + 86_400_000L, R.computeExp(1000, 86400))
        assertFalse(R.isExpired(0, 5)); assertFalse(R.isExpired(10, 5)); assertTrue(R.isExpired(5, 5)); assertTrue(R.isExpired(4, 5))
        assertFalse(R.isDeletable(5, 5)); assertTrue(R.isDeletable(4, 5)); assertFalse(R.isDeletable(0, 5))
    }
    @Test fun buckets() {
        assertEquals(0L, R.bucket(3_599_999)); assertEquals(1L, R.bucket(3_600_000))
        assertEquals("expiry/1/a_b/m1", R.indexPath(3_600_001, "a_b", "m1"))
        assertEquals(emptyList<Long>(), R.dueBuckets(9, 10 * 3_600_000L))
        assertEquals(listOf(10L), R.dueBuckets(9, 11 * 3_600_000L + 5))
        assertEquals(48, R.dueBuckets(0, 1000 * 3_600_000L).size)
        assertEquals(999L, R.dueBuckets(0, 1000 * 3_600_000L).last())
    }
    @Test fun dueAndNext() {
        assertEquals(listOf("a", "c"), R.dueIds(listOf("a" to 1L, "b" to 100L, "c" to 5L, "d" to 0L), 10))
        assertEquals(20L, R.nextExpiry(listOf(5L, 20L, 30L, 0L), 10)); assertEquals(0L, R.nextExpiry(listOf(5L, 0L), 10))
        assertEquals(0L, R.delayUntil(0, 1_000_000)); assertEquals(R.SAFETY_MS + 500 + 1000, R.delayUntil(2000, 1000))
    }
    @Test fun whoMayChange() {
        assertTrue(R.mayChange(Kind.DM, false)); assertFalse(R.mayChange(Kind.GROUP, false)); assertTrue(R.mayChange(Kind.GROUP, true))
        assertFalse(R.mayChange(Kind.CHANNEL, false)); assertTrue(R.mayChange(Kind.CHANNEL, true))
    }
    @Test fun systemText() {
        assertEquals("Alice turned on disappearing messages: 24 hours", R.systemText("Alice", 86400))
        assertEquals("Alice turned off disappearing messages", R.systemText("Alice", 0))
        assertEquals("Someone turned off disappearing messages", R.systemText("  ", 0))
        assertEquals("You turned on disappearing messages: 7 days", R.systemTextFor(R.systemText("Alice", 604800), "Alice", true))
        assertEquals("Alice turned off disappearing messages", R.systemTextFor(R.systemText("Alice", 0), "Alice", false))
        assertNull(R.subtitle(0)); assertEquals("Disappearing · 90 days", R.subtitle(7776000))
    }
    @Test fun ledger() {
        var m = emptyMap<String, R.Entry>()
        m = R.noteMedia(m, "x1", 100, false)
        assertSame(m, R.noteMedia(m, "x1", 100, false))
        m = R.noteMedia(m, "x1", 100, true)
        m = R.noteMedia(m, "x1", 100, false)
        assertTrue(m["x1"]!!.mine)
        assertSame(m, R.noteMedia(m, "", 5, true)); assertSame(m, R.noteMedia(m, "y", 0, true))
        m = R.noteMedia(m, "x2", 500, false)
        assertEquals(listOf("x1"), R.dueMedia(m, 200))
        assertEquals(m, R.parseLedger(R.writeLedger(m)))
        assertTrue(R.parseLedger("not json").isEmpty()); assertTrue(R.parseLedger(null).isEmpty())
        var big = emptyMap<String, R.Entry>(); for (i in 1..30) big = R.noteMedia(big, "k$i", i.toLong(), false, cap = 10)
        assertEquals(10, big.size); assertTrue("k30" in big); assertFalse("k1" in big)
    }
}
