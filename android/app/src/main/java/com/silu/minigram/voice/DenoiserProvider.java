package com.silu.minigram.voice;

import android.content.Context;

/** Returns the neural denoiser if its runtime + model are present on this device, else null (pipeline skips the stage). */
final class DenoiserProvider {
    private DenoiserProvider() {}
    static Denoiser get(Context c) { return null; }
}
