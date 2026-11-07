package com.kafisir.wetalks.voice

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.util.Log
import android.os.Messenger
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The native engine calls this by NAME and signature (invoke([F)Ljava/lang/Integer;). It must be a real class that the release shrinker (R8) keeps:
 * as a lambda R8 removed that method and the engine aborted with "NoSuchMethodError ... invoke([F)Ljava/lang/Integer;".
 * An exception thrown in here would also cross into native code, so it is caught and reported afterwards.
 * [take] gets every block of samples and answers false to ask the engine to stop.
 */
class PcmSink(private val take: (FloatArray) -> Boolean) : (FloatArray) -> Int {
    @Volatile var error: Throwable? = null
    @Volatile var frames = 0L
    override fun invoke(samples: FloatArray): Int = try {
        if (take(samples)) { frames += samples.size; 1 } else 0
    } catch (e: Throwable) { error = e; 0 }
}

/**
 * Runs the natural voice engine in its OWN process (":tts"). The engine is native code: if it ever crashes, only this process dies and
 * the app keeps running (it then falls back to the phone's own voice).
 *
 * Every reading is a [Job]. Only one job uses the engine at a time (the engine is not thread-safe): a new reading cancels the one before it and waits
 * for it to let go. A cancelled job never answers, and every answer carries its job id, so a late answer from an old reading can never end a new one.
 * The engine writes pieces into a queue and a second thread plays them, so the next piece is made while the current one plays (no gaps).
 */
class TtsService : Service() {
    companion object {
        const val MSG_SPEAK = 1; const val MSG_STOP = 2; const val MSG_PAUSE = 3; const val MSG_RESUME = 4; const val MSG_FREE = 5
        const val R_DONE = 10; const val R_ERROR = 11; const val R_STARTED = 12; const val R_PROGRESS = 13
    }

    private class Job(val id: Long) {
        @Volatile var cancelled = false
        @Volatile var paused = false
        @Volatile var started = false
        @Volatile var track: AudioTrack? = null
    }
    private class Seg(val sentence: Int, val pcm: FloatArray, val gapFrames: Int, val end: Boolean = false)

    private var tts: OfflineTts? = null
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tts-worker") }
    @Volatile private var current: Job? = null
    private var client: Messenger? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { m -> handle(m); true })

    override fun onBind(i: Intent?): IBinder = messenger.binder
    override fun onUnbind(i: Intent?): Boolean { cancelCurrent(); exec.execute { free() }; stopSelf(); return false }
    override fun onDestroy() { cancelCurrent(); free(); exec.shutdownNow(); super.onDestroy() }

    private fun free() { try { tts?.release() } catch (_: Throwable) {}; tts = null }
    private fun reply(j: Job, what: Int, text: String = "", idx: Int = -1) {
        try { client?.send(Message.obtain(null, what).apply { data = Bundle().apply { putString("t", text); putLong("jid", j.id); putInt("i", idx) } }) } catch (_: Exception) {}
    }

    private fun handle(m: Message) {
        when (m.what) {
            MSG_SPEAK -> {
                client = m.replyTo; val d = m.data
                cancelCurrent()
                val j = Job(d.getLong("jid")); current = j
                val dir = d.getString("dir") ?: ""; val chunks = d.getStringArrayList("chunks") ?: arrayListOf(); val langs = d.getStringArrayList("langs") ?: arrayListOf()
                val sents = d.getIntArray("sents") ?: IntArray(0); val gaps = d.getIntArray("gaps") ?: IntArray(0)
                val sid = d.getInt("sid"); val threads = d.getInt("threads", 2); val speed = d.getFloat("speed", 1f).coerceIn(0.5f, 2f)
                exec.execute { run(j, dir, chunks, langs, sents, gaps, sid, threads, speed) }
            }
            MSG_STOP -> cancelCurrent()
            MSG_PAUSE -> current?.let { it.paused = true; try { it.track?.pause() } catch (_: Exception) {} }
            MSG_RESUME -> current?.let { it.paused = false; if (it.started) try { it.track?.play() } catch (_: Exception) {} }
            MSG_FREE -> { cancelCurrent(); exec.execute { free() } }
        }
    }

    /** Marks the running job cancelled and empties its audio buffer so a blocked write returns at once. */
    private fun cancelCurrent() {
        val j = current ?: return
        j.cancelled = true; j.paused = false
        try { j.track?.pause(); j.track?.flush() } catch (_: Exception) {}
    }

    private fun engine(dir: String, threads: Int): OfflineTts {
        tts?.let { return it }
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(supertonic = OfflineTtsSupertonicModelConfig(
            durationPredictor = dir + "duration_predictor.int8.onnx", textEncoder = dir + "text_encoder.int8.onnx", vectorEstimator = dir + "vector_estimator.int8.onnx",
            vocoder = dir + "vocoder.int8.onnx", ttsJson = dir + "tts.json", unicodeIndexer = dir + "unicode_indexer.bin", voiceStyle = dir + "voice.bin"),
            numThreads = threads, debug = false, provider = "cpu"), maxNumSentences = 1)
        Log.i("WeTts", "creating engine: threads=$threads dir=$dir")
        return OfflineTts(config = cfg).also { tts = it; Log.i("WeTts", "engine ready, sampleRate=" + it.sampleRate() + " speakers=" + it.numSpeakers()) }
    }

    private fun offer(q: LinkedBlockingQueue<Seg>, j: Job, s: Seg) { while (!j.cancelled) if (q.offer(s, 100, TimeUnit.MILLISECONDS)) return }

    private fun run(j: Job, dir: String, chunks: List<String>, langs: List<String>, sents: IntArray, gaps: IntArray, sid: Int, threads: Int, speed: Float) {
        if (j.cancelled) return
        var player: Thread? = null
        try {
            val t = engine(dir, threads)
            if (j.cancelled) return
            reply(j, R_STARTED)
            val sr = t.sampleRate()
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val at = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, sr * 4 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
            j.track = at
            val q = LinkedBlockingQueue<Seg>(16)
            player = Thread { play(j, q, at, sr) }.also { it.name = "tts-player"; it.start() }
            for ((ci, chunk) in chunks.withIndex()) {
                if (j.cancelled) break
                val lang = langs.getOrElse(ci) { "en" }
                val cfg = GenerationConfig(sid = sid, speed = speed, extra = mapOf("lang" to lang))      // each piece is read by the voice of its own language
                Log.i("WeTts", "generate sid=$sid lang=$lang speed=$speed chars=" + chunk.length + " text=" + chunk.take(60))
                val parts = ArrayList<FloatArray>()
                // an exception thrown inside this callback would cross into native code and abort the process: PcmSink catches it, we report it afterwards
                val sink = PcmSink { s -> if (j.cancelled) false else { parts.add(s.copyOf()); true } }
                t.generateWithConfigAndCallback(chunk, cfg, sink)
                if (j.cancelled) break
                sink.error?.let { throw it }
                val pcm = PcmEdit.trim(PcmEdit.concat(parts), sr)
                offer(q, j, Seg(sents.getOrElse(ci) { ci }, pcm, sr * gaps.getOrElse(ci) { 90 } / 1000))
            }
            if (!j.cancelled) offer(q, j, Seg(-1, FloatArray(0), 0, end = true))
            player.join()
            try { at.release() } catch (_: Exception) {}
            j.track = null
            if (!j.cancelled) reply(j, R_DONE)
        } catch (e: Throwable) {
            val at = j.track; j.track = null
            try { at?.release() } catch (_: Exception) {}
            val wasLive = !j.cancelled
            j.cancelled = true                                   // lets the player thread finish
            try { player?.join(2000) } catch (_: Exception) {}
            if (wasLive) { free(); reply(j, R_ERROR, (e.javaClass.simpleName + ": " + (e.message ?: "")).take(300)) }
        }
    }

    /** Plays what the engine produces. Starts once about a second of speech is ready (so the first words are not cut by a gap), then keeps the buffer topped up. */
    private fun play(j: Job, q: LinkedBlockingQueue<Seg>, at: AudioTrack, sr: Int) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        var written = 0L; var last = -1
        fun put(a: FloatArray) {
            var o = 0
            while (o < a.size && !j.cancelled) {
                while (j.paused && !j.cancelled) Thread.sleep(30)
                val n = minOf(2048, a.size - o)
                val w = try { at.write(a, o, n, AudioTrack.WRITE_BLOCKING) } catch (_: Exception) { -1 }
                if (w < 0) return
                o += w; written += w
            }
        }
        try {
            val hold = ArrayList<Seg>(); var held = 0L; var ended = false; val t0 = System.currentTimeMillis()
            while (!j.cancelled && !ended && held < sr * 0.9 && System.currentTimeMillis() - t0 < 4000) {
                val s = q.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (s.end) ended = true else { hold.add(s); held += s.pcm.size }
            }
            if (j.cancelled) return
            j.started = true
            if (!j.paused) at.play()
            var i = 0
            while (!j.cancelled) {
                val s = if (i < hold.size) hold[i++] else if (ended) break else (q.poll(100, TimeUnit.MILLISECONDS) ?: continue)
                if (s.end) break
                if (s.sentence != last) { last = s.sentence; reply(j, R_PROGRESS, idx = s.sentence) }
                put(s.pcm)
                if (s.gapFrames > 0) put(FloatArray(s.gapFrames))
            }
            // let the last words finish playing
            var stall = 0; var lastPos = -1L
            while (!j.cancelled) {
                val pos = at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (pos >= written) break
                if (j.paused) { Thread.sleep(50); continue }
                if (pos == lastPos) stall += 50 else { stall = 0; lastPos = pos }
                if (stall > 4000) break
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) { Log.w("WeTts", "player: " + e) }
    }
}
