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
    private class RecError(val code: Int, msg: String) : Exception(msg)

    class Out(val text: String, val lang: String)

    /**
     * Voice message -> text with automatic language detection: the clip is recognised in a few likely languages
     * (your voice-note language, the phone language, Hindi, English) and the answer the recogniser is most sure about wins.
     */
    suspend fun transcribe(ctx: Context, m4a: ByteArray, preferred: String?): Out {
        if (Build.VERSION.SDK_INT < 33) throw IllegalStateException("Transcripts need Android 13 or newer")
        val pcm = withContext(Dispatchers.Default) { decodeTo16kMono(ctx, m4a) }
        if (pcm.isEmpty()) throw IllegalStateException("Could not read this voice message")
        val google = android.content.ComponentName("com.google.android.googlequicksearchbox", "com.google.android.voicesearch.serviceapi.GoogleRecognitionService")
        val makers = ArrayList<() -> SpeechRecognizer?>()
        if (isInstalled(ctx, google.packageName)) makers += { SpeechRecognizer.createSpeechRecognizer(ctx, google) }
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) makers += { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) makers += { SpeechRecognizer.createSpeechRecognizer(ctx) }
        if (makers.isEmpty()) throw IllegalStateException("No speech recognition service found on this phone")
        val langs = listOfNotNull(preferred?.takeIf { it.isNotEmpty() }, java.util.Locale.getDefault().toLanguageTag(), "hi-IN", "en-IN", "en-US").distinct().take(4)
        var best: Out? = null; var bestConf = -1f; var last: RecError? = null
        for (lang in langs) {
            var got: Pair<String, Float>? = null
            for (make in makers) {
                try { got = withTimeout(45_000) { recognise(make, pcm, lang) }; break }
                catch (e: RecError) { last = e; if (e.code == 12 || e.code == 13) break }           // this language isn't offered: next language
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { last = RecError(-1, "The speech service took too long") }
            }
            val (t, c) = got ?: continue
            val conf = if (c < 0f) .5f else c
            if (conf > bestConf) { best = Out(t, lang); bestConf = conf }
            if (conf >= .75f) break
        }
        return best ?: throw IllegalStateException((last?.message ?: "Transcription failed") + " (code ${last?.code})")
    }

    private fun isInstalled(ctx: Context, pkg: String) = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    private suspend fun recognise(make: () -> SpeechRecognizer?, pcm: ByteArray, lang: String): Pair<String, Float> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val rec = make() ?: run { cont.resumeWithException(RecError(-2, "Speech service unavailable")); return@suspendCancellableCoroutine }
            val pipe = ParcelFileDescriptor.createPipe()
            fun done(r: Result<Pair<String, Float>>) { try { rec.destroy() } catch (_: Exception) {}; try { pipe[0].close() } catch (_: Exception) {}; if (cont.isActive) { r.fold({ cont.resume(it) }, { cont.resumeWithException(it) }) } }
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); val c = b?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f; done(if (t.isNullOrBlank()) Result.failure(RecError(7, "No speech was recognised")) else Result.success(t to c)) }
                override fun onError(e: Int) { done(Result.failure(RecError(e, when (e) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was recognised"; SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone permission to transcribe"; SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not use this audio file. Open the Google app once and set it as your speech service"; 12, 13 -> "This language isn't available for transcripts"; else -> "Transcription isn't supported by this phone's speech service" }))) }
                override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}; override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}; override fun onPartialResults(p: Bundle?) {}; override fun onEvent(t: Int, p: Bundle?) {}
            })
            val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, false)
            }
            Thread { try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(pcm) } } catch (_: Exception) {} }.start()
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
