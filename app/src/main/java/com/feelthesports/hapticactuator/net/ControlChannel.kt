package com.feelthesports.hapticactuator.net

import android.util.Log
import com.feelthesports.hapticactuator.haptic.HapticCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket

private const val TAG = "ControlChannel"
private const val CLIENT_VERSION = "0.1"
private const val MAX_MESSAGE_BYTES = 10_000_000

class ControlChannel(
    private val host: InetAddress,
    private val port: Int,
    private val capabilities: HapticCapabilities,
    val udpPort: Int = 0,
) {
    var onConnected: (() -> Unit)? = null
    var onTimeline: ((JSONObject) -> Unit)? = null
    var onPlay: ((mediaT: Double) -> Unit)? = null
    var onPause: ((mediaT: Double) -> Unit)? = null
    var onSeek: ((mediaT: Double) -> Unit)? = null
    var onRate: ((rate: Double) -> Unit)? = null
    var onTimeResp: ((t0ClientNs: Long, tServerNs: Long, t1ClientNs: Long) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val sendMutex = Mutex()
    private var job: Job? = null

    fun connect(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                Socket(host, port).use { sock ->
                    socket = sock
                    val out = DataOutputStream(sock.getOutputStream())
                    val inp = DataInputStream(sock.getInputStream())
                    output = out

                    writeMessage(out, buildHello())
                    Log.d(TAG, "Connected to $host:$port, hello sent")
                    withContext(Dispatchers.Main) { onConnected?.invoke() }

                    while (isActive) {
                        val msg = readMessage(inp) ?: break
                        dispatch(msg)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection ended: ${e.message}")
            } finally {
                output = null
                socket = null
                withContext(Dispatchers.Main) { onDisconnected?.invoke() }
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        socket?.close()
    }

    suspend fun send(json: JSONObject) {
        sendMutex.withLock {
            output?.let { writeMessage(it, json) }
        }
    }

    private suspend fun dispatch(msg: JSONObject) {
        when (val type = msg.optString("msg")) {
            "timeline" -> {
                val data = msg.optJSONObject("data") ?: return
                Log.d(TAG, "Timeline received: ${data.optJSONArray("events")?.length() ?: 0} events")
                withContext(Dispatchers.Main) { onTimeline?.invoke(data) }
            }
            "play"  -> withContext(Dispatchers.Main) { onPlay?.invoke(msg.getDouble("media_t")) }
            "pause" -> withContext(Dispatchers.Main) { onPause?.invoke(msg.getDouble("media_t")) }
            "seek"  -> withContext(Dispatchers.Main) { onSeek?.invoke(msg.getDouble("media_t")) }
            "rate"  -> withContext(Dispatchers.Main) { onRate?.invoke(msg.getDouble("rate")) }
            "time_resp" -> {
                val t1 = System.nanoTime()   // capture on IO thread, before Main dispatch
                withContext(Dispatchers.Main) {
                    onTimeResp?.invoke(msg.getLong("t0_client_ns"), msg.getLong("t_server_ns"), t1)
                }
            }
            else -> Log.w(TAG, "Unknown message type: $type")
        }
    }

    private fun buildHello(): JSONObject {
        val primitives = JSONArray().apply {
            capabilities.supportedPrimitives.forEach { put(it) }
        }
        return JSONObject()
            .put("msg", "hello")
            .put("client", "android-haptic")
            .put("client_version", CLIENT_VERSION)
            .put("udp_port", udpPort)
            .put("capabilities", JSONObject()
                .put("amplitude_control", capabilities.hasAmplitudeControl)
                .put("primitives", primitives)
                .put("vibrator_api", capabilities.apiLevel)
            )
    }

    companion object {
        // Exposed so ClockSync and future callers can share the same framing.
        fun writeMessage(out: DataOutputStream, json: JSONObject) {
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }

        fun readMessage(inp: DataInputStream): JSONObject? {
            return try {
                val len = inp.readInt()
                if (len <= 0 || len > MAX_MESSAGE_BYTES) return null
                val bytes = ByteArray(len)
                inp.readFully(bytes)
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                null
            }
        }
    }
}
