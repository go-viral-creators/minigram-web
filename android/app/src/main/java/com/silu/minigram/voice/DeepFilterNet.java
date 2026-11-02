package com.silu.minigram.voice;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * DeepFilterNet3 (Schroeter et al., MIT/Apache-2.0) running on ONNX Runtime, as an offline (whole voice note) enhancer.
 *
 * The signal path follows the reference implementation in libDF: 48 kHz, 960-point Vorbis-window STFT with a 480 hop,
 * 32 ERB bands + 96 complex "deep filter" bins as features, the three exported networks (encoder, ERB-mask decoder,
 * deep-filter decoder), then the mask on all bins, a 5-tap complex filter on the lowest 96 bins and overlap-add
 * synthesis. The networks are causal with 2 frames of look-ahead, so output frame j uses model time step j+2.
 * Long clips are processed in segments (with warm-up context) to bound memory.
 */
public final class DeepFilterNet implements Denoiser {
    static final int SR = 48000, FFT = 960, HOP = 480, NF = FFT / 2 + 1, NB_ERB = 32, NB_DF = 96, ORDER = 5, LA = 2;
    private static final int SEG = 1500, CTX = 100;                 // frames per segment / warm-up frames
    private static final float MIN_DB = -10f, MAX_ERB_DB = 30f, MAX_DF_DB = 20f;

    private final OrtEnvironment env;
    private final OrtSession enc, erbDec, dfDec;
    private final int[] erb;
    private final double[] window = new double[FFT];
    private final double wnorm = 1.0 / ((double) FFT * FFT / (2 * HOP));
    private final double alpha = Math.exp(-(double) HOP / SR / 1.0);
    private final Fft fft = new Fft(FFT);

