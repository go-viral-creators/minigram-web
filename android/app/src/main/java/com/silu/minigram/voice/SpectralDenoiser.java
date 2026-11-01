package com.silu.minigram.voice;

/**
 * Real-time-capable speech denoiser (pure Java, 48 kHz mono):
 * STFT (1024 / hop 256, sqrt-Hann WOLA) -> per-bin noise tracking by minimum statistics (no "silence at the start"
 * needed) -> decision-directed Wiener gain with a floor and temporal/frequency smoothing -> overlap-add.
 * Removes steady background noise (fans, hum, hiss, street/traffic rumble) while keeping speech.
 * Cost is a few percent of one core, so it is safe on low-end phones.
 */
public final class SpectralDenoiser implements Denoiser {
    static final int N = 1024, HOP = 256, K = N / 2 + 1;
    /** Bias of the minimum-statistics estimator for the smoothing/window used below (calibrated in the unit test). */
    static final double BMIN = 2.1;
    private final double strength;                 // 0..1: how hard to suppress (gain floor)

    public SpectralDenoiser() { this(0.85); }
    public SpectralDenoiser(double strength) { this.strength = Math.max(0, Math.min(1, strength)); }

    @Override public String name() { return "denoise"; }

    // ---------------------------------------------------------------- FFT (radix-2, in place)
    private static final double[] COS = new double[N / 2], SIN = new double[N / 2];
    private static final int[] REV = new int[N];
    static {
        for (int i = 0; i < N / 2; i++) { COS[i] = Math.cos(2 * Math.PI * i / N); SIN[i] = -Math.sin(2 * Math.PI * i / N); }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) REV[i] = Integer.reverse(i) >>> (32 - bits);
    }
    static void fft(double[] re, double[] im, boolean inverse) {
        for (int i = 0; i < N; i++) { int j = REV[i]; if (j > i) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1, step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    double wr = COS[k], wi = inverse ? -SIN[k] : SIN[k];
                    int a = i + j, b = a + half;
                    double xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                }
            }
        }
        if (inverse) for (int i = 0; i < N; i++) { re[i] /= N; im[i] /= N; }
    }

    // ---------------------------------------------------------------- processing
    /**
     * Two passes. Pass 1 (analysis only) tracks the smoothed power of every bin and keeps its minimum per block of
     * 48 frames. The noise floor of a frame is then the minimum over the neighbouring blocks on BOTH sides (about
     * +-1.5 s), so it is right from the very first word even if the speaker starts talking immediately.
     * Pass 2 applies the Wiener gain.
     */
    @Override public float[] process(float[] x) {
        final int n = x.length;
        if (n < N) return x;
        final double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        final int pad = N;
        final int total = n + 2 * pad + N;
        final double[] in = new double[total], out = new double[total];
        for (int i = 0; i < n; i++) in[pad + i] = x[i];
        final int frames = (total - N) / HOP + 1;
        final int V = 48, R = 3;                          // block length (frames) and neighbour radius (blocks)
        final int blocks = (frames + V - 1) / V;
        final float[][] blockMin = new float[blocks][K];
        double[] re = new double[N], im = new double[N], ps = new double[K];

        // ---- pass 1: noise tracking
        for (int f = 0; f < frames; f++) {
            int pos = f * HOP;
            for (int i = 0; i < N; i++) { re[i] = in[pos + i] * win[i]; im[i] = 0; }
            fft(re, im, false);
            int b = f / V;
            for (int k = 0; k < K; k++) {
                double p = re[k] * re[k] + im[k] * im[k];
                ps[k] = f == 0 ? p : 0.85 * ps[k] + 0.15 * p;
                if (f % V == 0 || ps[k] < blockMin[b][k]) blockMin[b][k] = (float) ps[k];
            }
        }

        // ---- pass 2: gain + resynthesis
        final double gFloor = Math.pow(10, (-8 - 20 * strength) / 20);        // strength .85 -> about -25 dB
        final double xiMin = Math.pow(10, -25.0 / 10);
        double[] noise = new double[K], prevClean = new double[K], gPrev = new double[K], g = new double[K], gs = new double[K];
        int lastBlock = -1;
        for (int f = 0; f < frames; f++) {
            int pos = f * HOP;
            int b = f / V;
            if (b != lastBlock) {                                              // refresh the floor once per block
                lastBlock = b;
                for (int k = 0; k < K; k++) {
                    float m = Float.MAX_VALUE;
                    for (int j = Math.max(0, b - R); j <= Math.min(blocks - 1, b + R); j++) if (blockMin[j][k] < m) m = blockMin[j][k];
                    noise[k] = Math.max(m * BMIN, 1e-12);
                }
            }
            for (int i = 0; i < N; i++) { re[i] = in[pos + i] * win[i]; im[i] = 0; }
            fft(re, im, false);
            for (int k = 0; k < K; k++) {
                double p = re[k] * re[k] + im[k] * im[k];
                double gamma = p / noise[k];
                double xi = 0.97 * (prevClean[k] / noise[k]) + 0.03 * Math.max(gamma - 1, 0);
                if (xi < xiMin) xi = xiMin;
                double gain = xi / (1 + xi);
                g[k] = gain < gFloor ? gFloor : gain;
            }
            // 3-tap frequency smoothing + fast attack / slow release in time (no "musical noise", no chopped word ends)
            for (int k = 0; k < K; k++) {
                double a = g[Math.max(0, k - 1)], bb = g[k], c = g[Math.min(K - 1, k + 1)];
                double s2 = Math.max(0.25 * a + 0.5 * bb + 0.25 * c, bb * 0.9);
                gs[k] = s2 < gPrev[k] ? 0.55 * gPrev[k] + 0.45 * s2 : s2;
            }
            for (int k = 0; k < K; k++) {
                gPrev[k] = gs[k];
                re[k] *= gs[k]; im[k] *= gs[k];
                prevClean[k] = re[k] * re[k] + im[k] * im[k];
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }
            }
            fft(re, im, true);
            for (int i = 0; i < N; i++) out[pos + i] += re[i] * win[i] * 0.5;      // sum of Hann at 75 % overlap = 2
        }
        float[] y = new float[n];
        for (int i = 0; i < n; i++) y[i] = (float) out[pad + i];
        return y;
    }
}
