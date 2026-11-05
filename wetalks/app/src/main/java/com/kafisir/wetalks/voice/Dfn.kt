package com.kafisir.wetalks.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Growable float buffer with cheap "drop from the front". */
internal class FList {
    var a = FloatArray(1 shl 14); var n = 0
    fun add(src: FloatArray, len: Int = src.size) { ensure(n + len); System.arraycopy(src, 0, a, n, len); n += len }
    fun add(v: Float) { ensure(n + 1); a[n++] = v }
    private fun ensure(cap: Int) { if (cap > a.size) a = a.copyOf(max(cap, a.size * 2)) }
    fun dropFirst(k: Int) { if (k <= 0) return; val kk = min(k, n); System.arraycopy(a, kk, a, 0, n - kk); n -= kk }
}

/** The three DeepFilterNet3 networks on ONNX Runtime. Created once; each recording gets its own [DfnStream]. */
class DfnEngine(encModel: ByteArray, erbModel: ByteArray, dfModel: ByteArray) {
    internal val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    internal val enc: OrtSession; internal val erbDec: OrtSession; internal val dfDec: OrtSession
    init {
        val o = OrtSession.SessionOptions(); o.setIntraOpNumThreads(2)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)        // the .ort models are already optimised; the minimal runtime has no optimizer
        o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        enc = env.createSession(encModel, o); erbDec = env.createSession(erbModel, o); dfDec = env.createSession(dfModel, o)
    }
    fun close() { try { enc.close(); erbDec.close(); dfDec.close() } catch (_: Exception) {} }
}

/**
 * DeepFilterNet3 (Schroeter et al.) as a streaming enhancer for 48 kHz mono audio: feed() takes microphone chunks as they
 * arrive and a segment is denoised every ~0.5 s while the user is still talking, so only a short tail is left when they let go.
 * The maths is identical to the offline reference (48 kHz, 960-point STFT / 480 hop, 32 ERB bands + 96 deep-filter bins,
 * mask + 5-tap complex filter, 2 frames of look-ahead); the running normalisers and the overlap-add tail carry across segments.
 */
class DfnStream(private val eng: DfnEngine, private val SEG: Int = DEF_SEG, private val CTX: Int = DEF_CTX) {
    companion object {
        const val SR = 48000; const val FFT = 960; const val HOP = 480; const val NF = FFT / 2 + 1
        const val NB_ERB = 32; const val NB_DF = 96; const val ORDER = 5; const val LA = 2
        const val DEF_SEG = 200; const val DEF_CTX = 300                // frames per segment / warm-up frames
        private const val MIN_DB = -10f; private const val MAX_ERB_DB = 30f; private const val MAX_DF_DB = 20f

        private fun freq2erb(f: Double) = 9.265 * ln(1 + f / (24.7 * 9.265))
        private fun erb2freq(n: Double) = 24.7 * 9.265 * (exp(n / 9.265) - 1)
        fun erbBands(): IntArray {
            val fw = SR.toDouble() / FFT; val lo = freq2erb(0.0); val hi = freq2erb(SR / 2.0); val step = (hi - lo) / NB_ERB
            val e = IntArray(NB_ERB); var prev = 0; var over = 0; val minF = 2
            for (i in 1..NB_ERB) {
                val fb = Math.round(erb2freq(lo + i * step) / fw).toInt()
                var n = fb - prev - over
                if (n < minF) { over = minF - n; n = minF } else over = 0
                e[i - 1] = n; prev = fb
            }
            e[NB_ERB - 1] += 1
            val sum = e.sum(); if (sum > NF) e[NB_ERB - 1] -= sum - NF
            return e
        }
    }

    private val erb = erbBands()
    private val window = DoubleArray(FFT) { i -> val s = sin(0.5 * Math.PI * (i + 0.5) / (FFT / 2)); sin(0.5 * Math.PI * s * s) }
    private val wnorm = 1.0 / (FFT.toDouble() * FFT / (2 * HOP))
    private val alpha = exp(-HOP.toDouble() / SR / 1.0)
    private val fft = Fft(FFT)
    private val br = DoubleArray(FFT); private val bi = DoubleArray(FFT)

