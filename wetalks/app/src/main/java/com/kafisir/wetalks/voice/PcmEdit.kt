package com.kafisir.wetalks.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Small sample-level helpers for the natural voice. Pure, unit tested. */
object PcmEdit {
    /**
     * The voice model puts a little silence before and after every piece it makes. Played back to back that is the "stop, go, stop, go" gap.
     * This cuts the silence at both ends (keeping a short margin so word edges survive) and fades the edges so joins never click.
     */
    fun trim(x: FloatArray, sr: Int, leadMs: Int = 20, tailMs: Int = 45, fadeInMs: Int = 6, fadeOutMs: Int = 12): FloatArray {
        if (x.isEmpty()) return x
        var peak = 0f; for (v in x) peak = max(peak, abs(v))
        if (peak < 0.002f) return FloatArray(0)
        val thr = max(0.003f, peak * 0.025f)
        var a = 0; while (a < x.size && abs(x[a]) <= thr) a++
        var b = x.size - 1; while (b > a && abs(x[b]) <= thr) b--
        val start = max(0, a - sr * leadMs / 1000); val end = min(x.size, b + 1 + sr * tailMs / 1000)
        val out = x.copyOfRange(start, end)
        val fi = min(out.size / 2, sr * fadeInMs / 1000); val fo = min(out.size / 2, sr * fadeOutMs / 1000)
        for (i in 0 until fi) out[i] *= i / fi.toFloat()
        for (i in 0 until fo) out[out.size - 1 - i] *= i / fo.toFloat()
        return out
    }

    fun concat(parts: List<FloatArray>): FloatArray {
        var n = 0; for (p in parts) n += p.size
        val out = FloatArray(n); var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }
}
