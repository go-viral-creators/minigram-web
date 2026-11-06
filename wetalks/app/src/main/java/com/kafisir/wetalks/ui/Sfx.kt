package com.kafisir.wetalks.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Small interface sounds (message sent / received, upload done, voice play, delete, pop-up, call end).
 * No audio files are shipped: each sound is a few milliseconds of tone made here, once, then played through SoundPool.
 * They are skipped when the phone is on silent or vibrate, and can be switched off in Chat settings.
 */
object Sfx {
    private class Note(val f0: Float, val f1: Float, val ms: Int, val vol: Float = 1f)
    private val SOUNDS: Map<String, List<Note>> = mapOf(
        "send" to listOf(Note(740f, 880f, 55, .55f), Note(988f, 1180f, 70, .5f)),
        "recv" to listOf(Note(660f, 660f, 60, .5f), Note(880f, 880f, 90, .5f)),
        "done" to listOf(Note(523f, 523f, 55, .5f), Note(659f, 659f, 55, .5f), Note(784f, 784f, 110, .5f)),
        "play" to listOf(Note(1000f, 1100f, 35, .4f)),
        "delete" to listOf(Note(640f, 320f, 140, .5f)),
        "pop" to listOf(Note(1250f, 1250f, 22, .22f)),
        "callend" to listOf(Note(520f, 520f, 120, .7f), Note(390f, 390f, 150, .7f)),
    )
    private const val RATE = 22050

    @Volatile var enabled = true
    private var app: Context? = null
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val loaded = HashSet<Int>()
    private val last = HashMap<String, Long>()

    fun load(c: Context) { enabled = c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).getBoolean("sfx", true) }
    fun set(c: Context, v: Boolean) { enabled = v; c.getSharedPreferences("wt_ui", Context.MODE_PRIVATE).edit().putBoolean("sfx", v).apply() }

    fun init(c: Context) {
        if (pool != null) return
        val ctx = c.applicationContext; app = ctx
        load(ctx)
        Thread {
            try {
                val sp = SoundPool.Builder().setMaxStreams(3)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
                sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
                val dir = File(ctx.cacheDir, "sfx").apply { mkdirs() }
                for ((name, notes) in SOUNDS) {
                    val f = File(dir, "$name.v1.wav")
                    if (!f.exists()) FileOutputStream(f).use { it.write(wav(notes)) }
                    ids[name] = sp.load(f.absolutePath, 1)
                }
                pool = sp
            } catch (_: Exception) {}
        }.start()
    }

    /** Plays [name] unless sounds are off, the phone is silent, or the same sound just played. */
    fun play(name: String) {
        val c = app ?: return
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - (last[name] ?: 0L) < 120) return
        last[name] = now
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val id = ids[name] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        pool?.play(id, .8f, .8f, 1, 0, 1f)
    }

    fun vibrate(ms: Long = 45) {
        val c = app ?: return
        if (!enabled) return
        try {
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {}
    }

    /** Call finished: a light buzz and the two falling beeps. */
    fun callEnd() { play("callend"); vibrate(70) }

    private fun wav(notes: List<Note>): ByteArray {
        val pcm = ArrayList<Short>()
        for (n in notes) {
            val count = RATE * n.ms / 1000; var phase = 0.0
            for (i in 0 until count) {
                val t = i.toDouble() / count
                val f = n.f0 + (n.f1 - n.f0) * t
                phase += 2 * PI * f / RATE
                val attack = minOf(1.0, i / (RATE * 0.004))                  // 4 ms fade-in: no click
                val decay = exp(-3.2 * t)                                    // soft tail
                pcm += (sin(phase) * attack * decay * n.vol * 30000).toInt().toShort()
            }
            repeat(RATE / 200) { pcm += 0 }                                  // 5 ms gap between notes
        }
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { pcm.forEach { putShort(it) } }.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        return h.array() + data
    }
}
