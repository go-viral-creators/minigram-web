package com.silu.minigram.voice;

import android.content.Context;

/** The speech-enhancement stage used for every voice note (spectral noise suppression, no model download needed). */
final class DenoiserProvider {
    private DenoiserProvider() {}
    static Denoiser get(Context c) { return new SpectralDenoiser(); }
}
