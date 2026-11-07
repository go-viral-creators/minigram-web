package com.kafisir.wetalks.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Runs the downloads that [MediaPolicy] allows to start by themselves (voice messages, videos, audio, documents), two at a time.
 * When the network or the person's settings change, a download that is no longer allowed is stopped; one the person asked for with
 * a tap ([promote]) is never stopped.
 */
object AutoFetch {
    /** Media id (or key) -> 0..1 for every download in progress, so the chat can show it. */
    val progress = mutableStateMapOf<String, Float>()
    /** Goes up each time one finishes, so a screen that cached "is it on the phone?" asks again. */
    var doneTick by mutableIntStateOf(0); private set

    private class Run(val job: Job, var explicit: Boolean, val type: MediaPolicy.Type, val size: Long, val mine: Boolean)
    private val runs = HashMap<String, Run>()
    private val gate = Semaphore(2)
    private var watching = false

    private fun watch() {
        if (watching) return; watching = true
        Repo.scope.launch {
            snapshotFlow { MediaNet.state to DataPrefs.autoRules }.collect { (net, rules) ->
                val stop = synchronized(runs) { runs.filter { (_, r) -> !MediaPolicy.keepGoing(r.explicit, r.type, r.size, net, rules, r.mine) }.keys.toList() }
                for (k in stop) cancel(k)
            }
        }
    }

    fun running(key: String) = synchronized(runs) { runs.containsKey(key) }

    /** The person tapped a download that was already running by itself: from now on it carries on whatever the network does. */
    fun promote(key: String) { synchronized(runs) { runs[key]?.explicit = true } }

    fun cancel(key: String) {
        val r = synchronized(runs) { runs.remove(key) }
        r?.job?.cancel(); progress.remove(key)
    }

    /** Starts [run] unless the same key is already running. Failures are silent: the chat simply keeps its tap-to-download tile. */
    fun start(key: String, type: MediaPolicy.Type, size: Long, mine: Boolean, run: suspend (onProgress: (Float) -> Unit) -> Unit) {
        watch()
        synchronized(runs) {
            if (runs.containsKey(key)) return
            progress[key] = 0.03f
            val job = Repo.scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    gate.withPermit {
                        // the network may have changed while this waited for its turn
                        if (!MediaPolicy.keepGoing(false, type, size, MediaNet.refresh(), DataPrefs.autoRules, mine) && !(synchronized(runs) { runs[key]?.explicit } ?: false)) return@withPermit
                        run { p -> progress[key] = p.coerceIn(0.03f, 1f) }
                    }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { }
                finally { synchronized(runs) { runs.remove(key) }; progress.remove(key); doneTick++ }
            }
            runs[key] = Run(job, false, type, size, mine)
            job.start()
        }
    }
}
