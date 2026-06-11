package com.feelthesports.hapticactuator

import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.feelthesports.hapticactuator.clock.MediaClock
import com.feelthesports.hapticactuator.haptic.Capabilities
import com.feelthesports.hapticactuator.haptic.HapticCapabilities
import com.feelthesports.hapticactuator.haptic.HapticPlayer
import com.feelthesports.hapticactuator.haptic.HapticTier
import com.feelthesports.hapticactuator.net.ClockSync
import com.feelthesports.hapticactuator.net.ControlChannel
import com.feelthesports.hapticactuator.net.Discovery
import com.feelthesports.hapticactuator.net.SyncChannel
import com.feelthesports.hapticactuator.timeline.Scheduler
import com.feelthesports.hapticactuator.timeline.Timeline
import com.feelthesports.hapticactuator.ui.theme.HapticActuatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainActivity"

sealed class ConnectionStatus {
    object Searching : ConnectionStatus()
    data class Connecting(val name: String) : ConnectionStatus()
    data class Connected(val name: String) : ConnectionStatus()
}

class MainActivity : ComponentActivity() {

    private lateinit var discovery: Discovery
    private lateinit var scheduler: Scheduler
    private val mediaClock = MediaClock()
    private var controlChannel: ControlChannel? = null
    private var syncChannel: SyncChannel? = null
    private var currentTimeline: Timeline? = null
    private var connectionStatus: ConnectionStatus by mutableStateOf(ConnectionStatus.Searching)
    private var eventCount: Int?     by mutableStateOf(null)
    private var clockOffsetMs: Long? by mutableStateOf(null)
    private var lastSyncMediaT: Double? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val capabilities = Capabilities.detect(this)
        val hapticPlayer = HapticPlayer(this, capabilities)
        val powerManager = getSystemService(PowerManager::class.java)
        scheduler = Scheduler(hapticPlayer)

