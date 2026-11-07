package com.kafisir.wetalks.data

/** Call network quality as pure logic (no Android, unit tested): stats -> sample -> smoothed level -> decisions. */

/** Signal bars on the call screen. */
enum class NetLevel(val bars: Int) { POOR(1), FAIR(2), GOOD(3), EXCELLENT(4) }

/** One measurement. Every field is null when the stats did not have it yet. rtt/jitter in ms, loss in percent, available outgoing bitrate in kbit/s. */
data class NetSample(val rttMs: Double?, val lossPct: Double?, val jitterMs: Double?, val availKbps: Double?)

/** What to put on the RtpSenders. */
data class SendCaps(val videoMaxBps: Int, val videoScaleDown: Double, val videoFps: Int, val audioMaxBps: Int)

enum class VideoAction { NONE, PAUSE, RESUME }

data class QualityUpdate(val level: NetLevel, val levelChanged: Boolean, val video: VideoAction, val caps: SendCaps, val capsChanged: Boolean, val videoPaused: Boolean)

object CallQuality {
    /** Level of ONE sample from absolute thresholds. [useAvail] = also judge the bitrate estimate (only meaningful while video is being sent). */
    fun classify(s: NetSample, useAvail: Boolean): NetLevel? {
        var worst: NetLevel? = null
        fun take(l: NetLevel) { if (worst == null || l.bars < worst!!.bars) worst = l }
        s.rttMs?.let { take(when { it < 150 -> NetLevel.EXCELLENT; it < 300 -> NetLevel.GOOD; it < 600 -> NetLevel.FAIR; else -> NetLevel.POOR }) }
        s.lossPct?.let { take(when { it < 1.0 -> NetLevel.EXCELLENT; it < 3.0 -> NetLevel.GOOD; it < 8.0 -> NetLevel.FAIR; else -> NetLevel.POOR }) }
        s.jitterMs?.let { take(when { it < 20 -> NetLevel.EXCELLENT; it < 40 -> NetLevel.GOOD; it < 80 -> NetLevel.FAIR; else -> NetLevel.POOR }) }
        if (useAvail) s.availKbps?.let { take(when { it < 80 -> NetLevel.POOR; it < 200 -> NetLevel.FAIR; it < 500 -> NetLevel.GOOD; else -> NetLevel.EXCELLENT }) }
        return worst
    }

    /** Sender limits for a level. Audio is never starved: it keeps its own small budget, video gets what the estimate leaves. */
    fun caps(level: NetLevel, availKbps: Double?): SendCaps {
        val base = when (level) {
            NetLevel.EXCELLENT -> SendCaps(1_200_000, 1.0, 24, 32_000)
            NetLevel.GOOD -> SendCaps(600_000, 1.5, 20, 32_000)
            NetLevel.FAIR -> SendCaps(250_000, 2.0, 15, 24_000)
            NetLevel.POOR -> SendCaps(80_000, 3.0, 10, 20_000)
        }
        if (availKbps == null || availKbps <= 0) return base
        val room = (availKbps * 1000 * 0.75).toInt() - base.audioMaxBps
        return base.copy(videoMaxBps = minOf(base.videoMaxBps, maxOf(50_000, room)))
    }
}

/** Tunable numbers of [QualityMonitor]. One stats sample every ~2 s, so "3 samples" is about 6 s. */
data class QualityCfg(
    val alpha: Double = 0.5,         // smoothing of the metrics (higher = follows the newest sample more)
    val downAfter: Int = 2,          // the shown level drops after this many worse samples in a row
    val upAfter: Int = 3,            // ... and rises after this many better samples in a row
    val dropVideoAfter: Int = 3,     // bad samples in a row before video is paused
    val restoreVideoAfter: Int = 8,  // good samples in a row before video comes back
    val restoreMax: Int = 30,        // the restore wait doubles each time video is dropped again soon after a restore, up to this
    val flapWindow: Int = 30,        // "soon" = within this many samples
)

