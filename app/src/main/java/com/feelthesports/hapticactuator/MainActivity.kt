package com.feelthesports.hapticactuator

import android.os.Bundle
import android.os.PowerManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.feelthesports.hapticactuator.haptic.Capabilities
import com.feelthesports.hapticactuator.haptic.HapticCapabilities
import com.feelthesports.hapticactuator.haptic.HapticPlayer
import com.feelthesports.hapticactuator.haptic.HapticTier
import com.feelthesports.hapticactuator.ui.theme.HapticActuatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val capabilities = Capabilities.detect(this)
        val hapticPlayer = HapticPlayer(this, capabilities)
        val powerManager = getSystemService(PowerManager::class.java)

        setContent {
            HapticActuatorTheme {
                KeepScreenOn()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CapabilityScreen(
                        capabilities = capabilities,
                        isPowerSaveMode = powerManager.isPowerSaveMode,
                        onTestVibration = { type -> hapticPlayer.play(type, 0.8f) },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
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
fun CapabilityScreen(
    capabilities: HapticCapabilities,
    isPowerSaveMode: Boolean,
    onTestVibration: (type: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Haptic Actuator", style = MaterialTheme.typography.headlineMedium)

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
            .takeIf { it.isNotEmpty() }
            ?.joinToString()
            ?: "none"
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
    }
}
