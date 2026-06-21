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
private const val STALE_THRESHOLD_S = 0.3   // skip events more than 300 ms in the past
private const val MAX_SLEEP_MS = 200L        // re-anchor to live clock at least this often
private const val BATCH_WINDOW_MS = 200      // batch events within 200 ms into one Composition

class Scheduler(private val player: HapticPlayer) {

    var strengthScale: Float = 1.0f
    var minIntensity: Float = 0.15f

    private var job: Job? = null

    /**
     * Schedule [timeline] driven by [mediaClock].
     * Skips stale events; batches close events into one Composition call so the
     * hardware engine handles their inter-timing without vibration cancellation.
     */
    fun start(timeline: Timeline, scope: CoroutineScope, mediaClock: MediaClock) {
        job?.cancel()

        val startIndex = timeline.indexFrom(mediaClock.mediaTime())
        if (startIndex >= timeline.events.size) return

        job = scope.launch(Dispatchers.Default) {
            var i = startIndex
            while (i < timeline.events.size && isActive) {
                val event = timeline.events[i]

                // Sleep in short chunks so the live clock stays fresh between events.
                while (isActive) {
                    val rate = mediaClock.rate.coerceAtLeast(0.01)
                    val remainingMs = ((event.time - mediaClock.mediaTime()) / rate * 1_000.0).toLong()
                    if (remainingMs <= 1L) break
                    delay(remainingMs.coerceAtMost(MAX_SLEEP_MS))
                }
                if (!isActive) break

                // Skip if we woke up too late.
                if (event.time < mediaClock.mediaTime() - STALE_THRESHOLD_S) {
                    Log.v(TAG, "skipped stale event t=${event.time}")
                    i++
                    continue
                }

                val firstScaled = (event.intensity * strengthScale).coerceIn(0f, 1f)
                if (firstScaled < minIntensity) {
                    Log.v(TAG, "skipped low-intensity ${event.visionType} raw=${event.intensity} scaled=${"%.2f".format(firstScaled)} t=${event.time}")
                    i++
                    continue
                }

                // Look ahead: collect events within BATCH_WINDOW_MS into one Composition.
                val batch = mutableListOf(HapticPlayer.BatchEvent(event.visionType, firstScaled, 0))
                var j = i + 1
                while (j < timeline.events.size) {
                    val next = timeline.events[j]
                    val gapMs = ((next.time - event.time) * 1_000.0).toInt()
                    if (gapMs > BATCH_WINDOW_MS) break
                    val scaled = (next.intensity * strengthScale).coerceIn(0f, 1f)
                    if (scaled >= minIntensity) {
                        batch.add(HapticPlayer.BatchEvent(next.visionType, scaled, gapMs))
                    }
                    j++
                }

                val batchDurationMs = player.estimateBatchDurationMs(batch)
                withContext(Dispatchers.Main) { player.playBatch(batch) }
                Log.v(TAG, "fired batch=${batch.size} t=${event.time} dur=${batchDurationMs}ms scaled=${"%.2f".format(firstScaled)}")

                // Guard: don't let the next vibrate() cancel this composition's tail.
                // Wait until the composition has played out before continuing.
                val compositionEndsAtS = event.time + batchDurationMs / 1_000.0
                val guardMs = ((compositionEndsAtS - mediaClock.mediaTime()) / mediaClock.rate.coerceAtLeast(0.01) * 1_000.0).toLong()
                if (guardMs > 1L) delay(guardMs.coerceAtMost(MAX_SLEEP_MS))

                i = j  // advance past all events consumed by this batch
            }
            Log.d(TAG, "schedule complete")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