        discovery = Discovery(this).apply {
            onServiceResolved = { host, port, name ->
                runOnUiThread {
                    connectionStatus = ConnectionStatus.Connecting(name)
                    clockOffsetMs  = null
                    lastSyncMediaT = null

                    // Tear down any previous connection
                    syncChannel?.close();    syncChannel    = null
                    controlChannel?.disconnect(); controlChannel = null

                    // Create UDP socket first — its port goes into the hello message
                    val syncCh = SyncChannel().also { syncChannel = it }
                    syncCh.onSyncPulse = { mediaT, tServerNs, rate ->
                        mediaClock.syncAnchor(mediaT, tServerNs, rate)
                        lastSyncMediaT = mediaT
                    }
                    syncCh.start(lifecycleScope)

                    val ch = ControlChannel(host, port, capabilities, udpPort = syncCh.port)
                    val clockSync = ClockSync(ch)

                    ch.onConnected = {
                        connectionStatus = ConnectionStatus.Connected(name)
                        lifecycleScope.launch(Dispatchers.IO) {
                            val offset = clockSync.sync()
                            mediaClock.setOffset(offset)
                            withContext(Dispatchers.Main) {
                                clockOffsetMs = offset / 1_000_000
                                Log.d(TAG, "Clock sync complete: $clockOffsetMs ms offset")
                            }
                        }
                    }
                    ch.onTimeResp = { t0, ts, t1 -> clockSync.onTimeResp(t0, ts, t1) }
                    ch.onTimeline = { data ->
                        val timeline = Timeline.parse(data)
                        currentTimeline = timeline
                        eventCount = timeline.events.size
                        Log.d(TAG, "Timeline loaded: ${timeline.events.size} events")
                        mediaClock.play(timeline.events.firstOrNull()?.time ?: 0.0)
                        scheduler.start(timeline, lifecycleScope, mediaClock)
                    }
                    ch.onPlay  = { t ->
                        Log.d(TAG, "play  media_t=$t")
                        mediaClock.play(t)
                        currentTimeline?.let { scheduler.start(it, lifecycleScope, mediaClock) }
                    }
                    ch.onPause = { t ->
                        Log.d(TAG, "pause media_t=$t")
                        mediaClock.pause(t)
                        scheduler.stop()
                    }
                    ch.onSeek  = { t ->
                        Log.d(TAG, "seek  media_t=$t")
                        mediaClock.seek(t)
                        if (mediaClock.isPlaying) {
                            currentTimeline?.let { scheduler.start(it, lifecycleScope, mediaClock) }
                        }
                    }
                    ch.onRate  = { r ->
                        Log.d(TAG, "rate  rate=$r")
                        mediaClock.setRate(r)
                        if (mediaClock.isPlaying) {
                            currentTimeline?.let { scheduler.start(it, lifecycleScope, mediaClock) }
                        }
                    }
                    ch.onDisconnected = {
                        scheduler.stop()
                        syncChannel?.close(); syncChannel = null
                        currentTimeline = null
                        connectionStatus = ConnectionStatus.Searching
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            discovery.start()
                        }
                    }

                    ch.connect(lifecycleScope)
                    controlChannel = ch
                }
            }
            onServiceLost = {
                runOnUiThread {
                    scheduler.stop()
                    syncChannel?.close();         syncChannel    = null
                    controlChannel?.disconnect(); controlChannel = null
                    currentTimeline = null
                    connectionStatus = ConnectionStatus.Searching
                }
            }
        }

        setContent {
            HapticActuatorTheme {
                KeepScreenOn()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        capabilities   = capabilities,
                        isPowerSaveMode = powerManager.isPowerSaveMode,
                        connectionStatus = connectionStatus,
                        eventCount     = eventCount,
                        clockOffsetMs  = clockOffsetMs,
                        lastSyncMediaT = lastSyncMediaT,
                        onTestVibration = { type -> hapticPlayer.play(type, 0.8f) },
                        onTestTimeline  = {
                            val t = Timeline.createTest()
                            eventCount = t.events.size
                            val testClock = MediaClock().apply { play(0.0) }
                            scheduler.start(t, lifecycleScope, testClock)
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        discovery.start()
    }

    override fun onPause() {
        super.onPause()
        scheduler.stop()
        syncChannel?.close();         syncChannel    = null
        discovery.stop()
        controlChannel?.disconnect(); controlChannel = null
        connectionStatus = ConnectionStatus.Searching
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun MainScreen(
    capabilities: HapticCapabilities,
    isPowerSaveMode: Boolean,
    connectionStatus: ConnectionStatus,
    eventCount: Int?,
    clockOffsetMs: Long?,
    lastSyncMediaT: Double?,
    onTestVibration: (type: String) -> Unit,
    onTestTimeline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Haptic Actuator", style = MaterialTheme.typography.headlineMedium)

        val statusText = when (connectionStatus) {
            is ConnectionStatus.Searching  -> "Searching for laptop…"
            is ConnectionStatus.Connecting -> "Connecting to ${connectionStatus.name}…"
            is ConnectionStatus.Connected  -> "Connected to ${connectionStatus.name}"
        }
        val statusColor = when (connectionStatus) {
            is ConnectionStatus.Searching  -> MaterialTheme.colorScheme.surfaceVariant
            is ConnectionStatus.Connecting -> MaterialTheme.colorScheme.secondaryContainer
            is ConnectionStatus.Connected  -> MaterialTheme.colorScheme.primaryContainer
        }
        Card(colors = CardDefaults.cardColors(containerColor = statusColor)) {
            Text(statusText, modifier = Modifier.padding(12.dp))
        }

        if (isPowerSaveMode) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = "Power Save Mode is active — haptics may be suppressed",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        HorizontalDivider()

        val tierLabel = when (capabilities.tier) {
            HapticTier.COMPOSITION -> "Tier 1 — Composition primitives (best)"
            HapticTier.AMPLITUDE   -> "Tier 2 — Amplitude-modulated waveform"
            HapticTier.BASIC       -> "Tier 3 — Basic on/off buzz"
        }
        Text("Haptic tier:  $tierLabel", style = MaterialTheme.typography.titleMedium)
        Text("API level:  ${capabilities.apiLevel}")
        Text("Amplitude control:  ${if (capabilities.hasAmplitudeControl) "yes" else "no"}")

        val primitivesText = capabilities.supportedPrimitives
            .takeIf { it.isNotEmpty() }?.joinToString() ?: "none"
        Text("Primitives:  $primitivesText")

        if (capabilities.tier == HapticTier.BASIC) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = "This device's haptic hardware is basic. Vibrations will be on/off only.",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        HorizontalDivider()

        val diagColor = MaterialTheme.colorScheme.onSurfaceVariant
        val diagStyle = MaterialTheme.typography.bodySmall
        Text(
            text = when (clockOffsetMs) {
                null -> "Clock sync:  pending"
                else -> "Clock sync:  $clockOffsetMs ms offset"
            },
            style = diagStyle, color = diagColor,
        )
        Text(
            text = when (eventCount) {
                null -> "Timeline:  none loaded"
                else -> "Timeline:  $eventCount events"
            },
            style = diagStyle, color = diagColor,
        )
        Text(
            text = when (lastSyncMediaT) {
                null -> "UDP sync:  none received"
                else -> "UDP sync:  last media_t = ${"%.2f".format(lastSyncMediaT)} s"
            },
            style = diagStyle, color = diagColor,
        )

        HorizontalDivider()

        Text("Test vibration (intensity 0.8):", style = MaterialTheme.typography.titleSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { onTestVibration("strike") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Strike")
            }
            OutlinedButton(
                onClick = { onTestVibration("bounce") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Bounce")
            }
        }

        Button(
            onClick = onTestTimeline,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test Timeline (6 events, ~3 s)")
        }
    }
}
