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

/**
 * Runs the natural voice engine in its OWN process (":tts"). The engine is native code: if it ever crashes, only this process dies and
 * the app keeps running (it then falls back to the phone's own voice). Speaks the sentences it is given and plays them itself.
 */
/**
 * The native engine calls this by NAME and signature (invoke([F)Ljava/lang/Integer;). It must be a real class that the release shrinker (R8) keeps:
 * as a lambda R8 removed that method and the engine aborted with "NoSuchMethodError ... invoke([F)Ljava/lang/Integer;".
 * An exception thrown in here would also cross into native code, so it is caught and reported afterwards.
 */
class PcmSink(private val at: AudioTrack, private val cancelled: () -> Boolean) : (FloatArray) -> Int {
    @Volatile var error: Throwable? = null
    @Volatile var frames = 0L
    override fun invoke(samples: FloatArray): Int = try {
        if (cancelled()) 0 else {
            val w = at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            if (w < 0) { error = IllegalStateException("AudioTrack.write returned $w"); 0 } else { frames += w; 1 }
        }
    } catch (e: Throwable) { error = e; 0 }
}

class TtsService : Service() {
    companion object {
        const val MSG_SPEAK = 1; const val MSG_STOP = 2; const val MSG_PAUSE = 3; const val MSG_RESUME = 4; const val MSG_FREE = 5
        const val R_DONE = 10; const val R_ERROR = 11; const val R_STARTED = 12
    }

    private var tts: OfflineTts? = null
    @Volatile private var cancel = false
    @Volatile private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var client: Messenger? = null
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { m -> handle(m); true })

    override fun onBind(i: Intent?): IBinder = messenger.binder
    override fun onUnbind(i: Intent?): Boolean { stopWork(); free(); stopSelf(); return false }
    override fun onDestroy() { stopWork(); free(); super.onDestroy() }

    private fun free() { try { tts?.release() } catch (_: Throwable) {}; tts = null }
    private fun reply(what: Int, text: String = "") { try { client?.send(Message.obtain(null, what).apply { data = Bundle().apply { putString("t", text) } }) } catch (_: Exception) {} }

    private fun handle(m: Message) {
        when (m.what) {
            MSG_SPEAK -> { client = m.replyTo; val d = m.data; start(d.getString("dir") ?: "", d.getStringArrayList("chunks") ?: arrayListOf(), d.getStringArrayList("langs") ?: arrayListOf(), d.getInt("sid"), d.getInt("threads", 2)) }
            MSG_STOP -> stopWork()
            MSG_PAUSE -> try { track?.pause() } catch (_: Exception) {}
            MSG_RESUME -> try { track?.play() } catch (_: Exception) {}
            MSG_FREE -> { stopWork(); free() }
        }
    }

    private fun stopWork() { cancel = true; try { track?.pause(); track?.flush() } catch (_: Exception) {} }

    private fun engine(dir: String, threads: Int): OfflineTts {
        tts?.let { return it }
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(supertonic = OfflineTtsSupertonicModelConfig(
            durationPredictor = dir + "duration_predictor.int8.onnx", textEncoder = dir + "text_encoder.int8.onnx", vectorEstimator = dir + "vector_estimator.int8.onnx",
            vocoder = dir + "vocoder.int8.onnx", ttsJson = dir + "tts.json", unicodeIndexer = dir + "unicode_indexer.bin", voiceStyle = dir + "voice.bin"),
            numThreads = threads, debug = true, provider = "cpu"), maxNumSentences = 1)
        Log.i("WeTts", "creating engine: threads=$threads dir=$dir")
        return OfflineTts(config = cfg).also { tts = it; Log.i("WeTts", "engine ready, sampleRate=" + it.sampleRate() + " speakers=" + it.numSpeakers()) }
    }

    private fun start(dir: String, chunks: List<String>, langs: List<String>, sid: Int, threads: Int) {
        stopWork()
        val prev = worker
        cancel = false
        worker = Thread {
            try { prev?.join(3000) } catch (_: InterruptedException) {}
            try {
                val t = engine(dir, threads)
                reply(R_STARTED)
                val sr = t.sampleRate()
                val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                val at = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(min, sr * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
                track = at; at.play()
                var frames = 0L
                for ((ci, chunk) in chunks.withIndex()) {
                    if (cancel) break
                    val lang = langs.getOrElse(ci) { "en" }
                    val cfg = GenerationConfig(sid = sid, speed = 1.0f, extra = mapOf("lang" to lang))      // each piece is read by the voice of its own language
                    Log.i("WeTts", "generate sid=$sid lang=$lang chars=" + chunk.length + " text=" + chunk.take(60))
                    // an exception thrown inside this callback would cross into native code and abort the process: catch it, stop, and report it afterwards
                    val sink = PcmSink(at, { cancel })
                    t.generateWithConfigAndCallback(chunk, cfg, sink)
                    frames += sink.frames
                    sink.error?.let { throw it }
                }
                if (!cancel) {   // let the last sentence finish playing
                    at.stop()
                    var waited = 0
                    while (!cancel && at.playbackHeadPosition < frames && waited < 60_000) { Thread.sleep(100); waited += 100 }
                }
                try { at.release() } catch (_: Exception) {}
                track = null
                reply(R_DONE)
            } catch (e: Throwable) {
                try { track?.release() } catch (_: Exception) {}; track = null
                free()
                reply(R_ERROR, (e.javaClass.simpleName + ": " + (e.message ?: "")).take(300))
            }
        }.also { it.name = "tts-worker"; it.start() }
    }
}