    public DeepFilterNet(byte[] encModel, byte[] erbDecModel, byte[] dfDecModel) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(2);
        enc = env.createSession(encModel, o);
        erbDec = env.createSession(erbDecModel, o);
        dfDec = env.createSession(dfDecModel, o);
        erb = erbBands();
        for (int i = 0; i < FFT; i++) {
            double s = Math.sin(0.5 * Math.PI * (i + 0.5) / (FFT / 2));
            window[i] = Math.sin(0.5 * Math.PI * s * s);
        }
    }

    @Override public String name() { return "deepfilternet3"; }

    public void close() { try { enc.close(); erbDec.close(); dfDec.close(); } catch (OrtException ignored) {} }

    // ------------------------------------------------------------------ ERB bands (libDF erb_fb)
    private static double freq2erb(double f) { return 9.265 * Math.log1p(f / (24.7 * 9.265)); }
    private static double erb2freq(double n) { return 24.7 * 9.265 * (Math.exp(n / 9.265) - 1); }
    static int[] erbBands() {
        double fw = (double) SR / FFT, lo = freq2erb(0), hi = freq2erb(SR / 2.0), step = (hi - lo) / NB_ERB;
        int[] e = new int[NB_ERB]; int prev = 0, over = 0, minF = 2;
        for (int i = 1; i <= NB_ERB; i++) {
            int fb = (int) Math.round(erb2freq(lo + i * step) / fw);
            int n = fb - prev - over;
            if (n < minF) { over = minF - n; n = minF; } else over = 0;
            e[i - 1] = n; prev = fb;
        }
        e[NB_ERB - 1] += 1;
        int sum = 0; for (int v : e) sum += v;
        if (sum > NF) e[NB_ERB - 1] -= sum - NF;
        return e;
    }

    // ------------------------------------------------------------------ STFT
    private final double[] br = new double[FFT], bi = new double[FFT];

    /** Frame k covers samples [(k-1)*HOP, (k+1)*HOP); zero outside the signal. Output re/im of NF bins, scaled by wnorm. */
    private void stft(float[] x, int k, float[] re, float[] im) {
        int start = (k - 1) * HOP;
        for (int i = 0; i < FFT; i++) {
            int idx = start + i;
            br[i] = (idx >= 0 && idx < x.length) ? x[idx] * window[i] : 0;
            bi[i] = 0;
        }
        fft.forward(br, bi);
        for (int f = 0; f < NF; f++) { re[f] = (float) (br[f] * wnorm); im[f] = (float) (bi[f] * wnorm); }
    }

    /** Unnormalised inverse real FFT of a half spectrum, windowed. Returns FFT samples in {@code out}. */
    private void istft(float[] re, float[] im, double[] out) {
        for (int f = 0; f < NF; f++) { br[f] = re[f]; bi[f] = -im[f]; }                       // conj(X[f])
        for (int f = 1; f < FFT / 2; f++) { br[FFT - f] = re[f]; bi[FFT - f] = im[f]; }       // conj(X[N-f]) = X[f]
        fft.forward(br, bi);
        for (int i = 0; i < FFT; i++) out[i] = br[i] * window[i];
    }

    // ------------------------------------------------------------------ main
    @Override public float[] process(float[] x) throws OrtException {
        final int n = x.length;
        if (n < HOP) return x;
        final int frames = (n + HOP - 1) / HOP + LA + 2;

        // 1) features for every frame, sequentially (the normalisation is a running average)
        float[] featErb = new float[frames * NB_ERB];
        float[] featSpec = new float[frames * 2 * NB_DF];      // [frame][re(96) | im(96)]
        float[] re = new float[NF], im = new float[NF];
        float[] meanState = new float[NB_ERB];
        for (int i = 0; i < NB_ERB; i++) meanState[i] = -60f + (-90f + 60f) * i / (NB_ERB - 1);
        float[] unitState = new float[NB_DF];
        for (int i = 0; i < NB_DF; i++) unitState[i] = 0.001f + (0.0001f - 0.001f) * i / (NB_DF - 1);
        for (int k = 0; k < frames; k++) {
            stft(x, k, re, im);
            int o = 0;
            for (int b = 0; b < NB_ERB; b++) {
                double s = 0;
                for (int j = 0; j < erb[b]; j++) s += re[o + j] * re[o + j] + im[o + j] * im[o + j];
                float e = (float) (Math.log10(s / erb[b] + 1e-10) * 10.0);
                meanState[b] = (float) (e * (1 - alpha) + meanState[b] * alpha);
                featErb[k * NB_ERB + b] = (e - meanState[b]) / 40f;
                o += erb[b];
            }
            for (int f = 0; f < NB_DF; f++) {
                float mag = (float) Math.hypot(re[f], im[f]);
                unitState[f] = (float) (mag * (1 - alpha) + unitState[f] * alpha);
                float d = (float) Math.sqrt(unitState[f]);
                featSpec[k * 2 * NB_DF + f] = re[f] / d;
                featSpec[k * 2 * NB_DF + NB_DF + f] = im[f] / d;
            }
        }

        // 2) networks + enhancement, segment by segment
        float[] out = new float[(frames) * HOP];
        double[] smem = new double[FFT - HOP];
        double[] frameOut = new double[FFT];
        final int total = frames - LA;                                   // output frames 0 .. total-1
        for (int a = 0; a < total; a += SEG) {
            int b = Math.min(total, a + SEG);
            int ctx = Math.min(CTX, a);
            int t0 = a - ctx, t1 = b + LA;                               // model time steps [t0, t1)
            int s = t1 - t0;
            float[] fe = new float[s * NB_ERB], fs = new float[s * 2 * NB_DF];
            System.arraycopy(featErb, t0 * NB_ERB, fe, 0, s * NB_ERB);
            // feat_spec layout for the network is [1, 2, S, 96]: channel-major
            for (int t = 0; t < s; t++) {
                System.arraycopy(featSpec, (t0 + t) * 2 * NB_DF, fs, t * NB_DF, NB_DF);                    // real
                System.arraycopy(featSpec, (t0 + t) * 2 * NB_DF + NB_DF, fs, (s + t) * NB_DF, NB_DF);      // imag
            }
            float[][] mask = new float[s][];
            float[] lsnr = new float[s];
            float[] coefs;
            Map<String, OnnxTensor> in = new HashMap<>();
            in.put("feat_erb", OnnxTensor.createTensor(env, FloatBuffer.wrap(fe), new long[]{1, 1, s, NB_ERB}));
            in.put("feat_spec", OnnxTensor.createTensor(env, FloatBuffer.wrap(fs), new long[]{1, 2, s, NB_DF}));
            try (OrtSession.Result r = enc.run(in)) {
                OnnxTensor emb = (OnnxTensor) r.get("emb").get();
                Map<String, OnnxTensor> din = new HashMap<>();
                din.put("emb", emb);
                din.put("e3", (OnnxTensor) r.get("e3").get()); din.put("e2", (OnnxTensor) r.get("e2").get());
                din.put("e1", (OnnxTensor) r.get("e1").get()); din.put("e0", (OnnxTensor) r.get("e0").get());
                float[] ls = new float[s];
                ((OnnxTensor) r.get("lsnr").get()).getFloatBuffer().get(ls);
                lsnr = ls;
                try (OrtSession.Result m = erbDec.run(din)) {
                    FloatBuffer mb = ((OnnxTensor) m.get(0)).getFloatBuffer();
                    for (int t = 0; t < s; t++) { mask[t] = new float[NB_ERB]; mb.get(mask[t]); }
                }
                Map<String, OnnxTensor> fin = new HashMap<>();
                fin.put("emb", emb); fin.put("c0", (OnnxTensor) r.get("c0").get());
                try (OrtSession.Result c = dfDec.run(fin)) {
                    FloatBuffer cb = ((OnnxTensor) c.get(0)).getFloatBuffer();
                    coefs = new float[s * NB_DF * ORDER * 2];
                    cb.get(coefs);
                }
            } finally {
                for (OnnxTensor t : in.values()) t.close();
            }

            // spectra of frames [a-(ORDER-1)+LA.., ...]: noisy taps j-2 .. j+2 for j in [a, b)
            int sp0 = a - 2, sp1 = b + 2;
            int sn = sp1 - sp0;
            float[][] sre = new float[sn][NF], sim = new float[sn][NF];
            for (int q = 0; q < sn; q++) {
                int k = sp0 + q;
                if (k >= 0 && k < frames) stft(x, k, sre[q], sim[q]);
            }
            float[] er = new float[NF], ei = new float[NF];
            for (int j = a; j < b; j++) {
                int t = j + LA - t0;
                float l = lsnr[t];
                float[] yr = sre[j - sp0], yi = sim[j - sp0];
                System.arraycopy(yr, 0, er, 0, NF); System.arraycopy(yi, 0, ei, 0, NF);
                boolean gains, zero = false, df = false;
                if (l < MIN_DB) { gains = true; zero = true; }
                else if (l > MAX_ERB_DB) gains = false;
                else { gains = true; df = l <= MAX_DF_DB; }
                if (gains) {
                    int o = 0;
                    for (int bnd = 0; bnd < NB_ERB; bnd++) {
                        float g = zero ? 0f : mask[t][bnd];
                        for (int q = 0; q < erb[bnd]; q++) { er[o + q] *= g; ei[o + q] *= g; }
                        o += erb[bnd];
                    }
                }
                if (df) {
                    for (int f = 0; f < NB_DF; f++) { er[f] = 0; ei[f] = 0; }
                    for (int i = 0; i < ORDER; i++) {
                        int idx = (j - (ORDER - 1) + LA + i) - sp0;      // frames j-2 .. j+2
                        if (idx < 0 || idx >= sn) continue;
                        float[] pr = sre[idx], pi = sim[idx];
                        int base = (t * NB_DF) * ORDER * 2;
                        for (int f = 0; f < NB_DF; f++) {
                            float cr = coefs[base + (f * ORDER + i) * 2], ci = coefs[base + (f * ORDER + i) * 2 + 1];
                            er[f] += pr[f] * cr - pi[f] * ci;
                            ei[f] += pr[f] * ci + pi[f] * cr;
                        }
                    }
                }
                istft(er, ei, frameOut);
                for (int i = 0; i < HOP; i++) out[j * HOP + i] = (float) (frameOut[i] + smem[i]);
                for (int i = 0; i < FFT - HOP; i++) smem[i] = frameOut[HOP + i];
            }
        }
        float[] res = new float[n];
        System.arraycopy(out, HOP, res, 0, Math.min(n, out.length - HOP));    // synthesis is one hop late
        return res;
    }
}
