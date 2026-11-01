package com.silu.minigram.voice;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Random;

public class DenoiserTest {
    static final int FS = 48000;

    /** Voice-like signal: gliding pitch, formant-shaped harmonics, syllable bursts with gaps. */
    static float[] speech(double sec, boolean[] voiced) {
        float[] x = new float[(int) (sec * FS)];
        double ph = 0;
        for (int i = 0; i < x.length; i++) {
            double t = i / (double) FS;
            double syl = t % 0.7;
            boolean on = syl < 0.42;
            voiced[i] = on;
            if (!on) continue;
            double env = Math.sin(Math.PI * syl / 0.42);
            double f0 = 120 + 40 * Math.sin(2 * Math.PI * 0.6 * t);
            ph += 2 * Math.PI * f0 / FS;
            double s = 0;
            for (int h = 1; h <= 25; h++) {
                double f = h * f0;
                double form = Math.exp(-Math.pow((f - 700) / 350, 2)) + 0.6 * Math.exp(-Math.pow((f - 1700) / 500, 2)) + 0.3 * Math.exp(-Math.pow((f - 2900) / 600, 2));
                s += form / h * Math.sin(h * ph);
            }
            x[i] = (float) (0.25 * env * s);
        }
        return x;
    }

    /** Steady background noise: broadband hiss plus low-frequency rumble. */
    static float[] noise(int n, double rms, long seed) {
        Random r = new Random(seed);
        float[] y = new float[n];
        double b0 = 0, lp = 0;
        for (int i = 0; i < n; i++) {
            double w = r.nextGaussian();
            b0 = 0.97 * b0 + 0.03 * w;
            lp = 0.9 * lp + 0.1 * w;
            y[i] = (float) (0.55 * w + 4.0 * b0 + 1.5 * lp);
        }
        double e = 0; for (float v : y) e += v * v; e = Math.sqrt(e / n);
        for (int i = 0; i < n; i++) y[i] *= (float) (rms / e);
        return y;
    }

    static double db(double p) { return 10 * Math.log10(p + 1e-20); }

    @Test public void removesSteadyNoiseWhilePreservingSpeech() {
        boolean[] v = new boolean[FS * 8];
        float[] clean = speech(8, v);
        float[] noisy = clean.clone();
        float[] nz = noise(noisy.length, 0.035, 7);
        for (int i = 0; i < noisy.length; i++) noisy[i] += nz[i];
        float[] out = new SpectralDenoiser().process(noisy);

        double gapIn = 0, gapOut = 0, spClean = 0, spOut = 0; long gc = 0, sc = 0;
        for (int i = 2 * FS; i < v.length - FS / 4; i++) {
            if (!v[i]) { gapIn += (double) noisy[i] * noisy[i]; gapOut += (double) out[i] * out[i]; gc++; }
            else { spClean += (double) clean[i] * clean[i]; spOut += (double) out[i] * out[i]; sc++; }
        }
        double gapReduction = db(gapIn / gc) - db(gapOut / gc);
        double speechChange = db(spOut / sc) - db(spClean / sc);      // vs the CLEAN speech: the denoiser should not make it quieter
        double sig = 0, errIn = 0, errOut = 0;
        for (int i = 2 * FS; i < v.length - FS / 4; i++) {
            sig += (double) clean[i] * clean[i];
            errIn += Math.pow(noisy[i] - clean[i], 2); errOut += Math.pow(out[i] - clean[i], 2);
        }
        double snrIn = db(sig / errIn), snrOut = db(sig / errOut);
        System.out.printf("gap noise reduction %.1f dB, speech level change %.1f dB, SNR %.1f -> %.1f dB%n", gapReduction, speechChange, snrIn, snrOut);
        assertTrue("noise in pauses should drop by >= 15 dB, was " + gapReduction, gapReduction >= 15);
        assertTrue("speech must stay within 2.5 dB of the clean level, was " + speechChange, speechChange > -2.5 && speechChange < 2.5);
        assertTrue("SNR should improve by >= 6 dB, was " + (snrOut - snrIn), snrOut - snrIn >= 6);
    }

