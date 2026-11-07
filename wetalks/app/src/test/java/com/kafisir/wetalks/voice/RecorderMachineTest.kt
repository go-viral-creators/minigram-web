package com.kafisir.wetalks.voice

import com.kafisir.wetalks.voice.RecorderMachine.Event.*
import com.kafisir.wetalks.voice.RecorderMachine.State
import com.kafisir.wetalks.voice.RecorderMachine.State.*
import org.junit.Assert.*
import org.junit.Test

class RecorderMachineTest {
    private fun n(s: State, e: RecorderMachine.Event, sec: Int = 5) = RecorderMachine.next(s, e, sec)

    @Test fun pressStartsRecordingOnlyFromIdleOrCancelled() {
        assertEquals(RECORDING, n(IDLE, PRESS)); assertEquals(RECORDING, n(CANCELLED, PRESS))
        for (s in listOf(RECORDING, LOCKED, PAUSED, SAVED_DRAFT)) assertEquals(s, n(s, PRESS))
    }

    @Test fun holdAndRelease() {
        assertEquals(IDLE, n(RECORDING, RELEASE, 3))                    // sent
        assertEquals(CANCELLED, n(RECORDING, RELEASE, 0))               // too short
        assertEquals(CANCELLED, n(RECORDING, SLIDE_CANCEL, 30))         // swipe left
        assertEquals(LOCKED, n(RECORDING, LOCK))                        // swipe up
        assertEquals(IDLE, n(RECORDING, TIME_LIMIT, 180))
    }

    @Test fun lockedHasSendAndBin() {
        assertEquals(IDLE, n(LOCKED, SEND, 4)); assertEquals(CANCELLED, n(LOCKED, SEND, 0))
        assertEquals(CANCELLED, n(LOCKED, TRASH, 40))
        assertEquals(PAUSED, n(LOCKED, USER_PAUSE)); assertEquals(LOCKED, n(PAUSED, USER_RESUME))
    }

    @Test fun interruptionsKeepTheAudioAsADraft() {
        for (e in listOf(INTERRUPT, BACKGROUND, FAILED)) {
            assertEquals(PAUSED, n(RECORDING, e, 12)); assertEquals(PAUSED, n(LOCKED, e, 12))
        }
        // the draft can be resumed, sent or deleted
        assertEquals(LOCKED, n(PAUSED, USER_RESUME)); assertEquals(IDLE, n(PAUSED, SEND, 12)); assertEquals(CANCELLED, n(PAUSED, TRASH, 12))
    }

    @Test fun interruptionBeforeAnythingWasRecordedDropsIt() {
        for (e in listOf(INTERRUPT, BACKGROUND, FAILED)) { assertEquals(CANCELLED, n(RECORDING, e, 0)); assertEquals(CANCELLED, n(LOCKED, e, 0)) }
    }

    @Test fun closingTheChatParksADraftOrDropsTinyOnes() {
        for (s in listOf(RECORDING, LOCKED, PAUSED)) { assertEquals(SAVED_DRAFT, n(s, CLOSE_CHAT, 9)); assertEquals(CANCELLED, n(s, CLOSE_CHAT, 0)) }
        assertEquals(PAUSED, n(SAVED_DRAFT, OPEN_CHAT)); assertEquals(CANCELLED, n(SAVED_DRAFT, TRASH)); assertEquals(IDLE, n(SAVED_DRAFT, SEND))
        assertEquals(SAVED_DRAFT, n(SAVED_DRAFT, INTERRUPT))
    }

    @Test fun nothingHappensWhenIdle() {
        for (e in RecorderMachine.Event.values()) if (e != PRESS) { assertEquals(IDLE, n(IDLE, e)); assertEquals(CANCELLED, n(CANCELLED, e)) }
    }

    @Test fun everyPathLeavesTheMicrophoneClosed() {
        // whatever happens, a state that is not RECORDING/LOCKED never needs quiet (focus, Do Not Disturb)
        for (s in State.values()) for (e in RecorderMachine.Event.values()) for (sec in listOf(0, 5)) {
            val t = n(s, e, sec)
            assertEquals(t in listOf(RECORDING, LOCKED), RecorderMachine.needsQuiet(t))
            assertEquals(t in listOf(RECORDING, LOCKED), RecorderMachine.capturing(t))
        }
    }

    @Test fun barAndAudioFlags() {
        assertTrue(RecorderMachine.showsBar(PAUSED)); assertFalse(RecorderMachine.showsBar(SAVED_DRAFT)); assertFalse(RecorderMachine.showsBar(IDLE))
        assertTrue(RecorderMachine.holdsAudio(SAVED_DRAFT)); assertFalse(RecorderMachine.holdsAudio(CANCELLED))
    }
}
