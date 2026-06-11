package com.feelthesports.hapticactuator.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_TYPE = "_haptics._tcp"
private const val TAG = "Discovery"

class Discovery(context: Context) {

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveInFlight = AtomicBoolean(false)

    @Volatile private var active = false

    var onServiceResolved: ((host: InetAddress, port: Int, name: String) -> Unit)? = null
    var onServiceLost: (() -> Unit)? = null

    fun start() {
        if (active) return
        active = true
        discoveryListener = makeDiscoveryListener()
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        Log.d(TAG, "Discovery started")
    }

    fun stop() {
        active = false
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (_: IllegalArgumentException) {
                // listener was never registered or already unregistered
            }
            discoveryListener = null
        }
        Log.d(TAG, "Discovery stopped")
    }

    private fun makeDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Start discovery failed: $errorCode")
            active = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Stop discovery failed: $errorCode")
        }
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.contains(SERVICE_TYPE)) return
            // post to main thread — resolving from within onServiceFound can fail on some devices
            mainHandler.post { if (active) resolve(serviceInfo, retryOnFail = true) }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            if (active) onServiceLost?.invoke()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, retryOnFail: Boolean) {
        if (!resolveInFlight.compareAndSet(false, true)) return
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
                resolveInFlight.set(false)
                Log.w(TAG, "Resolve failed ($errorCode) for ${failedInfo.serviceName}")
                if (retryOnFail && active) {
                    mainHandler.postDelayed({ resolve(failedInfo, retryOnFail = false) }, 500)
                }
            }
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                resolveInFlight.set(false)
                Log.d(TAG, "Resolved ${resolvedInfo.serviceName} -> ${resolvedInfo.host}:${resolvedInfo.port}")
                if (active) onServiceResolved?.invoke(resolvedInfo.host, resolvedInfo.port, resolvedInfo.serviceName)
            }
        })
    }
}
