package com.kafisir.wetalks.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shortens long pauses in a voice note. Any silence longer than [minMs] is cut down to [keepMs] (so speech still breathes),
 * leading and trailing silence is trimmed, and every join is cross-faded so nothing clicks. The silence level is measured from the
 * recording itself (noise floor vs. speech level), and a short "hang-over" around speech protects quiet word endings.
 */
object SilenceCut {
    fun cut(x: FloatArray, sr: Int, minMs: Int = 300, keepMs: Int = 120): FloatArray {
        val hop = sr / 100                                        // 10 ms frames
        val n = x.size / hop
        if (n < 20) return x
        val db = FloatArray(n) { f -> var s = 0.0; for (i in f * hop until (f + 1) * hop) s += x[i].toDouble() * x[i]; (20 * log10(sqrt(s / hop) + 1e-9)).toFloat() }
        val sorted = db.sortedArray()
        val floor = sorted[(n * 0.10).toInt()]; val speech = sorted[(n * 0.90).toInt()]
        if (speech - floor < 14f) return x                         // no clear speech/silence contrast: leave it alone
        val thr = (floor + (speech - floor) * 0.28f).coerceIn(-62f, -34f)
        val voiced = BooleanArray(n) { db[it] >= thr }
        // protect word onsets / tails: grow every voiced stretch by 30 ms before and 70 ms after
        val keep = BooleanArray(n)
        for (f in 0 until n) if (voiced[f]) for (k in -3..7) { val j = f + k; if (j in 0 until n) keep[j] = true }

        val out = ArrayList<FloatArray>(); var total = 0
        val fade = (sr * 0.008).toInt()
        val minF = minMs / 10; val keepF = keepMs / 10
        var f = 0; var first = true
        fun emit(from: Int, to: Int) { if (to > from) { val seg = x.copyOfRange(from * hop, min(x.size, to * hop)); out.add(seg); total += seg.size } }
        while (f < n) {
            if (keep[f]) { var e = f; while (e < n && keep[e]) e++; emit(f, e); f = e; first = false }
            else {
                var e = f; while (e < n && !keep[e]) e++
                val len = e - f
                val atStart = f == 0; val atEnd = e == n
                when {
                    atStart -> emit(max(f, e - min(len, 8)), e)                      // leading pause: keep at most 80 ms
                    atEnd -> emit(f, f + min(len, 8))                                // trailing pause: keep at most 80 ms
                    len >= minF -> { val a = keepF / 2; emit(f, f + a); emit(e - (keepF - a), e) }
                    else -> emit(f, e)                                               // a natural short pause stays untouched
                }
                f = e
            }
        }
        if (total == 0) return x
        val res = FloatArray(total); var p = 0
        for (seg in out) { System.arraycopy(seg, 0, res, p, seg.size); p += seg.size }
        // fade out/in around every join so no click can be heard
        var q = 0
        for (i in 0 until out.size - 1) { q += out[i].size; val m = min(fade, min(out[i].size, out[i + 1].size) / 2); for (k in 0 until m) { val g = k / m.toFloat(); res[q - 1 - k] *= g; res[q + k] *= g } }
        return res
    }

    /** 40 bars (2..31) for the voice-note waveform. */
    fun wave(x: FloatArray, n: Int = 40): List<Int> {
        if (x.isEmpty()) return List(n) { 2 }
        val peaks = FloatArray(n) { i -> var m = 0f; for (j in i * x.size / n until max(i * x.size / n + 1, (i + 1) * x.size / n)) m = max(m, kotlin.math.abs(x[j.coerceAtMost(x.size - 1)])); m }
        val top = peaks.max().coerceAtLeast(1e-4f)
        return peaks.map { ((it / top) * 29f + 2f).toInt().coerceIn(2, 31) }
    }
}
