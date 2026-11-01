package com.silu.minigram.voice;

/**
 * Post-denoise voice finishing (pure Java, 48 kHz mono float): 80 Hz high-pass, gentle presence EQ,
 * ITU-R BS.1770 style loudness normalisation to about -16 LUFS (good for speech on phone speakers), true-peak safe limiter.
 */
public final class Dsp {
    private Dsp() {}

    /** Direct-form-I biquad. */
    static final class Biquad {
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;
        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0;
        }
        float process(float xf) {
            double x = xf;
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            return (float) y;
        }
        void run(float[] a) { for (int i = 0; i < a.length; i++) a[i] = process(a[i]); }

        static Biquad highpass(double fs, double f0, double q) {
            double w = 2 * Math.PI * f0 / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
        }
        static Biquad peaking(double fs, double f0, double q, double gainDb) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f0 / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Biquad(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
        }
        static Biquad highShelf(double fs, double f0, double gainDb, double slope) {
            double A = Math.pow(10, gainDb / 40), w = 2 * Math.PI * f0 / fs, c = Math.cos(w), s = Math.sin(w);
            double al = s / 2 * Math.sqrt((A + 1 / A) * (1 / slope - 1) + 2), sq = 2 * Math.sqrt(A) * al;
            return new Biquad(A * ((A + 1) + (A - 1) * c + sq), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sq),
                    (A + 1) - (A - 1) * c + sq, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sq);
        }
    }

    public static void speechEq(float[] pcm, int fs) {
        Biquad.highpass(fs, 80, 0.707).run(pcm);            // rumble / handling noise
        Biquad.peaking(fs, 3000, 0.9, 2.0).run(pcm);        // presence / intelligibility
        Biquad.peaking(fs, 250, 1.0, -1.5).run(pcm);        // reduce boxiness
    }

    /** BS.1770 K-weighting (shelf + RLB high-pass) coefficients are specified at 48 kHz. */
    private static void kWeight(float[] x, int fs) {
        Biquad.highShelf(fs, 1681.97, 3.9998, 1.0).run(x);
        Biquad.highpass(fs, 38.1355, 0.5003).run(x);
    }

    /** Gated integrated loudness in LUFS (400 ms blocks, 75 % overlap; -70 LUFS absolute and -10 LU relative gates). -inf if silent. */
    public static double integratedLufs(float[] pcm, int fs) {
        float[] k = pcm.clone();
        kWeight(k, fs);
        int block = (int) (0.4 * fs), step = (int) (0.1 * fs);
        if (k.length < block) block = k.length;
        if (block == 0) return Double.NEGATIVE_INFINITY;
        java.util.ArrayList<Double> ms = new java.util.ArrayList<>();
        for (int s = 0; s + block <= k.length; s += Math.max(1, step)) {
            double sum = 0;
            for (int i = s; i < s + block; i++) sum += (double) k[i] * k[i];
            ms.add(sum / block);
        }
        double sumAbs = 0; int nAbs = 0;
        for (double m : ms) if (-0.691 + 10 * Math.log10(m + 1e-20) > -70) { sumAbs += m; nAbs++; }
        if (nAbs == 0) return Double.NEGATIVE_INFINITY;
        double rel = -0.691 + 10 * Math.log10(sumAbs / nAbs) - 10;
        double sum = 0; int n = 0;
        for (double m : ms) { double l = -0.691 + 10 * Math.log10(m + 1e-20); if (l > -70 && l > rel) { sum += m; n++; } }
        return n == 0 ? Double.NEGATIVE_INFINITY : -0.691 + 10 * Math.log10(sum / n);
    }

    /** Applies gain to reach the target loudness (max +18 dB / -12 dB), then limits peaks to -1 dBFS. @return gain applied in dB. */
    public static double normalize(float[] pcm, int fs, double targetLufs) {
        double l = integratedLufs(pcm, fs);
        if (Double.isInfinite(l)) return 0;
        double gainDb = Math.max(-12, Math.min(18, targetLufs - l));
        float g = (float) Math.pow(10, gainDb / 20);
        for (int i = 0; i < pcm.length; i++) pcm[i] *= g;
        limit(pcm, fs);
        return gainDb;
    }

    /** Look-ahead peak limiter (ceiling -1 dBFS, 5 ms attack, 80 ms release). */
    public static void limit(float[] x, int fs) {
        final float ceil = (float) Math.pow(10, -1.0 / 20);
        int look = Math.max(1, (int) (0.005 * fs));
        float[] gain = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            float a = Math.abs(x[i]);
            gain[i] = a > ceil ? ceil / a : 1f;
        }
        // running minimum over the look-ahead window, then smooth (attack instantaneous via window, release exponential)
        float[] g2 = new float[x.length];
        java.util.ArrayDeque<Integer> dq = new java.util.ArrayDeque<>();
        for (int i = 0; i < x.length + look; i++) {
            if (i < x.length) {
                while (!dq.isEmpty() && gain[dq.peekLast()] >= gain[i]) dq.pollLast();
                dq.addLast(i);
            }
            int out = i - look;
            if (out >= 0) {
                while (dq.peekFirst() < out) dq.pollFirst();
                g2[out] = gain[dq.peekFirst()];
            }
        }
        float rel = (float) Math.exp(-1.0 / (0.08 * fs)), env = 1f;
        for (int i = 0; i < x.length; i++) {
            float want = g2[i];
            env = want < env ? want : want + (env - want) * rel;
            x[i] *= env;
        }
    }

    public static float peak(float[] x) { float p = 0; for (float v : x) p = Math.max(p, Math.abs(v)); return p; }
}
