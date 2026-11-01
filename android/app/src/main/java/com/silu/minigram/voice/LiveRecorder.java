package com.silu.minigram.voice;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Captures 48 kHz mono 16-bit PCM on a background thread, applies the platform's voice effects
 * (noise suppressor, automatic gain control, echo canceller - the same three families WebRTC's audio
 * processing module provides) when the device offers them, streams raw samples to a temp file and
 * reports a smoothed level (0..1) for the live waveform.
 */
public final class LiveRecorder {
    public static final int SAMPLE_RATE = 48000;
    public interface Listener { void onLevel(float level); void onError(Exception e); }

    private final File rawFile;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running, paused;
    private volatile long samples;
    private Thread thread;
    private AudioRecord rec;
    private NoiseSuppressor ns; private AutomaticGainControl agc; private AcousticEchoCanceler aec;
    public boolean nsActive, agcActive, aecActive;

    public LiveRecorder(File rawFile, Listener l) { this.rawFile = rawFile; this.listener = l; }

    @SuppressLint("MissingPermission")
    public void start() throws IOException {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("48 kHz capture not supported");
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE / 5 * 2));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); throw new IOException("microphone unavailable"); }
        int sid = rec.getAudioSessionId();
        try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(sid); if (ns != null) { ns.setEnabled(true); nsActive = true; } } } catch (Throwable ignored) { }
        try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(sid); if (agc != null) { agc.setEnabled(true); agcActive = true; } } } catch (Throwable ignored) { }
        try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sid); if (aec != null) { aec.setEnabled(true); aecActive = true; } } } catch (Throwable ignored) { }

        running = true;
        rec.startRecording();
        thread = new Thread(() -> {
            short[] buf = new short[SAMPLE_RATE / 50];           // 20 ms frames
            byte[] bytes = new byte[buf.length * 2];
            float smooth = 0;
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(rawFile), 1 << 16)) {
                while (running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    if (paused) continue;
                    double sum = 0;
                    for (int i = 0; i < n; i++) {
                        short v = buf[i];
                        bytes[2 * i] = (byte) v; bytes[2 * i + 1] = (byte) (v >> 8);
                        sum += (double) v * v;
                    }
                    out.write(bytes, 0, n * 2);
                    samples += n;
                    float rms = (float) (Math.sqrt(sum / n) / 32768.0);
                    float lvl = Math.min(1f, (float) Math.pow(rms * 6f, 0.6));
                    smooth = lvl > smooth ? lvl : smooth * 0.8f + lvl * 0.2f;
                    final float s = smooth;
                    main.post(() -> listener.onLevel(s));
                }
            } catch (Exception e) {
                main.post(() -> listener.onError(e));
            }
        }, "voice-capture");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void setPaused(boolean p) { paused = p; }
    public boolean isPaused() { return paused; }
    public long samples() { return samples; }

    /** Stops capture and waits for the file to be flushed. @return number of samples recorded. */
    public long stop() {
        running = false;
        try { if (thread != null) thread.join(1500); } catch (InterruptedException ignored) { }
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Throwable ignored) { }
        for (android.media.audiofx.AudioEffect e : new android.media.audiofx.AudioEffect[]{ns, agc, aec}) {
            try { if (e != null) e.release(); } catch (Throwable ignored) { }
        }
        return samples;
    }
}
