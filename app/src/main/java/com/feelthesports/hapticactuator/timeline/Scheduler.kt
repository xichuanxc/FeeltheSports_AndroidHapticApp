package com.feelthesports.hapticactuator.timeline

import android.util.Log
import com.feelthesports.hapticactuator.haptic.HapticPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Scheduler"

class Scheduler(private val player: HapticPlayer) {

    var strengthScale: Float = 1.0f
    var minIntensity: Float = 0.15f

    private var job: Job? = null

    /**
     * Schedule [timeline] with [startMediaT] mapped to the current moment.
     * Events before [startMediaT] are skipped. Default maps the first event to now,
     * which is the Step 5 "no clock sync" mode.
     */
    fun start(
        timeline: Timeline,
        scope: CoroutineScope,
        startMediaT: Double = timeline.events.firstOrNull()?.time ?: 0.0,
    ) {
        job?.cancel()
        val firstIndex = timeline.indexFrom(startMediaT)
        if (firstIndex >= timeline.events.size) return

        val anchorNs    = System.nanoTime()
        val anchorMedia = startMediaT

        job = scope.launch(Dispatchers.Default) {
            for (i in firstIndex until timeline.events.size) {
                if (!isActive) break
                val event    = timeline.events[i]
                val targetNs = anchorNs + ((event.time - anchorMedia) * 1_000_000_000.0).toLong()
                val delayMs  = (targetNs - System.nanoTime()) / 1_000_000L
                if (delayMs > 1L) delay(delayMs)
                if (!isActive) break

                val scaled = (event.intensity * strengthScale).coerceIn(0f, 1f)
                if (scaled < minIntensity) continue

                withContext(Dispatchers.Main) { player.play(event.type, scaled) }
                Log.v(TAG, "fired ${event.type} i=${scaled.format()} t=${event.time}")
            }
            Log.d(TAG, "schedule complete")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun Float.format() = "%.2f".format(this)
}
