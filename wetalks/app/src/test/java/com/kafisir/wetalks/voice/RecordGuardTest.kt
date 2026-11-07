package com.kafisir.wetalks.voice

import org.junit.Assert.*
import org.junit.Test

class RecordGuardTest {
    private fun f(mode: Int = 0, call: Boolean = false, others: Int = 0) = RecordGuard.Facts(mode, call, others)

    @Test fun quietPhoneAllows() = assertTrue(RecordGuard.decide(f()).allowed)

    @Test fun wetalksCallBlocks() {
        val v = RecordGuard.decide(f(call = true)); assertFalse(v.allowed); assertEquals("You are on a call", v.message)
    }

    @Test fun phoneCallRingingAndVoipBlock() {
        assertEquals("You are on a phone call", RecordGuard.decide(f(mode = RecordGuard.MODE_IN_CALL)).message)
        assertEquals("Your phone is ringing", RecordGuard.decide(f(mode = RecordGuard.MODE_RINGTONE)).message)
        assertFalse(RecordGuard.decide(f(mode = RecordGuard.MODE_IN_COMMUNICATION)).allowed)
    }

    @Test fun anotherAppRecordingBlocks() {
        val v = RecordGuard.decide(f(others = 1)); assertFalse(v.allowed); assertEquals("Another app is using the microphone", v.message)
    }

    @Test fun callMessageWinsOverOtherAppMessage() {
        assertEquals("You are on a call", RecordGuard.decide(f(call = true, others = 2)).message)
        assertEquals("You are on a phone call", RecordGuard.decide(f(mode = RecordGuard.MODE_IN_CALL, others = 2)).message)
    }

    @Test fun duringRecordingOnlyCallsInterrupt() {
        assertNull(RecordGuard.interruptReason(f())); assertNull(RecordGuard.interruptReason(f(others = 3)))
        assertNotNull(RecordGuard.interruptReason(f(call = true)))
        assertNotNull(RecordGuard.interruptReason(f(mode = RecordGuard.MODE_RINGTONE)))
        assertNotNull(RecordGuard.interruptReason(f(mode = RecordGuard.MODE_IN_CALL)))
    }

    @Test fun permissionSteps() {
        assertEquals(MicPermission.Step.GO, MicPermission.next(true, false, false))
        assertEquals(MicPermission.Step.GO, MicPermission.next(true, true, true))
        assertEquals(MicPermission.Step.ASK, MicPermission.next(false, false, false))                // never asked
        assertEquals(MicPermission.Step.EXPLAIN_THEN_ASK, MicPermission.next(false, true, true))     // denied once
        assertEquals(MicPermission.Step.OPEN_SETTINGS, MicPermission.next(false, false, true))       // permanently denied
    }

    @Test fun sourceOrderPrefersVoiceRecognition() {
        assertEquals(listOf(6, 7, 1), RecordSource.order())
    }

    @Test fun focusLossInterruptsButDuckAndGainDoNot() {
        assertTrue(FocusRules.interrupts(-1)); assertTrue(FocusRules.interrupts(-2))
        assertFalse(FocusRules.interrupts(-3)); assertFalse(FocusRules.interrupts(1))
    }

    @Test fun dndOnlyTightensAndNeverOverridesTheUser() {
        assertTrue(DndAccess.shouldChange(DndAccess.ALL)); assertTrue(DndAccess.shouldChange(DndAccess.PRIORITY))
        assertFalse(DndAccess.shouldChange(DndAccess.ALARMS)); assertFalse(DndAccess.shouldChange(DndAccess.NONE)); assertFalse(DndAccess.shouldChange(0))
        assertTrue(DndAccess.shouldRestore(true, DndAccess.ALARMS, DndAccess.ALARMS))
        assertFalse(DndAccess.shouldRestore(true, DndAccess.ALL, DndAccess.ALARMS))      // the person changed it during recording
        assertFalse(DndAccess.shouldRestore(false, DndAccess.ALARMS, DndAccess.ALARMS))  // we never changed it
    }

    @Test fun dndQuestionIsAskedOnlyTwiceAndNeverAfterDontAskAgain() {
        assertTrue(DndAccess.shouldAsk(false, 0, false)); assertTrue(DndAccess.shouldAsk(false, 1, false))
        assertFalse(DndAccess.shouldAsk(false, 2, false)); assertFalse(DndAccess.shouldAsk(false, 0, true)); assertFalse(DndAccess.shouldAsk(true, 0, false))
    }
}
