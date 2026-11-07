package com.kafisir.wetalks.data

import java.util.Calendar
import java.util.TimeZone

/** One line of a person's call history. [st]: ended, missed, declined, noanswer, unavailable, cancelled, busy. */
data class CallEntry(val id: String, val peer: String, val outgoing: Boolean, val video: Boolean, val st: String, val ts: Long, val dur: Int) {
    /** An incoming call this person did not answer. */
    val missed get() = !outgoing && st == "missed"
}

/** Call-history rules as pure functions (no Android), unit tested. */
object CallLog {
    /** What happened, for the person who PLACED the call. [peerRang] = their phone reported ringing, [byMe] = I hung up before they answered. */
    fun outgoingStatus(dur: Int, state: String, peerRang: Boolean, byMe: Boolean): String = when {
        dur > 0 -> "ended"
        state == "declined" -> "declined"
        state == "busy" -> "busy"
        byMe -> "cancelled"
        peerRang -> "noanswer"          // it rang, nobody picked up
        else -> "unavailable"           // it never reached their phone (offline / app closed)
    }

    /** What happened, for the person who RECEIVED the call. */
    fun incomingStatus(dur: Int, state: String): String = when {
        dur > 0 -> "ended"
        state == "declined" -> "declined"
        else -> "missed"                // cancelled by the caller, timed out, or I was busy
    }

    fun duration(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    /** The short line under the name. */
    fun label(e: CallEntry): String = when (e.st) {
        "ended" -> duration(e.dur)
        "missed" -> "Missed"
        "declined" -> "Declined"
        "noanswer" -> "No answer"
        "unavailable" -> "Unavailable"
        "cancelled" -> "Cancelled"
        "busy" -> "Busy"
        else -> e.st
    }

    /** Several calls with the same person that follow each other (same direction, kind, outcome, same day) shown as "Name (3)". */
    class Group(val all: List<CallEntry>) { val first get() = all.first(); val count get() = all.size }

    private fun day(ts: Long, zone: TimeZone): Int { val c = Calendar.getInstance(zone); c.timeInMillis = ts; return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }

    /** [list] newest first. */
    fun group(list: List<CallEntry>, zone: TimeZone = TimeZone.getDefault()): List<Group> {
        val out = ArrayList<MutableList<CallEntry>>()
        for (e in list) {
            val last = out.lastOrNull()?.first()
            if (last != null && last.peer == e.peer && last.outgoing == e.outgoing && last.video == e.video && last.st == e.st && day(last.ts, zone) == day(e.ts, zone)) out.last().add(e) else out.add(mutableListOf(e))
        }
        return out.map { Group(it) }
    }

    /** [missedOnly] = the "Missed" filter; [query] matches the person's name or their uid. */
    fun filter(list: List<CallEntry>, missedOnly: Boolean, query: String, nameOf: (String) -> String): List<CallEntry> {
        val q = query.trim().lowercase()
        return list.filter { (!missedOnly || it.missed) && (q.isEmpty() || nameOf(it.peer).lowercase().contains(q)) }
    }
}
