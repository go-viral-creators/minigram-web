package com.kafisir.wetalks.voice

/**
 * The life of one voice recording as a pure state machine (no Android classes, so it is unit tested on the JVM).
 *
 *  IDLE         nothing is recording.
 *  RECORDING    the mic button is held down.
 *  LOCKED       slid up to lock: hands-free, with a send button and a bin.
 *  PAUSED       the recording was paused (a call, an alarm, lost audio focus, unplugged headset, app in the background, ...).
 *               The audio so far is kept as a draft that the person can resume, send or delete.
 *  SAVED_DRAFT  the chat was closed while a draft existed; the audio is parked and comes back when the chat is opened again.
 *  CANCELLED    thrown away (swipe left, bin, too short, nothing worth keeping).
 */
object RecorderMachine {
    enum class State { IDLE, RECORDING, LOCKED, PAUSED, SAVED_DRAFT, CANCELLED }
    enum class Event {
        PRESS, LOCK, RELEASE, SLIDE_CANCEL, TRASH, SEND, USER_PAUSE, USER_RESUME,
        INTERRUPT, BACKGROUND, CLOSE_CHAT, OPEN_CHAT, TIME_LIMIT, FAILED
    }

    /** Shortest recording worth sending, in seconds. */
    const val MIN_SECONDS = 1

    /**
     * @param seconds how much audio has been captured so far
     * @return the next state. Events that make no sense in a state leave it unchanged.
     */
    fun next(s: State, e: Event, seconds: Int): State {
        val keep = seconds >= MIN_SECONDS
        return when (s) {
            State.IDLE, State.CANCELLED -> if (e == Event.PRESS) State.RECORDING else s
            State.RECORDING -> when (e) {
                Event.LOCK -> State.LOCKED
                Event.RELEASE, Event.TIME_LIMIT -> if (keep) State.IDLE else State.CANCELLED
                Event.SLIDE_CANCEL, Event.TRASH -> State.CANCELLED
                // the finger may still be down but the mic is gone: keep what we have so nothing is lost
                Event.INTERRUPT, Event.BACKGROUND, Event.FAILED -> if (keep) State.PAUSED else State.CANCELLED
                Event.CLOSE_CHAT -> if (keep) State.SAVED_DRAFT else State.CANCELLED
                else -> s
            }
            State.LOCKED -> when (e) {
                Event.SEND, Event.TIME_LIMIT -> if (keep) State.IDLE else State.CANCELLED
                Event.TRASH, Event.SLIDE_CANCEL -> State.CANCELLED
                Event.INTERRUPT, Event.BACKGROUND, Event.FAILED, Event.USER_PAUSE -> if (keep) State.PAUSED else State.CANCELLED
                Event.CLOSE_CHAT -> if (keep) State.SAVED_DRAFT else State.CANCELLED
                else -> s
            }
            State.PAUSED -> when (e) {
                Event.USER_RESUME -> State.LOCKED
                Event.SEND -> if (keep) State.IDLE else State.CANCELLED
                Event.TRASH -> State.CANCELLED
                Event.CLOSE_CHAT -> if (keep) State.SAVED_DRAFT else State.CANCELLED
                else -> s
            }
            State.SAVED_DRAFT -> when (e) {
                Event.OPEN_CHAT -> State.PAUSED
                Event.TRASH -> State.CANCELLED
                Event.SEND -> State.IDLE
                else -> s
            }
        }
    }

    /** The microphone is open and audio is being captured. */
    fun capturing(s: State) = s == State.RECORDING || s == State.LOCKED
    /** A recorder object (and its audio) exists. */
    fun holdsAudio(s: State) = s == State.RECORDING || s == State.LOCKED || s == State.PAUSED || s == State.SAVED_DRAFT
    /** Other media should be paused, sounds muted and Do Not Disturb applied. */
    fun needsQuiet(s: State) = capturing(s)
    /** The recording bar (timer, bin, send) is visible. */
    fun showsBar(s: State) = s == State.RECORDING || s == State.LOCKED || s == State.PAUSED
}
