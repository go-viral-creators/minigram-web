package com.kafisir.wetalks.data

/**
 * SDP helpers for calls, as pure functions (no Android, unit tested).
 *
 * [tuneOpus] rewrites the Opus `a=fmtp` line so a voice call survives bad networks:
 *  - useinbandfec=1   the sender adds forward error correction, a lost packet can be rebuilt from the next one
 *  - usedtx=1         silence is not sent (saves bandwidth, less to lose)
 *  - stereo=0 / sprop-stereo=0   mono voice, half the bits of stereo
 *  - maxaveragebitrate           cap of the average rate (never raised above a lower value the other side asked for)
 *  - minptime=10      shortest packet time
 * The same function is used for the offer, the answer and every renegotiation (ICE restart, video on), and running it twice changes nothing.
 */
object SdpTools {
    const val OPUS_MAX_AVG_BPS = 32_000

    private val OPUS_MAP = Regex("^a=rtpmap:(\\d+)\\s+opus/48000(?:/2)?\\s*$", RegexOption.IGNORE_CASE)
    private val FMTP = Regex("^a=fmtp:(\\d+)\\s+(.*)$")

    /** The tuned parameters, in a fixed order so the output is stable. */
    private fun wanted(maxAvg: Int): List<Pair<String, String>> = listOf(
        "minptime" to "10", "useinbandfec" to "1", "usedtx" to "1", "stereo" to "0", "sprop-stereo" to "0", "maxaveragebitrate" to maxAvg.toString(),
    )

    fun tuneOpus(sdp: String, maxAvgBps: Int = OPUS_MAX_AVG_BPS): String {
        if (sdp.isEmpty()) return sdp
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol)
        val opus = HashSet<String>()
        for (l in lines) OPUS_MAP.matchEntire(l)?.let { opus += it.groupValues[1] }
        if (opus.isEmpty()) return sdp
        val hasFmtp = HashSet<String>()
        for (l in lines) FMTP.matchEntire(l)?.let { if (it.groupValues[1] in opus) hasFmtp += it.groupValues[1] }
        val out = ArrayList<String>(lines.size + 2)
        for (l in lines) {
            val f = FMTP.matchEntire(l)
            if (f != null && f.groupValues[1] in opus) {
                out += "a=fmtp:" + f.groupValues[1] + " " + merge(f.groupValues[2], maxAvgBps)
                continue
            }
            out += l
            val m = OPUS_MAP.matchEntire(l)
            if (m != null && m.groupValues[1] !in hasFmtp) out += "a=fmtp:" + m.groupValues[1] + " " + merge("", maxAvgBps)
        }
        return out.joinToString(eol)
    }

    /** Existing parameters stay (in their order); ours are set or added. A lower maxaveragebitrate already in the line wins. */
    private fun merge(existing: String, maxAvg: Int): String {
        val map = LinkedHashMap<String, String?>()
        for (part in existing.split(';')) {
            val p = part.trim(); if (p.isEmpty()) continue
            val i = p.indexOf('=')
            if (i < 0) map[p] = null else map[p.substring(0, i).trim()] = p.substring(i + 1).trim()
        }
        for ((k, v) in wanted(maxAvg)) {
            if (k == "maxaveragebitrate") {
                val old = map[k]?.toIntOrNull()
                map[k] = (if (old != null && old in 1 until maxAvg) old else maxAvg).toString()
            } else map[k] = v
        }
        return map.entries.joinToString(";") { if (it.value == null) it.key else it.key + "=" + it.value }
    }

    /** True when the SDP has an Opus audio line (used by tests and logs). */
    fun hasOpus(sdp: String) = sdp.lineSequence().any { OPUS_MAP.matches(it.trimEnd('\r')) }
}
