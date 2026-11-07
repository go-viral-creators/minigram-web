package com.kafisir.wetalks.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kafisir.wetalks.data.Notifier
import java.io.File

/**
 * True while a voice message is being recorded. WeTalks' own sounds ([com.kafisir.wetalks.ui.Sfx]) and in-app
 * notification alerts ([Notifier]) check it and stay silent, so they cannot leak into the recording.
 */
object Recording {
    @Volatile var active = false
}

/**
 * Everything around one recording that is not the capture itself: the [RecorderMachine] state, audio focus, Do Not
 * Disturb, the "my own sounds are muted" flag, and the polling for interruptions. The chat screen only calls [fire]
 * and [poll]; [fire] starts and stops the quiet period when the state changes, so it cannot be left on by mistake.
 */
object RecordSession {
    var state by mutableStateOf(RecorderMachine.State.IDLE); private set
    /** A short line shown under the recording bar (for example when other apps' sounds cannot be muted). */
    var hint by mutableStateOf<String?>(null); private set

    class Interruption(val event: RecorderMachine.Event, val why: String)

    @Volatile private var pending: String? = null
    private var focus: RecordFocus? = null
    private var quiet = false

    fun fire(ctx: Context, e: RecorderMachine.Event, seconds: Int): RecorderMachine.State {
        val before = state
        val after = RecorderMachine.next(before, e, seconds)
        state = after
        val app = ctx.applicationContext
        if (RecorderMachine.needsQuiet(after) && !quiet) beginQuiet(app)
        else if (!RecorderMachine.needsQuiet(after) && quiet) endQuiet(app)
        return after
    }

    /** Only for a draft that was thrown away from outside the chat. */
    fun forget(ctx: Context) { state = RecorderMachine.State.IDLE; if (quiet) endQuiet(ctx.applicationContext) }

    private fun beginQuiet(app: Context) {
        quiet = true; pending = null
        // our own playback must not be in the recording
        try { VoicePlayer.pause() } catch (_: Throwable) {}
        try { Speaker.stop() } catch (_: Throwable) {}
        Recording.active = true
        focus = RecordFocus(app) { pending = it }.also { try { it.acquire() } catch (_: Throwable) {} }
        DndAccess.mute(app)
        hint = if (DndAccess.granted(app)) null else "Sounds from other apps may be heard in this recording"
    }

    private fun endQuiet(app: Context) {
        quiet = false; hint = null
        try { focus?.release() } catch (_: Throwable) {}
        focus = null
        DndAccess.restore(app)
        Recording.active = false
        try { Notifier.flushDeferred() } catch (_: Throwable) {}
    }

    /** Polled by the chat screen while capturing: something took the microphone, or the recorder itself failed. */
    fun poll(ctx: Context, r: Recorder): Interruption? {
        if (!RecorderMachine.capturing(state)) return null
        r.failure?.let { r.failure = null; return Interruption(RecorderMachine.Event.FAILED, it) }
        pending?.let { pending = null; return Interruption(RecorderMachine.Event.INTERRUPT, "$it. Your recording is saved as a draft.") }
        RecordGuard.interruptReason(RecordGuard.facts(ctx, checkOtherApps = false))?.let { return Interruption(RecorderMachine.Event.INTERRUPT, "$it. Your recording is saved as a draft.") }
        return null
    }

    /** App start: put Do Not Disturb back if the app died while recording, and delete recordings that were left behind. */
    fun onAppStart(ctx: Context) {
        try { DndAccess.recoverAtStart(ctx) } catch (_: Throwable) {}
        try { ctx.cacheDir.listFiles { f -> f.name.startsWith("rec_") && f.name.endsWith(".pcm") }?.forEach { it.delete() } } catch (_: Throwable) {}
    }
}

/** One unsent recording that outlives the chat screen (the chat was closed or the app went away while it was paused). */
object RecordDraft {
    private var cid: String? = null
    private var rec: Recorder? = null

    val chatId: String? get() = cid

    fun park(chatId: String, r: Recorder) { if (rec !== r) rec?.cancel(); cid = chatId; rec = r }
    fun take(chatId: String): Recorder? = if (cid == chatId) rec.also { rec = null; cid = null } else null
    fun discard(ctx: Context) { rec?.cancel(); rec = null; cid = null; RecordSession.forget(ctx) }
}
