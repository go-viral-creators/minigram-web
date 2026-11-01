package com.silu.minigram.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice-note finishing chain, run on a single background thread so the UI and low-end phones stay smooth:
 *   platform NS/AGC/AEC (during capture)  ->  neural denoiser (if its model is installed)  ->
 *   speech EQ  ->  loudness normalisation + limiter  ->  Opus (or AAC) encode.
 */
public final class VoicePipeline {
    public interface Callback { void onDone(VoiceEncoder.Result r, int durationSec, String stages); void onError(Exception e); }
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voice-pipeline"); t.setPriority(Thread.NORM_PRIORITY - 1); return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private VoicePipeline() {}

    public static void processAsync(Context ctx, File rawPcm48k, int platformFx, Callback cb) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                float[] x = readPcm(rawPcm48k);
                StringBuilder stages = new StringBuilder(platformFx == 0 ? "" : "platform-fx");
                Denoiser d = DenoiserProvider.get(app);
                if (d != null) {
                    try { x = d.process(x); append(stages, d.name()); }
                    catch (Throwable t) { Log.w("VoicePipeline", "denoiser failed, continuing without: " + t.getClass().getSimpleName()); }
                }
                // noise floor is already pushed down, so levelling can no longer lift it
                Dsp.speechEq(x, LiveRecorder.SAMPLE_RATE);
                Dsp.compress(x, LiveRecorder.SAMPLE_RATE, -26.0, 2.5, 8, 160);
                Dsp.normalize(x, LiveRecorder.SAMPLE_RATE, -16.0);
                Dsp.fade(x, LiveRecorder.SAMPLE_RATE);
                append(stages, "eq>compress>loudness");
                VoiceEncoder.Result r = VoiceEncoder.encode(x, new File(rawPcm48k.getParentFile(), "voice_" + System.nanoTime()));
                append(stages, r.ext.equals("ogg") ? "opus" : "aac");
                final int dur = Math.round(x.length / (float) LiveRecorder.SAMPLE_RATE);
                final String st = stages.toString();
                //noinspection ResultOfMethodCallIgnored
                rawPcm48k.delete();
                MAIN.post(() -> cb.onDone(r, dur, st));
            } catch (Exception e) {
                MAIN.post(() -> cb.onError(e));
            }
        });
    }

    private static void append(StringBuilder sb, String s) { if (sb.length() > 0) sb.append(" > "); sb.append(s); }

    static float[] readPcm(File f) throws IOException {
        int n = (int) (f.length() / 2);
        float[] x = new float[n];
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            byte[] b = new byte[2];
            for (int i = 0; i < n; i++) { in.readFully(b); x[i] = ((short) ((b[1] << 8) | (b[0] & 0xff))) / 32768f; }
        }
        return x;
    }
}
