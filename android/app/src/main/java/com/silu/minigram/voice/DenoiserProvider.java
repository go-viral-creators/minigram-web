package com.silu.minigram.voice;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The speech-enhancement stage used for every voice note: DeepFilterNet3 on ONNX Runtime (models bundled in
 * assets/dfn3). If the neural model cannot be created on a device, the DSP spectral denoiser takes over.
 */
final class DenoiserProvider {
    private static DeepFilterNet dfn;
    private static boolean failed;
    private DenoiserProvider() {}

    static synchronized Denoiser get(Context c) {
        if (dfn == null && !failed) {
            try {
                dfn = new DeepFilterNet(read(c, "dfn3/enc.onnx"), read(c, "dfn3/erb_dec.onnx"), read(c, "dfn3/df_dec.onnx"));
            } catch (Throwable t) {
                failed = true;
                Log.w("Denoiser", "DeepFilterNet unavailable, using spectral fallback: " + t);
            }
        }
        if (dfn == null) return new SpectralDenoiser();
        final DeepFilterNet n = dfn;
        return new Denoiser() {
            @Override public float[] process(float[] pcm) throws Exception {
                try { return n.process(pcm); }
                catch (Throwable t) { Log.w("Denoiser", "DeepFilterNet failed on this clip, using spectral fallback: " + t); return new SpectralDenoiser().process(pcm); }
            }
            @Override public String name() { return n.name(); }
        };
    }

    private static byte[] read(Context c, String path) throws Exception {
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 20);
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
}
