package com.kafisir.wetalks.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Pure: which focus changes should interrupt a recording (ducking is not one, we hold exclusive focus). */
object FocusRules {
    const val LOSS = -1; const val LOSS_TRANSIENT = -2          // AudioManager.AUDIOFOCUS_LOSS / _LOSS_TRANSIENT
    fun interrupts(change: Int): Boolean = change == LOSS || change == LOSS_TRANSIENT
}

/**
 * Audio focus + route watching for one recording. `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` asks well-behaved apps to pause
 * their music/video (they may not duck, which would still leak into the mic). It is given back in [release], so other
 * media can resume. Another app taking focus (call, alarm), a headset being unplugged and a microphone (wired, USB,
 * Bluetooth) disappearing all report through [onInterrupt] on the main thread.
 */
class RecordFocus(private val ctx: Context, private val onInterrupt: (String) -> Unit) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var req: AudioFocusRequest? = null
    private var held = false
    private var noisy: BroadcastReceiver? = null
    private var devices: AudioDeviceCallback? = null

    fun acquire(): Boolean {
        if (held) return true
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                if (held && FocusRules.interrupts(change)) onInterrupt(if (change == FocusRules.LOSS) "Another app took over the audio" else "A call or alarm interrupted the recording")
            }, main).build()
        req = r
        // if focus is refused we still record (the call check is what blocks recording during a call)
        held = am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        watchRoute()
        return held
    }

    private fun watchRoute() {
        if (noisy != null) return
        val rc = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { onInterrupt("Headphones were unplugged") } }
        noisy = try { ctx.registerReceiver(rc, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); rc } catch (_: Throwable) { null }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.isSource && it.type in MIC_TYPES }) onInterrupt("The headset microphone was disconnected")
            }
        }
        devices = try { am.registerAudioDeviceCallback(cb, main); cb } catch (_: Throwable) { null }
    }

    /** Safe to call any number of times. */
    fun release() {
        held = false
        try { noisy?.let { ctx.unregisterReceiver(it) } } catch (_: Throwable) {}
        noisy = null
        try { devices?.let { am.unregisterAudioDeviceCallback(it) } } catch (_: Throwable) {}
        devices = null
        try { req?.let { am.abandonAudioFocusRequest(it) } } catch (_: Throwable) {}
        req = null
    }

    private companion object {
        val MIC_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    }
}
