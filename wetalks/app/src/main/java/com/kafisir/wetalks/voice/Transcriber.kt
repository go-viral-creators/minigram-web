package com.kafisir.wetalks.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Voice message -> text. The voice note is decoded to 16 kHz PCM and handed to the phone's speech recogniser as an audio file
 * (Android 13+, needs Google speech services). Nothing is uploaded by WeTalks itself.
 */
object Transcriber {
    suspend fun transcribe(ctx: Context, m4a: ByteArray, langTag: String?): String {
        if (Build.VERSION.SDK_INT < 33) throw IllegalStateException("Transcripts need Android 13 or newer")
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) throw IllegalStateException("No speech recognition service found on this phone")
        val pcm = withContext(Dispatchers.Default) { decodeTo16kMono(ctx, m4a) }
        if (pcm.isEmpty()) throw IllegalStateException("Could not read this voice message")
        return withTimeout(90_000) { recognise(ctx, pcm, langTag) }
    }

    private suspend fun recognise(ctx: Context, pcm: ByteArray, langTag: String?): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val pipe = ParcelFileDescriptor.createPipe()
            val rec = SpeechRecognizer.createSpeechRecognizer(ctx)
            fun done(r: Result<String>) { try { rec.destroy() } catch (_: Exception) {}; try { pipe[0].close() } catch (_: Exception) {}; if (cont.isActive) { r.fold({ cont.resume(it) }, { cont.resumeWithException(it) }) } }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); done(if (t.isNullOrBlank()) Result.failure(IllegalStateException("No speech was recognised")) else Result.success(t)) }
                override fun onError(e: Int) { done(Result.failure(IllegalStateException(when (e) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognised"; SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone permission to transcribe"; SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet for the speech service"; 12, 13 -> "This language isn't available for transcripts"; else -> "Transcription isn't supported on this phone ($e)" }))) }
                override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}; override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}; override fun onPartialResults(p: Bundle?) {}; override fun onEvent(t: Int, p: Bundle?) {}
            })
            val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (!langTag.isNullOrEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            }
            Thread {
                try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } } catch (_: Exception) {}
            }.start()
            rec.startListening(it)
            cont.invokeOnCancellation { try { rec.destroy() } catch (_: Exception) {} }
        }
    }

    /** AAC/anything -> 16 kHz mono 16-bit little-endian PCM. */
    private fun decodeTo16kMono(ctx: Context, data: ByteArray): ByteArray {
        val f = File(ctx.cacheDir, "tr_${System.nanoTime()}.m4a"); f.writeBytes(data)
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.absolutePath)
            val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val shorts = ArrayList<Short>(rate * 30)
            val info = MediaCodec.BufferInfo(); var eosIn = false; var eosOut = false
            while (!eosOut) {
                if (!eosIn) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = codec.getInputBuffer(i)!!; val n = ex.readSampleData(b, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosIn = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { val of = codec.outputFormat; rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!; ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sb.remaining() >= ch) { var sum = 0; repeat(ch) { sum += sb.get() }; shorts.add((sum / ch).toShort()) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eosOut = true
                }
            }
            codec.stop(); codec.release()
            val outN = (shorts.size.toLong() * 16000 / rate).toInt()
            val out = ByteArray(outN * 2)
            for (i in 0 until outN) {
                val pos = i.toDouble() * rate / 16000; val i0 = pos.toInt().coerceAtMost(shorts.size - 1); val i1 = (i0 + 1).coerceAtMost(shorts.size - 1); val fr = pos - i0
                val v = (shorts[i0] * (1 - fr) + shorts[i1] * fr).toInt().coerceIn(-32768, 32767)
                out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte()
            }
            return out
        } finally { ex.release(); f.delete() }
    }
}