    @Test public void worksWhenSpeechStartsImmediately() {
        boolean[] v = new boolean[FS * 6];
        float[] clean = speech(6, v);
        float[] noisy = clean.clone(); float[] nz = noise(noisy.length, 0.03, 11);
        for (int i = 0; i < noisy.length; i++) noisy[i] += nz[i];
        float[] out = new SpectralDenoiser().process(noisy);
        double sig = 0, errIn = 0, errOut = 0;
        for (int i = 0; i < v.length; i++) { sig += (double) clean[i] * clean[i]; errIn += Math.pow(noisy[i] - clean[i], 2); errOut += Math.pow(out[i] - clean[i], 2); }
        System.out.printf("speech-first SNR gain %.1f dB%n", db(sig / errOut) - db(sig / errIn));
        assertTrue("SNR gain (input SNR about 2 dB, speech starts at 0 s) " + (db(sig / errOut) - db(sig / errIn)), db(sig / errOut) - db(sig / errIn) >= 4);
    }

    @Test public void moderateNoiseRealisticCase() {
        boolean[] v = new boolean[FS * 6];
        float[] clean = speech(6, v);
        float[] noisy = clean.clone(); float[] nz = noise(noisy.length, 0.012, 21);     // roughly 10 dB SNR
        for (int i = 0; i < noisy.length; i++) noisy[i] += nz[i];
        float[] out = new SpectralDenoiser().process(noisy);
        double sig = 0, errIn = 0, errOut = 0;
        for (int i = FS; i < v.length - FS / 4; i++) { sig += (double) clean[i] * clean[i]; errIn += Math.pow(noisy[i] - clean[i], 2); errOut += Math.pow(out[i] - clean[i], 2); }
        System.out.printf("moderate noise SNR %.1f -> %.1f dB%n", db(sig / errIn), db(sig / errOut));
        assertTrue(db(sig / errOut) - db(sig / errIn) >= 4);
    }

    @Test public void silenceAndCleanAudioStayIntact() {
        float[] o = new SpectralDenoiser().process(new float[FS * 3]);
        assertEquals(0f, Dsp.peak(o), 1e-6f);
        boolean[] v = new boolean[FS * 4];
        float[] clean = speech(4, v);
        float[] oc = new SpectralDenoiser().process(clean);
        double eIn = 0, eOut = 0;
        for (int i = FS; i < clean.length - FS / 4; i++) { eIn += (double) clean[i] * clean[i]; eOut += (double) oc[i] * oc[i]; }
        System.out.printf("clean speech level change %.1f dB%n", db(eOut) - db(eIn));
        assertTrue("clean speech should not be crushed: " + (db(eOut) - db(eIn)), db(eOut) - db(eIn) > -2.5);
    }

    @Test public void speedIsSuitableForPhones() {
        float[] x = noise(FS * 60, 0.05, 5);
        long t0 = System.nanoTime();
        new SpectralDenoiser().process(x);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf("60 s of audio denoised in %.2f s on the build machine%n", sec);
        assertTrue("too slow: " + sec, sec < 6);
    }

    @Test public void outputLengthMatchesAndIsFinite() {
        float[] x = noise(FS * 2 + 123, 0.05, 3);
        float[] y = new SpectralDenoiser().process(x);
        assertEquals(x.length, y.length);
        for (float f : y) assertFalse(Float.isNaN(f) || Float.isInfinite(f));
    }

    @Test public void compressorReducesLoudQuietGap() {
        float[] x = new float[FS * 2];
        for (int i = 0; i < x.length; i++) x[i] = (float) ((i < FS ? 0.6 : 0.05) * Math.sin(2 * Math.PI * 300 * i / FS));
        double before = 20 * Math.log10(0.6 / 0.05);
        Dsp.compress(x, FS, -24, 3.0, 8, 150);
        double pl = 0, pq = 0;
        for (int i = FS / 2; i < FS; i++) pl = Math.max(pl, Math.abs(x[i]));
        for (int i = FS + FS / 2; i < x.length; i++) pq = Math.max(pq, Math.abs(x[i]));
        double after = 20 * Math.log10(pl / pq);
        System.out.printf("compressor dynamic range %.1f -> %.1f dB%n", before, after);
        assertTrue("dynamic range " + before + " -> " + after, after < before - 4);
    }
}
