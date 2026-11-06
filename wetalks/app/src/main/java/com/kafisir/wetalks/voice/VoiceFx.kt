package com.kafisir.wetalks.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** User switches for the voice-note pipeline, plus the shared DeepFilterNet3 engine. */
/** Paid ("WeTalks Plus") features stay switched off until this is true. */
object Premium {
    const val unlocked = false     // AI noise remover + auto-cut
    const val files = false        // sending video, audio and documents (needs server storage, so it is a Plus feature / coming soon)
}

object VoiceFx {
    /** AI background-noise removal and auto-cut silence are Plus features; "Voice enhance" (EQ + volume normalisation) is free. */
    fun aiOn() = Premium.unlocked && ai.value
    fun cutOn() = Premium.unlocked && cut.value
    val ai = mutableStateOf(true)          // neural noise + echo removal
    val polish = mutableStateOf(true)      // speech EQ, compression, loudness normalisation
    val cut = mutableStateOf(true)         // shorten pauses longer than 300 ms
    private var engine: DfnEngine? = null
    private var failed = false
    private fun sp(c: Context) = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE)
    fun load(c: Context) {
        appCtx = c.applicationContext
        ai.value = sp(c).getBoolean("vfx_ai", true); polish.value = sp(c).getBoolean("vfx_polish", true); cut.value = sp(c).getBoolean("vfx_cut", true)
        if (sp(c).getBoolean("ort_busy", false)) {            // last run ended inside the native engine
            sp(c).edit().putBoolean("ort_busy", false).putBoolean("vfx_ai", false).putBoolean("ort_crashed", true).apply()
            ai.value = false; crashedBefore = true
        } else crashedBefore = sp(c).getBoolean("ort_crashed", false)
    }
    /** The user turned the AI stage back on after a crash: give it another chance. */
    fun retryAfterCrash(c: Context) { sp(c).edit().putBoolean("ort_crashed", false).apply(); crashedBefore = false; failed = false; setAi(c, true) }
    fun setAi(c: Context, v: Boolean) { ai.value = v; sp(c).edit().putBoolean("vfx_ai", v).apply(); if (v) warmUp(c) }
    fun setCut(c: Context, v: Boolean) { cut.value = v; sp(c).edit().putBoolean("vfx_cut", v).apply() }
    fun setPolish(c: Context, v: Boolean) { polish.value = v; sp(c).edit().putBoolean("vfx_polish", v).apply() }

    private var appCtx: Context? = null
    /** true when the previous run died inside the native AI engine; the AI stage is then switched off so the app keeps working. */
    var crashedBefore by mutableStateOf(false); private set
    var engineError by mutableStateOf<String?>(null); private set

    @Synchronized
    fun engine(c: Context): DfnEngine? {
        appCtx = c.applicationContext
        if (engine == null && !failed && !crashedBefore) try {
            fun rd(n: String) = c.assets.open("dfn3/$n").use { it.readBytes() }
            sp(c).edit().putBoolean("ort_busy", true).commit()               // cleared again straight away if nothing crashes
            engine = DfnEngine(rd("enc.ort"), rd("erb_dec.ort"), rd("df_dec.ort"))
            sp(c).edit().putBoolean("ort_busy", false).commit()
        } catch (t: Throwable) { failed = true; engineError = t.javaClass.simpleName + ": " + (t.message ?: ""); sp(c).edit().putBoolean("ort_busy", false).commit() }
        return engine
    }

    // A native crash cannot be caught, so the flag is written to disk before each risky call and cleared after it.
    fun guardStart() { appCtx?.let { sp(it).edit().putBoolean("ort_busy", true).commit() } }
    fun guardEnd() { appCtx?.let { sp(it).edit().putBoolean("ort_busy", false).apply() } }
    /** Loads the models in the background so the first voice note starts instantly. */
    fun warmUp(c: Context) { if (aiOn() && !crashedBefore) Thread({ engine(c.applicationContext) }, "dfn-warmup").start() }
}

/**
 * Voice-note recorder. Captures 48 kHz mono PCM (platform echo canceller on, platform noise suppressor off so it never
 * stacks with ours) and, while the user is still talking, streams it through DeepFilterNet3 on a worker thread.
 * On release only the short tail is left to process: loudness normalisation, then AAC.
 */
class Recorder(private val ctx: Context) {
    companion object { const val SR = 48000; const val MAX_SECONDS = 180 }
    class Result(val bytes: ByteArray, val seconds: Int, val wave: List<Int>)

    private val raw = File(ctx.cacheDir, "rec_${System.nanoTime()}.pcm")
    private var ar: AudioRecord? = null
    private var capture: Thread? = null
    private var worker: Thread? = null
    private val queue = LinkedBlockingQueue<ShortArray>()
    private val amps = ArrayList<Int>()
    private var stream: DfnStream? = null
    @Volatile private var dfnOk = false
    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile private var samples = 0L
    @Volatile private var lvl = 0f
    var startedAt = 0L; private set

