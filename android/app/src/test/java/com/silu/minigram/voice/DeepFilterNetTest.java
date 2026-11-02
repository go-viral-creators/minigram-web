package com.silu.minigram.voice;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

public class DeepFilterNetTest {
    private static byte[] model(String n) throws Exception { return Files.readAllBytes(new File("src/main/assets/dfn3/" + n).toPath()); }
    private static double rms(float[] a, int s, int e) { double x = 0; for (int i = s; i < e; i++) x += a[i] * (double) a[i]; return Math.sqrt(x / Math.max(1, e - s)) + 1e-12; }
    private static double db(double v) { return 20 * Math.log10(v); }

    @Test public void fftMatchesNaiveDft() {
        int n = 960; Fft f = new Fft(n);
        double[] re = new double[n], im = new double[n], r0 = new double[n], i0 = new double[n];
        Random rnd = new Random(1);
        for (int i = 0; i < n; i++) { re[i] = r0[i] = rnd.nextGaussian(); }
        f.forward(re, im);
        for (int k : new int[]{0, 1, 7, 100, 479, 480, 959}) {
            double sr = 0, si = 0;
            for (int t = 0; t < n; t++) { double a = -2 * Math.PI * k * t / n; sr += r0[t] * Math.cos(a); si += r0[t] * Math.sin(a); }
            assertEquals(sr, re[k], 1e-6); assertEquals(si, im[k], 1e-6);
        }
    }

    @Test public void erbBandsCoverAllBins() {
        int s = 0; for (int v : DeepFilterNet.erbBands()) { assertTrue(v >= 2); s += v; }
        assertEquals(481, s);
    }

    @Test public void removesSteadyNoiseKeepsSpeechLikeSignal() throws Exception {
        DeepFilterNet d = new DeepFilterNet(model("enc.onnx"), model("erb_dec.onnx"), model("df_dec.onnx"));
        int sr = 48000, n = sr * 4;
        float[] clean = new float[n], noisy = new float[n];
        Random rnd = new Random(0);
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) sr;
            double f0 = 120 + 30 * Math.sin(2 * Math.PI * 0.7 * t);
            ph += 2 * Math.PI * f0 / sr;
            double s = 0; for (int h = 1; h < 30; h++) s += Math.sin(h * ph) / h;
            double env = (Math.sin(2 * Math.PI * 2.5 * t) > 0.2 ? 1 : 0) * (0.5 + 0.5 * Math.sin(2 * Math.PI * 0.9 * t));
            clean[i] = (float) (0.25 * s * env);
            noisy[i] = clean[i] + (float) (0.05 * rnd.nextGaussian());
        }
        float[] out = d.process(noisy);
        assertEquals(n, out.length);
        // find a noise-only stretch (envelope 0) and a speech stretch
        int qs = -1, qe = -1, ss = -1, se = -1;
        for (int i = 0; i < n - sr / 5; i += sr / 10) {
            boolean quiet = true, loud = true;
            for (int j = i; j < i + sr / 5; j++) { if (Math.abs(clean[j]) > 1e-6) quiet = false; if (Math.abs(clean[j]) < 1e-3) loud = false; }
            if (quiet && qs < 0) { qs = i; qe = i + sr / 5; }
            if (!quiet && rms(clean, i, i + sr / 5) > 0.1 && ss < 0) { ss = i; se = i + sr / 5; }
        }
        assertTrue("need a quiet and a speech stretch", qs >= 0 && ss >= 0);
        double noiseDrop = db(rms(noisy, qs, qe)) - db(rms(out, qs, qe));
        double speechDelta = db(rms(out, ss, se)) - db(rms(noisy, ss, se));
        assertTrue("noise should drop by >=15 dB, was " + noiseDrop, noiseDrop >= 15);
        assertTrue("speech should keep its level, delta " + speechDelta, speechDelta > -3);
        d.close();
    }
}