/**
 * Turns the raw stat samples into: a calm level for the signal bars (hysteresis), sender limits, and the decision to pause / resume video.
 * Feed one sample per stats round. Not thread-safe: call it from one place (the link holds a lock).
 */
class QualityMonitor(private val cfg: QualityCfg = QualityCfg()) {
    private var rtt: Double? = null; private var loss: Double? = null; private var jit: Double? = null; private var avail: Double? = null
    var level: NetLevel? = null; private set
    var videoPaused = false; private set
    private var downN = 0; private var downTarget = NetLevel.EXCELLENT
    private var upN = 0; private var upTarget = NetLevel.POOR
    private var badN = 0; private var goodN = 0
    private var lastCaps: SendCaps? = null
    private var restoreNeed = cfg.restoreVideoAfter
    private var sinceRestore = Int.MAX_VALUE
    private var samples = 0

    private fun ewma(old: Double?, v: Double?): Double? = if (v == null) old else if (old == null) v else old + cfg.alpha * (v - old)

    fun feed(s: NetSample, videoSending: Boolean): QualityUpdate {
        samples++
        if (sinceRestore < Int.MAX_VALUE) sinceRestore++
        rtt = ewma(rtt, s.rttMs); loss = ewma(loss, s.lossPct); jit = ewma(jit, s.jitterMs); avail = ewma(avail, s.availKbps)
        val sm = NetSample(rtt, loss, jit, avail)
        val raw = CallQuality.classify(sm, useAvail = videoSending && !videoPaused)
        var changed = false
        val cur = level
        if (raw != null) {
            if (cur == null) { level = raw; changed = true }
            else if (raw.bars < cur.bars) {
                downN++; upN = 0; if (downN == 1 || raw.bars > downTarget.bars) downTarget = raw
                if (downN == 1) downTarget = raw
                if (downN >= cfg.downAfter) { level = downTarget; changed = true; downN = 0 }
            } else if (raw.bars > cur.bars) {
                upN++; downN = 0; if (upN == 1 || raw.bars < upTarget.bars) upTarget = raw
                if (upN >= cfg.upAfter) { level = upTarget; changed = true; upN = 0 }
            } else { downN = 0; upN = 0 }
        }

        // video pause / resume
        var action = VideoAction.NONE
        if (videoSending || videoPaused) {
            if (!videoPaused) {
                if (raw == NetLevel.POOR) badN++ else badN = 0
                if (badN >= cfg.dropVideoAfter) {
                    videoPaused = true; action = VideoAction.PAUSE; badN = 0; goodN = 0
                    // dropped again soon after a restore: be slower to restore next time
                    restoreNeed = if (sinceRestore <= cfg.flapWindow) minOf(cfg.restoreMax, restoreNeed * 2) else cfg.restoreVideoAfter
                }
            } else {
                // paused: the bitrate estimate is stale while nothing is sent, so judge by delay, loss and jitter only
                val audioOnly = CallQuality.classify(sm, useAvail = false)
                if (audioOnly != null && audioOnly.bars >= NetLevel.GOOD.bars) goodN++ else goodN = 0
                if (goodN >= restoreNeed) { videoPaused = false; action = VideoAction.RESUME; goodN = 0; badN = 0; sinceRestore = 0 }
            }
        } else { badN = 0; goodN = 0 }

        val lv = level ?: NetLevel.GOOD
        val caps = CallQuality.caps(lv, if (videoPaused) null else avail)
        val capsChanged = caps != lastCaps
        lastCaps = caps
        return QualityUpdate(lv, changed, action, caps, capsChanged, videoPaused)
    }

    /** The video sender went away or the person switched the camera: forget a pause that no longer applies. */
    fun resetVideo() { videoPaused = false; badN = 0; goodN = 0 }
}

/** One row of a WebRTC stats report (type like "inbound-rtp", members as delivered by the library). */
data class StatRow(val type: String, val id: String, val m: Map<String, Any?>)

