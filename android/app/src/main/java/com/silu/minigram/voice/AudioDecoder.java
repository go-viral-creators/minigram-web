package com.silu.minigram.voice;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes any audio file Android can read (Ogg/Opus, AAC/M4A, ...) to 16 kHz mono PCM16 little-endian. */
public final class AudioDecoder {
    private AudioDecoder() {}

    public static byte[] pcm16kMono(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        int track = -1; MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat t = ex.getTrackFormat(i);
            String m = t.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) { track = i; fmt = t; break; }
        }
        if (track < 0) throw new IllegalStateException("no audio track");
        ex.selectTrack(track);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();
        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;
        while (!outDone) {
            if (!inDone) {
                int ii = codec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer b = codec.getInputBuffer(ii);
                    int n = ex.readSampleData(b, 0);
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                    else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                }
            }
            int oi = codec.dequeueOutputBuffer(info, 10000);
            if (oi >= 0) {
                ByteBuffer b = codec.getOutputBuffer(oi);
                byte[] chunk = new byte[info.size];
                b.get(chunk);
                pcm.write(chunk, 0, chunk.length);
                codec.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
        }
        codec.stop(); codec.release(); ex.release();
        return resample(pcm.toByteArray(), rate, Math.max(1, ch), 16000);
    }

    /** Down-mixes to mono and linearly resamples PCM16 LE. */
    static byte[] resample(byte[] in, int rate, int ch, int target) {
        ByteBuffer src = ByteBuffer.wrap(in).order(ByteOrder.LITTLE_ENDIAN);
        int frames = in.length / 2 / ch;
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) { float s = 0; for (int c = 0; c < ch; c++) s += src.getShort((i * ch + c) * 2); mono[i] = s / ch; }
        int outN = (int) ((long) frames * target / rate);
        ByteBuffer out = ByteBuffer.allocate(outN * 2).order(ByteOrder.LITTLE_ENDIAN);
        double ratio = (double) rate / target;
        for (int i = 0; i < outN; i++) {
            double p = i * ratio; int i0 = (int) p; int i1 = Math.min(frames - 1, i0 + 1); double fr = p - i0;
            out.putShort((short) Math.max(-32768, Math.min(32767, Math.round(mono[i0] * (1 - fr) + mono[i1] * fr))));
        }
        return out.array();
    }
}
