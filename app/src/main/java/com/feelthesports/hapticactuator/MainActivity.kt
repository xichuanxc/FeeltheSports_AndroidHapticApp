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
import com.feelthesports.hapticactuator.haptic.Capabilities
import com.feelthesports.hapticactuator.haptic.HapticCapabilities
import com.feelthesports.hapticactuator.haptic.HapticPlayer
import com.feelthesports.hapticactuator.haptic.HapticTier
import com.feelthesports.hapticactuator.net.ControlChannel
import com.feelthesports.hapticactuator.net.Discovery
import com.feelthesports.hapticactuator.timeline.Scheduler
import com.feelthesports.hapticactuator.timeline.Timeline
import com.feelthesports.hapticactuator.ui.theme.HapticActuatorTheme

private const val TAG = "MainActivity"

sealed class ConnectionStatus {
    object Searching : ConnectionStatus()
    data class Connecting(val name: String) : ConnectionStatus()
    data class Connected(val name: String) : ConnectionStatus()
}

class MainActivity : ComponentActivity() {

    private lateinit var discovery: Discovery
    private lateinit var scheduler: Scheduler
    private var controlChannel: ControlChannel? = null
    private var connectionStatus: ConnectionStatus by mutableStateOf(ConnectionStatus.Searching)
    private var eventCount: Int? by mutableStateOf(null)

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
                    controlChannel?.disconnect()
                    controlChannel = ControlChannel(host, port, capabilities).apply {
                        onConnected = {
                            connectionStatus = ConnectionStatus.Connected(name)
                        }
                        onTimeline = { data ->
                            val timeline = Timeline.parse(data)
                            eventCount = timeline.events.size
                            Log.d(TAG, "Timeline loaded: ${timeline.events.size} events")
                            scheduler.start(timeline, lifecycleScope)
                        }
                        onPlay  = { t -> Log.d(TAG, "play  media_t=$t") }
                        onPause = { t -> Log.d(TAG, "pause media_t=$t") }
                        onSeek  = { t -> Log.d(TAG, "seek  media_t=$t") }
                        onRate  = { r -> Log.d(TAG, "rate  rate=$r") }
                        onDisconnected = {
                            scheduler.stop()
                            connectionStatus = ConnectionStatus.Searching
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                discovery.start()
                            }
                        }
                    }.also { it.connect(lifecycleScope) }
                }
            }
            onServiceLost = {
                runOnUiThread {
                    scheduler.stop()
                    controlChannel?.disconnect()
                    controlChannel = null
                    connectionStatus = ConnectionStatus.Searching
                }
            }
        }

        setContent {
            HapticActuatorTheme {
                KeepScreenOn()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        capabilities = capabilities,
                        isPowerSaveMode = powerManager.isPowerSaveMode,
                        connectionStatus = connectionStatus,
                        eventCount = eventCount,
                        onTestVibration = { type -> hapticPlayer.play(type, 0.8f) },
                        onTestTimeline = {
                            val t = Timeline.createTest()
                            eventCount = t.events.size
                            scheduler.start(t, lifecycleScope)
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
        discovery.stop()
        controlChannel?.disconnect()
        controlChannel = null
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

        val timelineLabel = when (eventCount) {
            null -> "No timeline loaded"
            else -> "Timeline: $eventCount events"
        }
        Text(timelineLabel, style = MaterialTheme.typography.titleSmall)

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
