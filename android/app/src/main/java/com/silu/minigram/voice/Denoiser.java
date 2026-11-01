package com.silu.minigram.voice;

/** Speech-enhancement stage working on 48 kHz mono float PCM in [-1, 1]. */
public interface Denoiser {
    /** @return enhanced audio, same length. Must not throw for valid input; may return the input unchanged. */
    float[] process(float[] pcm48k) throws Exception;
    String name();
}
