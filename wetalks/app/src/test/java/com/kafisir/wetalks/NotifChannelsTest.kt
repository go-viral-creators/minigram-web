package com.kafisir.wetalks

import android.app.NotificationManager
import com.kafisir.wetalks.data.ChatNotif
import com.kafisir.wetalks.data.Kind
import com.kafisir.wetalks.data.NotifChannels
import org.junit.Assert.*
import org.junit.Test

class NotifChannelsTest {
    @Test fun everyCategoryIsDefinedOnceWithSensibleImportance() {
        val ids = NotifChannels.specs.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in listOf("messages", "groups", "calls", "missed_calls", "services", "connection", "background", "playback")) assertTrue("missing $id", id in ids)
        fun imp(id: String) = NotifChannels.specs.first { it.id == id }.importance
        assertEquals(NotificationManager.IMPORTANCE_HIGH, imp("messages")); assertEquals(NotificationManager.IMPORTANCE_HIGH, imp("groups"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, imp("calls")); assertEquals(NotificationManager.IMPORTANCE_HIGH, imp("missed_calls"))
        // background work and the connection notice must stay quiet
        assertTrue(imp("connection") <= NotificationManager.IMPORTANCE_MIN); assertTrue(imp("background") <= NotificationManager.IMPORTANCE_LOW)
        assertFalse(NotifChannels.specs.first { it.id == "background" }.sound); assertFalse(NotifChannels.specs.first { it.id == "connection" }.vibrate)
        // the call notification itself is silent (the ringtone is played by the app so it can be cut by the power button)
        assertFalse(NotifChannels.specs.first { it.id == "calls" }.sound)
    }

    @Test fun chatsUseTheirOwnCategory() {
        assertEquals("messages", NotifChannels.forChat(Kind.DM)); assertEquals("groups", NotifChannels.forChat(Kind.GROUP))
    }

    @Test fun mutingRules() {
        val now = 1_000_000L
        assertTrue(ChatNotif.shouldNotify(Kind.DM, false, 0, now))
        assertFalse(ChatNotif.shouldNotify(Kind.DM, true, 0, now))                       // muted always (synced flag)
        assertFalse(ChatNotif.shouldNotify(Kind.GROUP, false, now + 1, now))             // muted for a while
        assertTrue(ChatNotif.shouldNotify(Kind.GROUP, false, now - 1, now))              // the timed mute has run out
        assertFalse(ChatNotif.shouldNotify(Kind.CHANNEL, false, 0, now))                 // channels do not notify yet
    }
}