    private val inp = FList(); private var inBase = 0; private var totalIn = 0; private var nReal = 0
    private val fErb = FList(); private val fSpec = FList(); private var featFrames = 0; private var featBase = 0
    private val meanState = FloatArray(NB_ERB) { -60f + (-90f + 60f) * it / (NB_ERB - 1) }
    private val unitState = FloatArray(NB_DF) { 0.001f + (0.0001f - 0.001f) * it / (NB_DF - 1) }
    private var nextOut = 0
    private val out = FList()
    private val smem = DoubleArray(FFT - HOP)
    private val frameOut = DoubleArray(FFT)
    private var finalFrames = -1

    fun feed(x: FloatArray, n: Int = x.size) { inp.add(x, n); totalIn += n; nReal += n; pump(false) }

    /** Flushes the tail and returns the whole enhanced clip (same length as what was fed). */
    fun finish(): FloatArray {
        val frames = (nReal + HOP - 1) / HOP + LA + 2
        finalFrames = frames
        val pad = frames * HOP - totalIn
        if (pad > 0) { inp.add(FloatArray(pad)); totalIn += pad }
        pump(true)
        val res = FloatArray(nReal)
        System.arraycopy(out.a, HOP, res, 0, min(nReal, out.n - HOP))        // synthesis is one hop late
        return res
    }

    private fun pump(final: Boolean) {
        val maxFrames = if (final) finalFrames else Int.MAX_VALUE
        while (featFrames < maxFrames && (featFrames + 1).toLong() * HOP <= totalIn) { featurize(featFrames); featFrames++ }
        val limit = if (final) finalFrames - LA else featFrames - LA
        while (nextOut < limit && (final || limit - nextOut >= SEG)) {
            val b = min(limit, nextOut + SEG)
            segment(nextOut, b); nextOut = b; trim()
        }
    }

    private fun trim() {
        val newFeat = max(0, nextOut - CTX - 1)
        if (newFeat > featBase) { fErb.dropFirst((newFeat - featBase) * NB_ERB); fSpec.dropFirst((newFeat - featBase) * 2 * NB_DF); featBase = newFeat }
        val newIn = max(0, (nextOut - 6) * HOP)
        if (newIn > inBase) { inp.dropFirst(newIn - inBase); inBase = newIn }
    }

    private fun stft(k: Int, re: FloatArray, im: FloatArray) {
        val start = (k - 1) * HOP
        for (i in 0 until FFT) {
            val idx = start + i; val loc = idx - inBase
            br[i] = if (idx >= 0 && loc >= 0 && loc < inp.n) inp.a[loc] * window[i] else 0.0
            bi[i] = 0.0
        }
        fft.forward(br, bi)
        for (f in 0 until NF) { re[f] = (br[f] * wnorm).toFloat(); im[f] = (bi[f] * wnorm).toFloat() }
    }

    private fun istft(re: FloatArray, im: FloatArray, o: DoubleArray) {
        for (f in 0 until NF) { br[f] = re[f].toDouble(); bi[f] = -im[f].toDouble() }
        for (f in 1 until FFT / 2) { br[FFT - f] = re[f].toDouble(); bi[FFT - f] = im[f].toDouble() }
        fft.forward(br, bi)
        for (i in 0 until FFT) o[i] = br[i] * window[i]
    }

    private val fre = FloatArray(NF); private val fim = FloatArray(NF)
    private fun featurize(k: Int) {
        stft(k, fre, fim)
        var o = 0
        for (b in 0 until NB_ERB) {
            var s = 0.0
            for (j in 0 until erb[b]) s += (fre[o + j] * fre[o + j] + fim[o + j] * fim[o + j]).toDouble()
            val e = (log10(s / erb[b] + 1e-10) * 10.0).toFloat()
            meanState[b] = (e * (1 - alpha) + meanState[b] * alpha).toFloat()
            fErb.add((e - meanState[b]) / 40f)
            o += erb[b]
        }
        val spec = FloatArray(2 * NB_DF)
        for (f in 0 until NB_DF) {
            val mag = hypot(fre[f].toDouble(), fim[f].toDouble()).toFloat()
            unitState[f] = (mag * (1 - alpha) + unitState[f] * alpha).toFloat()
            val d = sqrt(unitState[f].toDouble()).toFloat()
            spec[f] = fre[f] / d; spec[NB_DF + f] = fim[f] / d
        }
        fSpec.add(spec)
    }

