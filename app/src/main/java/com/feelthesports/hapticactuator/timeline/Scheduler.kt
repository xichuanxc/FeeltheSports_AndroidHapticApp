package com.feelthesports.hapticactuator.timeline

import android.util.Log
import com.feelthesports.hapticactuator.clock.MediaClock
import com.feelthesports.hapticactuator.haptic.HapticPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Scheduler"
private const val STALE_THRESHOLD_S = 0.1   // skip events more than 100 ms in the past

class Scheduler(private val player: HapticPlayer) {

    var strengthScale: Float = 1.0f
    var minIntensity: Float = 0.15f

    private var job: Job? = null

    /**
     * Schedule [timeline] driven by [mediaClock].
     * Skips events already behind the clock; fires the rest at their correct moment.
     */
    fun start(timeline: Timeline, scope: CoroutineScope, mediaClock: MediaClock) {
        job?.cancel()

        val startIndex = timeline.indexFrom(mediaClock.mediaTime())
        if (startIndex >= timeline.events.size) return

        job = scope.launch(Dispatchers.Default) {
            for (i in startIndex until timeline.events.size) {
                if (!isActive) break
                val event = timeline.events[i]

                val rate = mediaClock.rate.coerceAtLeast(0.01)
                val deltaMediaS = event.time - mediaClock.mediaTime()
                val delayMs = (deltaMediaS / rate * 1_000.0).toLong()

                if (delayMs > 1L) delay(delayMs)
                if (!isActive) break

                // Skip if we woke up too late (e.g. thread was slow to schedule)
                if (event.time < mediaClock.mediaTime() - STALE_THRESHOLD_S) {
                    Log.v(TAG, "skipped stale event t=${event.time}")
                    continue
                }

                val scaled = (event.intensity * strengthScale).coerceIn(0f, 1f)
                if (scaled < minIntensity) continue

                withContext(Dispatchers.Main) { player.play(event.type, scaled) }
                Log.v(TAG, "fired ${event.type} scaled=${"%.2f".format(scaled)} t=${event.time}")
            }
            Log.d(TAG, "schedule complete")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
