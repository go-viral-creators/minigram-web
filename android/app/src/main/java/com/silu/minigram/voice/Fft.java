package com.silu.minigram.voice;

/** Complex FFT for any length whose prime factors are small (960 = 2^6 * 3 * 5). Double precision, allocation-free per call after warm-up. */
final class Fft {
    private final int n;
    private final int[] factors;
    private final double[] cos, sin;      // W_n^k = exp(-2 pi i k / n)
    private final double[] yr, yi, tr, ti;

    Fft(int n) {
        this.n = n;
        int m = n, c = 0; int[] f = new int[32];
        for (int p = 2; m > 1; ) { if (m % p == 0) { f[c++] = p; m /= p; } else p += (p == 2) ? 1 : 2; }
        factors = java.util.Arrays.copyOf(f, c);
        cos = new double[n]; sin = new double[n];
        for (int k = 0; k < n; k++) { cos[k] = Math.cos(-2 * Math.PI * k / n); sin[k] = Math.sin(-2 * Math.PI * k / n); }
        yr = new double[n]; yi = new double[n]; tr = new double[32]; ti = new double[32];
    }

    /** In-place forward transform (no scaling). */
    void forward(double[] re, double[] im) {
        rec(re, im, 0, 1, yr, yi, 0, n, 0);
        System.arraycopy(yr, 0, re, 0, n);
        System.arraycopy(yi, 0, im, 0, n);
    }

    private void rec(double[] xr, double[] xi, int xo, int xs, double[] outR, double[] outI, int yo, int len, int fi) {
        if (len == 1) { outR[yo] = xr[xo]; outI[yo] = xi[xo]; return; }
        int p = factors[fi], m = len / p;
        for (int q = 0; q < p; q++) rec(xr, xi, xo + q * xs, xs * p, outR, outI, yo + q * m, m, fi + 1);
        final int step = n / len;                       // W_len^j = W_n^(j*step)
        double[] zr = new double[p], zi = new double[p];
        for (int k = 0; k < m; k++) {
            for (int q = 0; q < p; q++) {
                int idx = (q * k * step) % n;
                double a = outR[yo + q * m + k], b = outI[yo + q * m + k];
                zr[q] = a * cos[idx] - b * sin[idx];
                zi[q] = a * sin[idx] + b * cos[idx];
            }
            for (int r = 0; r < p; r++) {
                double sr = 0, si = 0;
                for (int q = 0; q < p; q++) {
                    int idx = (int) (((long) q * r * (n / p)) % n);   // W_p^(q r)
                    sr += zr[q] * cos[idx] - zi[q] * sin[idx];
                    si += zr[q] * sin[idx] + zi[q] * cos[idx];
                }
                tr[r] = sr; ti[r] = si;
            }
            for (int r = 0; r < p; r++) { outR[yo + k + r * m] = tr[r]; outI[yo + k + r * m] = ti[r]; }
        }
    }
}