    private fun segment(a: Int, b: Int) {
        val ctx = min(CTX, a - featBase)
        val t0 = a - ctx; val t1 = b + LA; val s = t1 - t0
        val fe = FloatArray(s * NB_ERB); val fs = FloatArray(s * 2 * NB_DF)
        System.arraycopy(fErb.a, (t0 - featBase) * NB_ERB, fe, 0, s * NB_ERB)
        for (t in 0 until s) {
            val src = (t0 + t - featBase) * 2 * NB_DF
            System.arraycopy(fSpec.a, src, fs, t * NB_DF, NB_DF)                       // real  [1,2,S,96]
            System.arraycopy(fSpec.a, src + NB_DF, fs, (s + t) * NB_DF, NB_DF)        // imag
        }
        val mask = Array(s) { FloatArray(NB_ERB) }
        val lsnr = FloatArray(s)
        var coefs = FloatArray(0)
        val env = eng.env
        VoiceFx.guardStart()
        val tErb = OnnxTensor.createTensor(env, FloatBuffer.wrap(fe), longArrayOf(1, 1, s.toLong(), NB_ERB.toLong()))
        val tSpec = OnnxTensor.createTensor(env, FloatBuffer.wrap(fs), longArrayOf(1, 2, s.toLong(), NB_DF.toLong()))
        try {
            eng.enc.run(mapOf("feat_erb" to tErb, "feat_spec" to tSpec)).use { r ->
                val emb = r.get("emb").get() as OnnxTensor
                val din = mapOf("emb" to emb, "e3" to r.get("e3").get() as OnnxTensor, "e2" to r.get("e2").get() as OnnxTensor, "e1" to r.get("e1").get() as OnnxTensor, "e0" to r.get("e0").get() as OnnxTensor)
                (r.get("lsnr").get() as OnnxTensor).floatBuffer.get(lsnr)
                eng.erbDec.run(din).use { m -> val mb = (m.get(0) as OnnxTensor).floatBuffer; for (t in 0 until s) mb.get(mask[t]) }
                eng.dfDec.run(mapOf("emb" to emb, "c0" to r.get("c0").get() as OnnxTensor)).use { c ->
                    coefs = FloatArray(s * NB_DF * ORDER * 2); (c.get(0) as OnnxTensor).floatBuffer.get(coefs)
                }
            }
        } finally { tErb.close(); tSpec.close() }
        VoiceFx.guardEnd()

        val sp0 = a - 2; val sp1 = b + 2; val sn = sp1 - sp0
        val sre = Array(sn) { FloatArray(NF) }; val sim = Array(sn) { FloatArray(NF) }
        for (q in 0 until sn) { val k = sp0 + q; if (k >= 0 && k < featFrames) stft(k, sre[q], sim[q]) }
        val er = FloatArray(NF); val ei = FloatArray(NF)
        for (j in a until b) {
            val t = j + LA - t0
            val l = lsnr[t]
            System.arraycopy(sre[j - sp0], 0, er, 0, NF); System.arraycopy(sim[j - sp0], 0, ei, 0, NF)
            var gains: Boolean; var zero = false; var df = false
            if (l < MIN_DB) { gains = true; zero = true } else if (l > MAX_ERB_DB) gains = false else { gains = true; df = l <= MAX_DF_DB }
            if (gains) {
                var o = 0
                for (bnd in 0 until NB_ERB) {
                    val g = if (zero) 0f else mask[t][bnd]
                    for (q in 0 until erb[bnd]) { er[o + q] *= g; ei[o + q] *= g }
                    o += erb[bnd]
                }
            }
            if (df) {
                for (f in 0 until NB_DF) { er[f] = 0f; ei[f] = 0f }
                for (i in 0 until ORDER) {
                    val idx = (j - (ORDER - 1) + LA + i) - sp0
                    if (idx < 0 || idx >= sn) continue
                    val pr = sre[idx]; val pi = sim[idx]
                    val base = (t * NB_DF) * ORDER * 2
                    for (f in 0 until NB_DF) {
                        val cr = coefs[base + (f * ORDER + i) * 2]; val ci = coefs[base + (f * ORDER + i) * 2 + 1]
                        er[f] += pr[f] * cr - pi[f] * ci
                        ei[f] += pr[f] * ci + pi[f] * cr
                    }
                }
            }
            istft(er, ei, frameOut)
            for (i in 0 until HOP) out.add((frameOut[i] + smem[i]).toFloat())
            for (i in 0 until FFT - HOP) smem[i] = frameOut[HOP + i]
        }
    }
}
