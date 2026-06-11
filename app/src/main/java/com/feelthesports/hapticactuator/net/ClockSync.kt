package com.feelthesports.hapticactuator.net

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "ClockSync"
private const val INTER_REQUEST_MS = 20L
private const val RESPONSE_TIMEOUT_MS = 2_000L

class ClockSync(private val controlChannel: ControlChannel) {

    // (rttNs, offsetNs) pairs posted here by onTimeResp, consumed by sync()
    private val responseQueue = Channel<Pair<Long, Long>>(Channel.UNLIMITED)

    /**
     * Called from ControlChannel.onTimeResp (on Main thread).
     * [t1ClientNs] is the receive timestamp recorded on the IO thread before dispatch.
     */
    fun onTimeResp(t0ClientNs: Long, tServerNs: Long, t1ClientNs: Long) {
        val rtt    = t1ClientNs - t0ClientNs
        val offset = tServerNs - (t0ClientNs + rtt / 2)
        responseQueue.trySend(Pair(rtt, offset))
    }

    /**
     * Run SNTP-style exchange: send [rounds] time_req messages, collect responses,
     * discard the high-RTT half, average the rest.
     * Returns clock_offset_ns to add to System.nanoTime() to get laptop server time.
     * Must be called from a coroutine (preferably Dispatchers.IO).
     */
    suspend fun sync(rounds: Int = 8): Long {
        // Drain any stale entries from a previous run
        while (!responseQueue.isEmpty) responseQueue.tryReceive()

        repeat(rounds) {
            val t0 = System.nanoTime()
            controlChannel.send(
                JSONObject()
                    .put("msg", "time_req")
                    .put("t0_client_ns", t0)
            )
            delay(INTER_REQUEST_MS)
        }

        val results = mutableListOf<Pair<Long, Long>>()
        repeat(rounds) {
            runCatching {
                withTimeout(RESPONSE_TIMEOUT_MS) { results.add(responseQueue.receive()) }
            }.onFailure { Log.w(TAG, "time_resp missing: ${it.message}") }
        }

        if (results.isEmpty()) {
            Log.w(TAG, "No responses — using offset 0")
            return 0L
        }

        val sorted  = results.sortedBy { it.first }            // ascending RTT
        val kept    = sorted.take(maxOf(1, sorted.size / 2))   // discard high-RTT half
        val offset  = kept.map { it.second }.average().toLong()
        val avgRttMs = "%.2f".format(kept.map { it.first }.average() / 1_000_000.0)

        Log.d(TAG, "offset=${offset / 1_000_000} ms, avgRTT=${avgRttMs} ms (${kept.size}/${rounds} kept)")
        return offset
    }
}
