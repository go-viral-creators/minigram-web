package com.kafisir.wetalks

import com.kafisir.wetalks.data.BlockNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BlockNotesTest {
    @Test fun roundTrip() {
        val l = listOf(100L to true, 200L to false, 300L to true)
        assertEquals(l, BlockNotes.parse(BlockNotes.encode(l)))
    }
    @Test fun emptyAndGarbage() {
        assertEquals(emptyList<Pair<Long, Boolean>>(), BlockNotes.parse(null))
        assertEquals(listOf(5L to true), BlockNotes.parse("x:1,5:1,:0"))
    }
    @Test fun repeatedStateIsNotRecordedTwice() {
        val a = BlockNotes.plus(emptyList(), 1, true)
        assertEquals(listOf(1L to true), a)
        assertSame(a, BlockNotes.plus(a, 2, true))
        assertEquals(listOf(1L to true, 3L to false), BlockNotes.plus(a, 3, false))
    }
    @Test fun keepsOnlyTheNewest() {
        var l = emptyList<Pair<Long, Boolean>>()
        for (i in 1..50) l = BlockNotes.plus(l, i.toLong(), i % 2 == 1)
        assertEquals(20, l.size); assertEquals(50L, l.last().first)
    }
    @Test fun wording() {
        assertEquals("You blocked this user", BlockNotes.text(true))
        assertEquals("You unblocked this user", BlockNotes.text(false))
    }
}
