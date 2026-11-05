package com.kafisir.wetalks.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.log10
import kotlin.math.sqrt

class DfnStreamTest {
    private fun model(n: String) = File("src/main/assets/dfn3/$n").readBytes()
    private fun rms(a: FloatArray, s: Int, e: Int): Double { var x = 0.0; for (i in s until e) x += a[i] * a[i].toDouble(); return sqrt(x / maxOf(1, e - s)) + 1e-12 }
    private fun snr(c: FloatArray, y: FloatArray): Double { var s = 0.0; var e = 0.0; for (i in c.indices) { s += c[i].toDouble() * c[i]; val d = (y[i] - c[i]).toDouble(); e += d * d }; return 10 * log10(s / (e + 1e-12)) }
    private fun db(v: Double) = 20 * log10(v)

    private fun signal(n: Int, sr: Int): Pair<FloatArray, FloatArray> {
        val clean = FloatArray(n); val noisy = FloatArray(n); val rnd = Random(0); var ph = 0.0
        for (i in 0 until n) {
            val t = i / sr.toDouble(); val f0 = 120 + 30 * Math.sin(2 * Math.PI * .7 * t)
            ph += 2 * Math.PI * f0 / sr
            var s = 0.0; for (h in 1 until 30) s += Math.sin(h * ph) / h
            val env = (if (Math.sin(2 * Math.PI * 2.5 * t) > .2) 1 else 0) * (.5 + .5 * Math.sin(2 * Math.PI * .9 * t))
            clean[i] = (.25 * s * env).toFloat(); noisy[i] = clean[i] + (.05 * rnd.nextGaussian()).toFloat()
        }
        return clean to noisy
    }

    @Test fun streamingMatchesOfflineReference() {
        val sr = 48000; val n = sr * 5 + 321
        val (clean, noisy) = signal(n, sr)
        val ref = DeepFilterNetRef(model("enc.onnx"), model("erb_dec.onnx"), model("df_dec.onnx")).process(noisy)

        val eng = DfnEngine(model("enc.onnx"), model("erb_dec.onnx"), model("df_dec.onnx"))
        println("noisy snr=${snr(clean, noisy)} dB, offline reference snr=${snr(clean, ref)} dB")
        val st = DfnStream(eng)
        var p = 0                                   // odd chunk sizes, like a real microphone
        val sizes = intArrayOf(960, 777, 1920, 333)
        var k = 0
        while (p < n) { val c = minOf(sizes[k++ % sizes.size], n - p); st.feed(noisy.copyOfRange(p, p + c)); p += c }
        val t0 = System.nanoTime()
        val out = st.finish()
        val tailMs = (System.nanoTime() - t0) / 1e6
        assertEquals(n, out.size)

        var num = 0.0; var den = 0.0
        for (i in 0 until n) { val d = (out[i] - ref[i]).toDouble(); num += d * d; den += ref[i].toDouble() * ref[i] }
        val relErr = sqrt(num / den)
        println("stream vs offline relative error: $relErr, tail flush ${"%.0f".format(tailMs)} ms")
        val streamSnr = snr(clean, out)
        println("streaming snr=$streamSnr dB")
        assertTrue("streaming should clean the signal (noisy ${snr(clean, noisy)} dB, got $streamSnr dB)", streamSnr > snr(clean, noisy) + 5)
        assertTrue("streaming drifted from the reference: $relErr", relErr < 0.35)

        // noise really is removed in a quiet stretch
        var qs = -1
        var i = 0
        while (i < n - sr / 5) { var quiet = true; for (j in i until i + sr / 5) if (Math.abs(clean[j]) > 1e-6) { quiet = false; break }; if (quiet) { qs = i; break }; i += sr / 10 }
        assertTrue(qs >= 0)
        val drop = db(rms(noisy, qs, qs + sr / 5)) - db(rms(out, qs, qs + sr / 5))
        println("noise drop in a silent stretch: $drop dB")
        assertTrue("noise should drop by >12 dB, was $drop", drop > 12)
        eng.close()
    }
}
