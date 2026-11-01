package com.silu.minigram.voice;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Encodes finished 48 kHz mono float PCM: Opus-in-Ogg where the device has an Opus encoder (Android 10+), else AAC (.m4a). */
public final class VoiceEncoder {
    public static final class Result { public File file; public String mime, ext; }
    private VoiceEncoder() {}

    public static Result encode(float[] pcm, File outDirFile) throws IOException {
        byte[] pcm16 = toPcm16(pcm);
        Result r = new Result();
        if (Build.VERSION.SDK_INT >= 29) {
            File f = new File(outDirFile.getParentFile(), outDirFile.getName() + ".ogg");
            try { encodeOpus(pcm16, pcm.length, f); r.file = f; r.mime = "audio/ogg"; r.ext = "ogg"; return r; }
            catch (Exception e) { //noinspection ResultOfMethodCallIgnored
                f.delete(); }
        }
        File f = new File(outDirFile.getParentFile(), outDirFile.getName() + ".m4a");
        encodeAac(pcm16, f);
        r.file = f; r.mime = "audio/mp4"; r.ext = "m4a";
        return r;
    }

    static byte[] toPcm16(float[] pcm) {
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, pcm[i])) * 32767f);
            b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    private static void encodeOpus(byte[] pcm16, int totalSamples, File out) throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat("audio/opus", 48000, 1);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 24000);
        MediaCodec codec = MediaCodec.createEncoderByType("audio/opus");
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        try (BufferedOutputStream bo = new BufferedOutputStream(new FileOutputStream(out))) {
            OggOpusWriter ogg = null;
            byte[] head = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int pos = 0; boolean eos = false, done = false;
            long ptsUs = 0, lastPts = -1;
            while (!done) {
                if (!eos) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(in);
                        ib.clear();
                        int n = Math.min(ib.remaining(), pcm16.length - pos);
                        if (n > 0) ib.put(pcm16, pos, n);
                        pos += Math.max(n, 0);
                        long t = (long) (pos / 2) * 1_000_000L / 48000;
                        if (pos >= pcm16.length) { codec.queueInputBuffer(in, 0, Math.max(n, 0), ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true; }
                        else codec.queueInputBuffer(in, 0, n, ptsUs, 0);
                        ptsUs = t;
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    ByteBuffer csd = of.containsKey("csd-0") ? of.getByteBuffer("csd-0") : null;
                    if (csd != null) { head = new byte[csd.remaining()]; csd.duplicate().get(head); }
                } else if (o >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        if (head == null && info.size > 0) { head = new byte[info.size]; ob.position(info.offset); ob.get(head); }
                    } else if (info.size > 0) {
                        if (ogg == null) ogg = new OggOpusWriter(bo, head, 48000);
                        byte[] pkt = new byte[info.size];
                        ob.position(info.offset); ob.get(pkt, 0, info.size);
                        int samples = lastPts >= 0 && info.presentationTimeUs > lastPts
                                ? (int) Math.round((info.presentationTimeUs - lastPts) * 48.0 / 1000.0) : 960;
                        if (samples <= 0 || samples > 5760) samples = 960;
                        lastPts = info.presentationTimeUs;
                        ogg.writePacket(pkt, samples);
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    codec.releaseOutputBuffer(o, false);
                }
            }
            if (ogg == null) throw new IOException("encoder produced no audio");
            ogg.close(totalSamples);
        } finally {
            try { codec.stop(); } catch (Throwable ignored) { }
            codec.release();
        }
    }

    private static void encodeAac(byte[] pcm16, File out) throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 48000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1; boolean muxStarted = false;
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int pos = 0; boolean eos = false, done = false;
            while (!done) {
                if (!eos) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(in);
                        ib.clear();
                        int n = Math.min(ib.remaining(), pcm16.length - pos);
                        if (n > 0) ib.put(pcm16, pos, n);
                        long pts = (long) (pos / 2) * 1_000_000L / 48000;
                        pos += Math.max(n, 0);
                        codec.queueInputBuffer(in, 0, Math.max(n, 0), pts, pos >= pcm16.length ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (pos >= pcm16.length) eos = true;
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.getOutputFormat()); mux.start(); muxStarted = true;
                } else if (o >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(track, ob, info); }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    codec.releaseOutputBuffer(o, false);
                }
            }
        } finally {
            try { codec.stop(); } catch (Throwable ignored) { }
            codec.release();
            try { if (muxStarted) mux.stop(); } catch (Throwable ignored) { }
            mux.release();
        }
    }
}