/**
 * Reads `PeerConnection.getStats` rows into a [NetSample]. Keeps the previous packet counters so loss is measured over the last round, not since the call began.
 */
class StatsReader {
    private var prevRecv = 0.0; private var prevLost = 0.0; private var havePrev = false

    private fun num(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }

    fun read(rows: List<StatRow>): NetSample {
        var rtt: Double? = null; var jitter: Double? = null; var avail: Double? = null
        var recv = 0.0; var lost = 0.0; var haveIn = false; var remoteLoss: Double? = null
        val selected = rows.firstOrNull { it.type == "transport" }?.m?.get("selectedCandidatePairId") as? String
        val pair = rows.firstOrNull { it.type == "candidate-pair" && selected != null && it.id == selected }
            ?: rows.firstOrNull { it.type == "candidate-pair" && it.m["nominated"] == true && it.m["state"] == "succeeded" }
            ?: rows.firstOrNull { it.type == "candidate-pair" && it.m["state"] == "succeeded" }
        if (pair != null) {
            num(pair.m["currentRoundTripTime"])?.let { if (it > 0) rtt = it * 1000 }
            num(pair.m["availableOutgoingBitrate"])?.let { if (it > 0) avail = it / 1000 }
        }
        for (r in rows) {
            val kind = (r.m["kind"] ?: r.m["mediaType"]) as? String
            when (r.type) {
                "inbound-rtp" -> if (kind == "audio" || kind == null) {
                    haveIn = true
                    recv += num(r.m["packetsReceived"]) ?: 0.0; lost += num(r.m["packetsLost"]) ?: 0.0
                    num(r.m["jitter"])?.let { jitter = maxOf(jitter ?: 0.0, it * 1000) }
                }
                "remote-inbound-rtp" -> if (kind == "audio" || kind == null) {
                    num(r.m["roundTripTime"])?.let { if (rtt == null && it > 0) rtt = it * 1000 }
                    num(r.m["fractionLost"])?.let { remoteLoss = maxOf(remoteLoss ?: 0.0, it * 100) }
                    num(r.m["jitter"])?.let { jitter = maxOf(jitter ?: 0.0, it * 1000) }
                }
            }
        }
        var loss: Double? = remoteLoss
        if (haveIn) {
            if (havePrev) {
                val dr = (recv - prevRecv).coerceAtLeast(0.0); val dl = (lost - prevLost).coerceAtLeast(0.0)
                if (dr + dl >= 5) loss = maxOf(loss ?: 0.0, dl / (dr + dl) * 100)
            }
            prevRecv = recv; prevLost = lost; havePrev = true
        }
        return NetSample(rtt, loss, jitter, avail)
    }
}

/** Extra STUN/TURN servers from `config/turn` in the database (the app does not ship credentials). Pure parsing + checks, unit tested. */
data class IceSpec(val urls: List<String>, val username: String?, val credential: String?)

object IceConfig {
    private val ok = Regex("^(stun|stuns|turn|turns):[^\\s,]+$", RegexOption.IGNORE_CASE)

    /** Accepts `urls` as one string (comma separated) or a list; skips bad entries; at most 6 servers; turn(s) needs a username and credential. */
    fun parse(raw: List<Map<String, Any?>>): List<IceSpec> {
        val out = ArrayList<IceSpec>()
        for (e in raw) {
            if (out.size >= 6) break
            val u = e["urls"] ?: e["url"]
            val urls = when (u) { is String -> u.split(','); is List<*> -> u.filterIsInstance<String>(); else -> emptyList() }
                .map { it.trim() }.filter { it.length <= 200 && ok.matches(it) }.take(6)
            if (urls.isEmpty()) continue
            val user = (e["username"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 200 }
            val cred = (e["credential"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 400 }
            val needsAuth = urls.any { it.startsWith("turn", ignoreCase = true) }
            if (needsAuth && (user == null || cred == null)) continue
            out += IceSpec(urls, user, cred)
        }
        return out
    }
}
