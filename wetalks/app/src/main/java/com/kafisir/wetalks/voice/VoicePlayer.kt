package com.kafisir.wetalks.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kafisir.wetalks.data.ChatRef
import com.kafisir.wetalks.data.Msg
import com.kafisir.wetalks.data.Repo
import com.kafisir.wetalks.ui.Nav
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * One app-wide voice player. It lives outside any screen, so a voice note keeps playing while you scroll,
 * leave the chat or open another one; the mini-player bar controls it from anywhere.
 */
object VoicePlayer {
    var currentId by mutableStateOf<String?>(null); private set
    var ref by mutableStateOf<ChatRef?>(null); private set
    var title by mutableStateOf(""); private set
    var playing by mutableStateOf(false); private set
    var loadingId by mutableStateOf<String?>(null); private set
    var pos by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null

    fun toggle(ctx: Context, chat: ChatRef, m: Msg, who: String) {
        if (currentId == m.id && mp != null) { if (playing) pause() else resume(); return }
        stop()
        Repo.scope.launch {
            loadingId = m.id
            val bytes = try { m.mediaId?.let { Repo.loadMedia(chat, it) } } catch (e: Exception) { null }
            if (bytes == null) { loadingId = null; Nav.say("Could not load voice message"); return@launch }
            try {
                val f = File(ctx.cacheDir, "v_${m.id}.m4a"); f.writeBytes(bytes)
                val p = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); setOnCompletionListener { finished() } }
                mp = p; currentId = m.id; ref = chat; title = who; total = p.duration; pos = 0
                ctx.getSharedPreferences("wt_listened", Context.MODE_PRIVATE).edit().putBoolean(m.id, true).apply()
                p.start(); playing = true; applySpeed(); startTicker()
            } catch (e: Exception) { stop(); Nav.say("Could not play this voice message") }
            loadingId = null
        }
    }

    private fun startTicker() { ticker?.cancel(); ticker = Repo.scope.launch { while (playing) { pos = try { mp?.currentPosition ?: 0 } catch (e: Exception) { 0 }; delay(80) } } }
    private fun finished() { playing = false; pos = 0; try { mp?.seekTo(0) } catch (_: Exception) {}; stop() }
    fun pause() { try { mp?.pause() } catch (_: Exception) {}; playing = false }
    fun resume() { try { mp?.start() } catch (_: Exception) {}; playing = true; applySpeed(); startTicker() }
    fun seek(frac: Float) { mp?.let { val t = (frac * total).toInt(); try { it.seekTo(t) } catch (_: Exception) {}; pos = t } }
    fun cycleSpeed() { speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }; applySpeed() }
    private fun applySpeed() { try { mp?.let { if (playing) it.playbackParams = PlaybackParams().setSpeed(speed) } } catch (_: Exception) {} }
    fun stop() { ticker?.cancel(); try { mp?.release() } catch (_: Exception) {}; mp = null; playing = false; currentId = null; pos = 0; total = 0 }
}
