package com.kafisir.wetalks

import com.kafisir.wetalks.data.*
import org.junit.Assert.*
import org.junit.Test

class CallAudioRulesTest {
    private val ear = AudioRoute(RouteKind.EARPIECE)
    private val spk = AudioRoute(RouteKind.SPEAKER)
    private val bt = AudioRoute(RouteKind.BLUETOOTH, 11, "Buds")
    private val bt2 = AudioRoute(RouteKind.BLUETOOTH, 12, "Car")
    private val wired = AudioRoute(RouteKind.WIRED, 21, "")
    private val phone = listOf(ear, spk)

    @Test fun normalizeAlwaysHasSpeakerAndOnlyPhonesHaveEarpiece() {
        assertEquals(listOf(spk), CallRoutes.normalize(emptyList(), false))
        assertEquals(listOf(ear, spk, bt, wired), CallRoutes.normalize(listOf(wired, bt), true))
        assertEquals(listOf(ear, spk, bt), CallRoutes.normalize(listOf(bt, bt), true))   // one entry per headset
    }

    @Test fun preferredOrder() {
        assertEquals(ear, CallRoutes.preferred(false, phone))
        assertEquals(spk, CallRoutes.preferred(true, phone))
        assertEquals(spk, CallRoutes.preferred(false, listOf(spk)))
        assertEquals(bt, CallRoutes.preferred(false, listOf(ear, spk, wired, bt)))
        assertEquals(wired, CallRoutes.preferred(true, listOf(ear, spk, wired)))
    }

    @Test fun newHeadsetIsSwitchedToAtOnce() {
        assertEquals(bt, CallRoutes.afterDevicesChanged(phone, phone + bt, ear, false))
        assertEquals(wired, CallRoutes.afterDevicesChanged(phone, phone + wired, spk, false))
        assertEquals(bt, CallRoutes.afterDevicesChanged(phone, phone + wired + bt, ear, false))
        // a second headset while one is in use: the new one wins, like phone apps
        assertEquals(bt2, CallRoutes.afterDevicesChanged(phone + bt, phone + bt + bt2, bt, false))
    }

    @Test fun headsetGoneFallsBack() {
        assertEquals(ear, CallRoutes.afterDevicesChanged(phone + bt, phone, bt, false))
        assertEquals(spk, CallRoutes.afterDevicesChanged(phone + wired, phone, wired, true))   // video call: speaker
        assertEquals(wired, CallRoutes.afterDevicesChanged(phone + wired + bt, phone + wired, bt, false))
    }

    @Test fun unrelatedChangeKeepsPersonsChoice() {
        // headset is connected but the person chose the earpiece: a refresh must not undo that
        assertEquals(ear, CallRoutes.afterDevicesChanged(phone + bt, phone + bt, ear, false))
        assertEquals(spk, CallRoutes.afterDevicesChanged(phone + bt, phone + bt, spk, false))
        // a headset that is not in use disappears: no change
        assertEquals(ear, CallRoutes.afterDevicesChanged(phone + bt, phone, ear, false))
    }

    @Test fun phoneWithoutEarpieceFallsBackToSpeaker() {
        assertEquals(spk, CallRoutes.afterDevicesChanged(listOf(spk, wired), listOf(spk), wired, false))
    }

    @Test fun videoStartMovesEarpieceToSpeakerButKeepsHeadsets() {
        assertEquals(spk, CallRoutes.afterVideoStarted(ear, phone))
        assertEquals(bt, CallRoutes.afterVideoStarted(bt, phone + bt))
        assertEquals(wired, CallRoutes.afterVideoStarted(wired, phone + wired))
        assertEquals(spk, CallRoutes.afterVideoStarted(spk, phone))
    }

    @Test fun speakerToggle() {
        assertEquals(spk, CallRoutes.afterSpeakerToggle(ear, phone, false))
        assertEquals(ear, CallRoutes.afterSpeakerToggle(spk, phone, false))
        assertEquals(bt, CallRoutes.afterSpeakerToggle(spk, phone + bt, false))
        assertEquals(spk, CallRoutes.afterSpeakerToggle(spk, listOf(spk), false))   // nothing else to go to
    }

    @Test fun fallbackIgnoresTheLostRoute() {
        assertEquals(ear, CallRoutes.fallback(false, phone + bt, bt))
        assertEquals(wired, CallRoutes.fallback(false, phone + wired + bt, bt))
        assertEquals(spk, CallRoutes.fallback(true, phone + bt, bt))
    }

    @Test fun sameRouteAndLabels() {
        assertTrue(CallRoutes.same(ear, AudioRoute(RouteKind.EARPIECE, 5)))
        assertFalse(CallRoutes.same(bt, bt2))
        assertFalse(CallRoutes.same(null, bt))
        assertEquals("Phone", CallRoutes.label(ear)); assertEquals("Buds", CallRoutes.label(bt))
        assertEquals("Bluetooth", CallRoutes.label(AudioRoute(RouteKind.BLUETOOTH, 1))); assertEquals("Wired headset", CallRoutes.label(wired))
        assertEquals("Bluetooth", CallRoutes.shortLabel(bt)); assertEquals("Headset", CallRoutes.shortLabel(wired))
    }

    @Test fun focusLossNeverEndsTheCall() {
        assertEquals(FocusAction.LOST, CallRoutes.focusAction(CallRoutes.FOCUS_LOSS))
        assertEquals(FocusAction.LOST, CallRoutes.focusAction(CallRoutes.FOCUS_LOSS_TRANSIENT))
        assertEquals(FocusAction.NONE, CallRoutes.focusAction(CallRoutes.FOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(FocusAction.RESTORE, CallRoutes.focusAction(CallRoutes.FOCUS_GAIN))
    }

    // ---- proximity
    private fun prox(phase: CallPhase = CallPhase.ACTIVE, video: Boolean = false, route: RouteKind = RouteKind.EARPIECE, sensor: Boolean = true, shown: Boolean = true) =
        ProximityRule.shouldHold(phase, video, route, sensor, shown)

    @Test fun proximityOnlyForVoiceOnEarpiece() {
        assertTrue(prox())
        assertTrue(prox(phase = CallPhase.OUTGOING)); assertTrue(prox(phase = CallPhase.CONNECTING))
        assertFalse(prox(phase = CallPhase.INCOMING)); assertFalse(prox(phase = CallPhase.IDLE))
        assertFalse(prox(video = true))
        assertFalse(prox(route = RouteKind.SPEAKER)); assertFalse(prox(route = RouteKind.BLUETOOTH)); assertFalse(prox(route = RouteKind.WIRED))
        assertFalse(prox(sensor = false))
        assertFalse(prox(shown = false))        // call minimized / app in the background
    }
}