    val elapsedSec: Int get() = (samples / SR).toInt()
    fun level(): Float = lvl

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalStateException("Recording isn't supported on this phone")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SR / 5 * 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("Microphone unavailable") }
        ar = r
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true } catch (_: Throwable) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = false } catch (_: Throwable) {}
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(r.audioSessionId)?.enabled = false } catch (_: Throwable) {}

        if (VoiceFx.aiOn()) VoiceFx.engine(ctx)?.let { e ->
            stream = DfnStream(e); dfnOk = true
            worker = Thread({
                try {
                    while (true) {
                        val c = queue.take(); if (c.isEmpty()) break
                        if (dfnOk) try { val f = FloatArray(c.size) { c[it] / 32768f }; stream!!.feed(f) } catch (t: Throwable) { dfnOk = false }
                    }
                } catch (_: InterruptedException) {}
            }, "dfn-stream").apply { priority = Thread.NORM_PRIORITY; start() }
        }

        running = true; startedAt = System.currentTimeMillis()
        r.startRecording()
        capture = Thread({
            val buf = ShortArray(SR / 50)          // 20 ms
            val bytes = ByteArray(buf.size * 2)
            var smooth = 0f; var peak = 0; var count = 0
            try {
                java.io.BufferedOutputStream(java.io.FileOutputStream(raw), 1 shl 16).use { out ->
                    while (running) {
                        val n = r.read(buf, 0, buf.size)
                        if (n <= 0 || paused) continue
                        var sum = 0.0
                        for (i in 0 until n) { val v = buf[i].toInt(); bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte(); sum += v.toDouble() * v; peak = maxOf(peak, abs(v)) }
                        out.write(bytes, 0, n * 2)
                        samples += n
                        if (stream != null) queue.offer(buf.copyOf(n))
                        val rms = (sqrt(sum / n) / 32768.0).toFloat()
                        val l = minOf(1f, (rms * 6f).pow(0.6f)); smooth = if (l > smooth) l else smooth * .8f + l * .2f; lvl = smooth
                        if (++count == 5) { synchronized(amps) { amps.add(peak) }; peak = 0; count = 0 }
                    }
                }
            } catch (_: Throwable) {}
        }, "voice-capture").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun stopCapture() {
        running = false
        try { capture?.join(1500) } catch (_: InterruptedException) {}
        try { ar?.stop(); ar?.release() } catch (_: Throwable) {}
        ar = null
        queue.offer(ShortArray(0))
        try { worker?.join(8000) } catch (_: InterruptedException) {}
    }

    fun cancel() { stopCapture(); raw.delete() }

    suspend fun finish(): Result? = withContext(Dispatchers.Default) {
        stopCapture()
        val n = samples
        try {
            val seconds = (n / SR).toInt()
            if (n < SR / 2) { raw.delete(); return@withContext Result(ByteArray(0), 0, emptyList()) }
            var x: FloatArray? = null
            if (dfnOk) try { x = stream!!.finish() } catch (_: Throwable) { x = null }
            if (x == null) x = readRaw(n.toInt())
            if (VoiceFx.cutOn()) x = SilenceCut.cut(x, SR)
            if (VoiceFx.polish.value) {
                Dsp.speechEq(x, SR); Dsp.compress(x, SR, -26.0, 2.5, 8.0, 160.0); Dsp.normalize(x, SR, -16.0)
            } else Dsp.limit(x, SR)
            Dsp.fade(x, SR)
            val bytes = VoiceEncoder.encodeAac(x, File(ctx.cacheDir, "voice_${System.nanoTime()}.m4a"))
            Result(bytes, if (VoiceFx.cutOn()) maxOf(1, Math.round(x.size / SR.toFloat())) else seconds, SilenceCut.wave(x))
        } catch (e: Throwable) { null } finally { raw.delete() }
    }

    private fun readRaw(n: Int): FloatArray {
        val b = raw.readBytes(); val cnt = minOf(n, b.size / 2)
        return FloatArray(cnt) { i -> ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xff)).toShort() / 32768f }
    }

    private fun downsample(a: List<Int>, n: Int = 40): List<Int> {
        if (a.isEmpty()) return List(n) { 2 }
        val peak = (a.maxOrNull() ?: 1).coerceAtLeast(1)
        return List(n) { i ->
            val from = i * a.size / n; val to = ((i + 1) * a.size / n).coerceAtLeast(from + 1).coerceAtMost(a.size)
            val m = a.subList(from, to).maxOrNull() ?: 0
            (m * 31f / peak).toInt().coerceIn(2, 31)
        }
    }
}
