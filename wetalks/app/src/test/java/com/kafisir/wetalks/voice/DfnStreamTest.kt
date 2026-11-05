package com.kafisir.wetalks.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.log10
import kotlin.math.sqrt

class DfnStreamTest {
    private fun model(n: String) = File("src/test/models/$n").readBytes()
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

class OrtFormatTest {
    private fun bytes(p: String) = java.io.File(p).readBytes()
    @Test fun ortModelsMatchTheOnnxOriginals() {
        val onnx = DfnEngine(bytes("src/test/models/enc.onnx"), bytes("src/test/models/erb_dec.onnx"), bytes("src/test/models/df_dec.onnx"))
        val ort = DfnEngine(bytes("src/main/assets/dfn3/enc.ort"), bytes("src/main/assets/dfn3/erb_dec.ort"), bytes("src/main/assets/dfn3/df_dec.ort"))
        val rnd = java.util.Random(5); val n = 48000 * 3
        val x = FloatArray(n) { (0.2 * Math.sin(it * 0.05) * (if ((it / 12000) % 2 == 0) 1 else 0) + 0.04 * rnd.nextGaussian()).toFloat() }
        fun run(e: DfnEngine): FloatArray { val s = DfnStream(e); var p = 0; while (p < n) { val c = minOf(1920, n - p); s.feed(x.copyOfRange(p, p + c)); p += c }; return s.finish() }
        val a = run(onnx); val b = run(ort)
        var num = 0.0; var den = 0.0; for (i in a.indices) { val d = (a[i] - b[i]).toDouble(); num += d * d; den += a[i].toDouble() * a[i] }
        println("onnx vs ort relative difference: ${Math.sqrt(num / den)}")
        assertTrue("ORT-format models differ from the originals", Math.sqrt(num / den) < 0.01)
    }
}

class SilenceCutTest {
    private val sr = 48000
    private fun burst(ms: Int, f: Double = 220.0) = FloatArray(sr * ms / 1000) { (0.3 * Math.sin(2 * Math.PI * f * it / sr) * (0.6 + 0.4 * Math.sin(it * 0.002))).toFloat() }
    private fun quiet(ms: Int) = FloatArray(sr * ms / 1000) { (0.0005 * Math.sin(it * 0.37)).toFloat() }
    @Test fun longPausesAreShortenedAndShortOnesStay() {
        val x = quiet(900) + burst(700) + quiet(1000) + burst(600) + quiet(150) + burst(500) + quiet(2000)
        val y = SilenceCut.cut(x, sr)
        val before = x.size / sr.toDouble(); val after = y.size / sr.toDouble()
        println("silence cut: %.2fs -> %.2fs".format(before, after))
        // 900 lead -> <=80, 1000 pause -> ~120, 150 pause untouched, 2000 tail -> <=80 : about 1.85 s removed or more
        assertTrue("expected a big reduction, got $after of $before", after < before - 3.0)
        assertTrue("speech must survive", after > 1.8 + 0.05)
        // the speech itself (peak) is intact
        assertTrue(y.maxOrNull()!! > 0.25f)
    }
    @Test fun audioWithoutClearSilenceIsLeftAlone() {
        val x = burst(3000)
        assertEquals(x.size, SilenceCut.cut(x, sr).size)
    }
    @Test fun waveHasFortyBars() { assertEquals(40, SilenceCut.wave(burst(1000)).size) }
}
