package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import org.junit.Assert.*
import org.junit.Test

class ReportLogicTest {
    private val me = "meUid"
    private val dm = ChatRef("bobUid_meUid", Kind.DM, "bobUid")
    private val grp = ChatRef("-Ngroup1", Kind.GROUP)
    private val chn = ChatRef("-Nchan1", Kind.CHANNEL)

    @Test fun keys_match_the_server_rules() {
        assertEquals("user~bobUid", ReportLogic.targetKey(ReportTarget.User("bobUid"), me))
        assertEquals("msg~-Ngroup1~-Nm1", ReportLogic.targetKey(ReportTarget.Message(grp, "-Nm1", "bobUid", "hi", "text", 1), me))
        assertEquals("chat~bobUid_meUid", ReportLogic.targetKey(ReportTarget.Chat("bobUid"), me))
        assertEquals("chat~bobUid_meUid", ReportLogic.targetKey(ReportTarget.Chat("bobUid"), me))
        assertEquals("group~-Ngroup1", ReportLogic.targetKey(ReportTarget.Group("-Ngroup1"), me))
        assertEquals("channel~-Nchan1", ReportLogic.targetKey(ReportTarget.Channel("-Nchan1"), me))
        // the rules accept only these characters in a key
        val re = Regex("^(user|msg|chat|group|channel)~[A-Za-z0-9_~-]{1,300}$")
        listOf(ReportTarget.User("bobUid"), ReportTarget.Message(dm, "-Nm1", "bobUid", "", "text", 1), ReportTarget.Chat("bobUid"), ReportTarget.Group("-Ng"), ReportTarget.Channel("-Nc"))
            .forEach { assertTrue(ReportLogic.targetKey(it, me), re.matches(ReportLogic.targetKey(it, me))) }
    }

    @Test fun dm_id_is_sorted() { assertEquals("a_b", ReportLogic.dmId("b", "a")); assertEquals("a_b", ReportLogic.dmId("a", "b")) }

    @Test fun ids_are_validated() {
        assertTrue(ReportLogic.validId("-Nabc_DEF123"))
        assertFalse(ReportLogic.validId(""))
        assertFalse(ReportLogic.validId(null))
        assertFalse(ReportLogic.validId("a/b"))
        assertFalse(ReportLogic.validId("a.b"))
        assertFalse(ReportLogic.validId("x".repeat(129)))
    }

    @Test fun no_self_report_and_bad_ids() {
        assertNotNull(ReportLogic.problem(ReportTarget.User(me), me))
        assertNotNull(ReportLogic.problem(ReportTarget.Message(grp, "m1", me, "x", "text", 1), me))
        assertNotNull(ReportLogic.problem(ReportTarget.Chat(me), me))
        assertNotNull(ReportLogic.problem(ReportTarget.User("bad/id"), me))
        assertNotNull(ReportLogic.problem(ReportTarget.User("bobUid"), ""))
        assertNull(ReportLogic.problem(ReportTarget.User("bobUid"), me))
        assertNull(ReportLogic.problem(ReportTarget.Group("-Ng"), me))
    }

    @Test fun admin_copy_only_for_groups_and_channels() {
        assertNull(ReportLogic.adminCopyChat(ReportTarget.Chat("bobUid")))
        assertNull(ReportLogic.adminCopyChat(ReportTarget.Message(dm, "m", "bobUid", "x", "text", 1)))
        assertNull(ReportLogic.adminCopyChat(ReportTarget.User("bobUid")))
        assertEquals("-Ngroup1", ReportLogic.adminCopyChat(ReportTarget.Message(grp, "m", "bobUid", "x", "text", 1)))
        assertEquals("-Nchan1", ReportLogic.adminCopyChat(ReportTarget.Message(chn, "m", "bobUid", "x", "text", 1)))
        assertEquals("-Ngroup1", ReportLogic.adminCopyChat(ReportTarget.User("bobUid", grp)))
        assertNull(ReportLogic.adminCopyChat(ReportTarget.User("bobUid", dm)))
        assertEquals("-Ng", ReportLogic.adminCopyChat(ReportTarget.Group("-Ng")))
    }

    @Test fun reported_person() {
        assertEquals("bobUid", ReportLogic.reportedPerson(ReportTarget.User("bobUid")))
        assertEquals("bobUid", ReportLogic.reportedPerson(ReportTarget.Message(grp, "m", "bobUid", "x", "text", 1)))
        assertEquals("bobUid", ReportLogic.reportedPerson(ReportTarget.Chat("bobUid")))
        assertNull(ReportLogic.reportedPerson(ReportTarget.Group("g")))
    }

    @Test fun note_and_snapshot_are_limited() {
        assertEquals(500, ReportLogic.cleanNote("  " + "x".repeat(900) + "  ").length)
        assertEquals("hi", ReportLogic.cleanNote("  hi \n"))
        assertEquals(4000, ReportLogic.snapshotOf("text", "y".repeat(9000)).length)
        assertEquals("[photo]", ReportLogic.snapshotOf("image", ""))
        assertEquals("[photo] look", ReportLogic.snapshotOf("image", " look "))
        assertEquals("[voice message]", ReportLogic.snapshotOf("voice", ""))
        assertEquals("plain", ReportLogic.snapshotOf("text", "plain"))
    }

    @Test fun reasons_are_the_five_server_values() {
        assertEquals(listOf("spam", "scam", "nudity", "harassment", "other"), ReportReason.values().map { it.key })
    }

    @Test fun daily_counter() {
        val day = 20_000L * 86_400_000L
        assertEquals(ReportLogic.dayKey(day) to 1L, ReportLogic.nextQuota(null, null, day))
        assertEquals(ReportLogic.dayKey(day) to 6L, ReportLogic.nextQuota(ReportLogic.dayKey(day), 5, day + 1000))
        assertEquals(ReportLogic.dayKey(day) to 10L, ReportLogic.nextQuota(ReportLogic.dayKey(day), 9, day))
        assertNull(ReportLogic.nextQuota(ReportLogic.dayKey(day), 10, day))                       // the 11th today
        assertEquals(ReportLogic.dayKey(day) + 1 to 1L, ReportLogic.nextQuota(ReportLogic.dayKey(day), 10, day + 86_400_000L))   // a new day starts again
        assertEquals(20_000L, ReportLogic.dayKey(day))
        assertEquals(20_000L, ReportLogic.dayKey(day + 86_399_999L))
    }
}
