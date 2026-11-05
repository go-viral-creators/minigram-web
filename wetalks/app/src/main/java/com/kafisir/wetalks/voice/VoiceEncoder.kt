package com.kafisir.wetalks.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Encodes finished 48 kHz mono PCM as AAC-LC in an .m4a container (plays on every Android version). */
object VoiceEncoder {
    fun encodeAac(pcm: FloatArray, tmp: File, bitrate: Int = 40000): ByteArray {
        val pcm16 = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { val v = Math.round(pcm[i].coerceIn(-1f, 1f) * 32767f); pcm16[2 * i] = v.toByte(); pcm16[2 * i + 1] = (v shr 8).toByte() }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        try {
            val info = MediaCodec.BufferInfo(); var pos = 0; var eos = false; var done = false
            while (!done) {
                if (!eos) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val ib = codec.getInputBuffer(i)!!; ib.clear()
                        val n = minOf(ib.remaining(), pcm16.size - pos)
                        if (n > 0) ib.put(pcm16, pos, n)
                        val pts = (pos / 2).toLong() * 1_000_000L / 48000
                        pos += maxOf(n, 0)
                        codec.queueInputBuffer(i, 0, maxOf(n, 0), pts, if (pos >= pcm16.size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (pos >= pcm16.size) eos = true
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start(); started = true }
                else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(track, ob, info) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    codec.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            try { if (started) mux.stop() } catch (_: Throwable) {}
            mux.release()
        }
        if (!started) throw IOException("encoder produced no audio")
        val bytes = tmp.readBytes(); tmp.delete()
        return bytes
    }
}
