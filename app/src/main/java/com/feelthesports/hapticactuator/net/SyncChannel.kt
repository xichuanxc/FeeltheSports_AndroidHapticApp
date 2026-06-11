package com.feelthesports.hapticactuator.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException

private const val TAG = "SyncChannel"
private const val RECEIVE_TIMEOUT_MS = 500    // lets the loop check isActive periodically
private const val BUFFER_SIZE = 1024

class SyncChannel {

    private val socket = DatagramSocket().apply { soTimeout = RECEIVE_TIMEOUT_MS }

    /** The OS-assigned UDP port; include this in the hello message. */
    val port: Int = socket.localPort

    var onSyncPulse: ((mediaT: Double, tServerNs: Long, rate: Double) -> Unit)? = null

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "UDP listener started on port $port")
            val buf    = ByteArray(BUFFER_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    socket.receive(packet)
                    val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (json.optString("msg") == "sync") {
                        val mediaT    = json.getDouble("media_t")
                        val tServerNs = json.getLong("t_server_ns")
                        val rate      = json.optDouble("rate", 1.0)
                        Log.v(TAG, "sync  media_t=$mediaT  rate=$rate")
                        withContext(Dispatchers.Main) { onSyncPulse?.invoke(mediaT, tServerNs, rate) }
                    }
                } catch (_: SocketTimeoutException) {
                    // expected — just loop back and check isActive
                } catch (_: SocketException) {
                    break  // socket was closed via close()
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "UDP parse error: ${e.message}")
                }
            }
            Log.d(TAG, "UDP listener stopped")
        }
    }

    fun close() {
        job?.cancel()
        socket.close()
    }
}
