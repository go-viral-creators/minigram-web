package com.silu.minigram.voice;

import static org.junit.Assert.*;

import org.junit.Test;

public class DspTest {
    static float[] sine(double hz, double amp, double sec, int fs) {
        float[] x = new float[(int) (sec * fs)];
        for (int i = 0; i < x.length; i++) x[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / fs));
        return x;
    }

    @Test public void lufsOfFullScaleSineMatchesBs1770Reference() {
        // BS.1770: a 997 Hz sine at -3.01 dBFS peak (RMS -6.02 dBFS) is about -3.0 LUFS... verify against the formula:
        float[] x = sine(997, 1.0, 5, 48000);
        double l = Dsp.integratedLufs(x, 48000);
        assertEquals(-3.7, l, 0.5);        // 0 dBFS-peak sine = -3.01 dB RMS, K-weighting ~ +0.7 dB at 1 kHz, offset -0.691
    }

    @Test public void normalizeReachesTargetWithinTolerance() {
        float[] x = sine(440, 0.05, 6, 48000);
        Dsp.normalize(x, 48000, -16);
        assertEquals(-16, Dsp.integratedLufs(x, 48000), 0.6);
        assertTrue(Dsp.peak(x) <= 0.9f);
    }

    @Test public void loudInputIsTurnedDownButNeverClips() {
        float[] x = sine(300, 1.0, 5, 48000);
        Dsp.normalize(x, 48000, -16);
        assertTrue("peak " + Dsp.peak(x), Dsp.peak(x) <= 0.9f);
        assertTrue(Dsp.integratedLufs(x, 48000) < -10);
    }

    @Test public void limiterHoldsCeiling() {
        float[] x = sine(200, 3.0, 2, 48000);      // way over full scale
        Dsp.limit(x, 48000);
        assertTrue("peak " + Dsp.peak(x), Dsp.peak(x) <= 0.8913f + 1e-3f);
    }

    @Test public void silenceStaysSilent() {
        float[] x = new float[48000];
        assertEquals(0, Dsp.normalize(x, 48000, -16), 0);
        assertEquals(0f, Dsp.peak(x), 0f);
    }

    @Test public void highPassRemovesDcAndRumbleKeepsSpeechBand() {
        float[] low = sine(30, 0.5, 3, 48000), mid = sine(1000, 0.5, 3, 48000);
        Dsp.speechEq(low, 48000); Dsp.speechEq(mid, 48000);
        float pl = 0, pm = 0;
        for (int i = 48000; i < low.length; i++) { pl = Math.max(pl, Math.abs(low[i])); pm = Math.max(pm, Math.abs(mid[i])); }
        assertTrue("30 Hz should be strongly attenuated: " + pl, pl < 0.25f);
        assertTrue("1 kHz should pass: " + pm, pm > 0.4f);
    }
}
