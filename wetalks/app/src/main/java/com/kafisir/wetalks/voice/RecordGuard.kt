package com.kafisir.wetalks.voice

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.kafisir.wetalks.data.CallPhase
import com.kafisir.wetalks.data.Calls

/**
 * Decides whether a voice message may be recorded right now. The decision is a pure function ([decide]); [facts] only
 * reads the state from the phone. Android lets several apps record at once on newer versions, but a message recorded
 * during a call would be unusable, so we refuse.
 */
object RecordGuard {
    // same values as AudioManager.MODE_*, kept here so the logic needs no Android classes
    const val MODE_NORMAL = 0; const val MODE_RINGTONE = 1; const val MODE_IN_CALL = 2; const val MODE_IN_COMMUNICATION = 3

    class Facts(val audioMode: Int, val wetalksCall: Boolean, val otherAppsRecording: Int)
    class Verdict(val allowed: Boolean, val message: String)

    fun decide(f: Facts): Verdict = when {
        f.wetalksCall -> Verdict(false, "You are on a call")
        f.audioMode == MODE_IN_CALL -> Verdict(false, "You are on a phone call")
        f.audioMode == MODE_RINGTONE -> Verdict(false, "Your phone is ringing")
        f.audioMode == MODE_IN_COMMUNICATION -> Verdict(false, "You are on a call in another app")
        f.otherAppsRecording > 0 -> Verdict(false, "Another app is using the microphone")
        else -> Verdict(true, "")
    }

    /** While recording: a call (phone, WeTalks or another app) means the mic is no longer ours. Other apps recording is not checked here. */
    fun interruptReason(f: Facts): String? = when {
        f.wetalksCall -> "A WeTalks call started"
        f.audioMode == MODE_IN_CALL || f.audioMode == MODE_RINGTONE || f.audioMode == MODE_IN_COMMUNICATION -> "A call came in"
        else -> null
    }

    fun facts(ctx: Context, checkOtherApps: Boolean): Facts {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var others = 0
        if (checkOtherApps) try {
            // a silenced client (for example a hotword listener) is not really recording
            others = am.activeRecordingConfigurations.count { c -> Build.VERSION.SDK_INT < 29 || !c.isClientSilenced }
        } catch (_: Throwable) {}
        return Facts(am.mode, Calls.phase != CallPhase.IDLE, others)
    }

    fun check(ctx: Context): Verdict = decide(facts(ctx, checkOtherApps = true))
}

/** Which microphone sources to try, best first: VOICE_RECOGNITION (6), VOICE_COMMUNICATION (7), MIC (1). */
object RecordSource {
    fun order(): List<Int> = listOf(6, 7, 1)
}

/** What to do when the mic is pressed and the microphone permission may be missing. Pure, unit tested. */
object MicPermission {
    enum class Step { GO, ASK, EXPLAIN_THEN_ASK, OPEN_SETTINGS }

    /**
     * @param shouldShowRationale Android's answer; true after one denial
     * @param askedBefore whether this app already showed the system prompt once (Android reports "never asked" and
     * "permanently denied" the same way, shouldShowRationale = false, so the app has to remember)
     */
    fun next(granted: Boolean, shouldShowRationale: Boolean, askedBefore: Boolean): Step = when {
        granted -> Step.GO
        shouldShowRationale -> Step.EXPLAIN_THEN_ASK
        !askedBefore -> Step.ASK
        else -> Step.OPEN_SETTINGS
    }
}
