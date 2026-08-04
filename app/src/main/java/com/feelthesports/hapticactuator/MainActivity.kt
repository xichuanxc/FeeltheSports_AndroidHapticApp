package com.feelthesports.hapticactuator

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

private const val TAG = "MainActivity"
private const val PREFS_NAME = "haptic_settings"
private const val KEY_STRENGTH = "strength_scale"
private const val KEY_MIN_INTENSITY = "min_intensity"
private const val RECONNECT_DELAY_MIN_MS = 1_000L
private const val RECONNECT_DELAY_MAX_MS = 30_000L

sealed class ConnectionStatus {
    object Searching : ConnectionStatus()
    data class Reconnecting(val name: String) : ConnectionStatus()
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
    private var eventCount: Int?      by mutableStateOf(null)
    private var clockOffsetMs: Long?  by mutableStateOf(null)
    private var lastSyncMediaT: Double? by mutableStateOf(null)
    private var strengthScale: Float  by mutableFloatStateOf(1.0f)
    private var minIntensity: Float   by mutableFloatStateOf(0.0f)
    private var showSettings: Boolean by mutableStateOf(false)
    private var showAbout: Boolean    by mutableStateOf(false)
    private var showDndDialog: Boolean by mutableStateOf(false)
    private var dndDismissed: Boolean = false

    private lateinit var capabilities: HapticCapabilities
    private lateinit var notificationManager: NotificationManager

    private var lastHost: InetAddress? = null
    private var lastPort: Int = 0
    private var lastName: String = ""
    private var reconnectJob: Job? = null
    private var reconnectDelay: Long = RECONNECT_DELAY_MIN_MS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        capabilities = Capabilities.detect(this)
        val hapticPlayer = HapticPlayer(this, capabilities)
        val powerManager = getSystemService(PowerManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        scheduler = Scheduler(hapticPlayer)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        strengthScale = prefs.getFloat(KEY_STRENGTH, 1.0f)
        minIntensity  = prefs.getFloat(KEY_MIN_INTENSITY, 0.0f)
        scheduler.strengthScale = strengthScale
        scheduler.minIntensity  = minIntensity

        discovery = Discovery(this).apply {
            onServiceResolved = { host, port, name ->
                runOnUiThread {
                    lastHost = host; lastPort = port; lastName = name
                    reconnectDelay = RECONNECT_DELAY_MIN_MS
                    connectTo(host, port, name)
                }
            }
            onServiceLost = {
                runOnUiThread {
                    Log.d(TAG, "mDNS service lost; re-browsing")
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        discovery.start()
                    }
                }
            }
        }

        setContent {
            HapticActuatorTheme {
                KeepScreenOn()
                val context = LocalContext.current
                when {
                    showSettings -> SettingsScreen(
                        capabilities        = capabilities,
                        isPowerSaveMode     = powerManager.isPowerSaveMode,
                        eventCount          = eventCount,
                        clockOffsetMs       = clockOffsetMs,
                        lastSyncMediaT      = lastSyncMediaT,
                        strengthScale       = strengthScale,
                        minIntensity        = minIntensity,
                        onTestVibration     = { visionType -> hapticPlayer.play(visionType, 0.8f) },
                        onTestTimeline      = {
                            val t = Timeline.createTest()
                            eventCount = t.events.size
                            val testClock = MediaClock().apply { play(0.0) }
                            scheduler.start(t, lifecycleScope, testClock)
                        },
                        onStrengthScaleChange = { v ->
                            strengthScale = v
                            scheduler.strengthScale = v
                            prefs.edit().putFloat(KEY_STRENGTH, v).apply()
                        },
                        onMinIntensityChange  = { v ->
                            minIntensity = v
                            scheduler.minIntensity = v
                            prefs.edit().putFloat(KEY_MIN_INTENSITY, v).apply()
                        },
                        onBack = { showSettings = false },
                    )
                    showAbout -> AboutScreen(onBack = { showAbout = false })
                    else -> Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        MainScreen(
                            connectionStatus = connectionStatus,
                            onOpenSettings   = { showSettings = true },
                            onOpenAbout      = { showAbout = true },
                            onExit           = { finish() },
                            modifier         = Modifier.padding(innerPadding),
                        )
                    }
                }

                if (showDndDialog) {
                    AlertDialog(
                        onDismissRequest = { showDndDialog = false; dndDismissed = true },
                        title = { Text("Enable Do Not Disturb") },
                        text  = { Text("FeeltheSports works best without interruptions. Grant Do Not Disturb access to silence notifications during haptic feedback sessions.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showDndDialog = false
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                )
                            }) { Text("Go to Settings") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDndDialog = false; dndDismissed = true }) {
                                Text("Not Now")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun connectTo(host: InetAddress, port: Int, name: String) {
        reconnectJob?.cancel()

        connectionStatus = ConnectionStatus.Connecting(name)
        clockOffsetMs  = null
        lastSyncMediaT = null

        syncChannel?.close();        syncChannel    = null
        controlChannel?.disconnect(); controlChannel = null

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
            reconnectDelay = RECONNECT_DELAY_MIN_MS
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
        }
        ch.onPlay  = { t, tServerNs, rate ->
            Log.d(TAG, "play  media_t=$t rate=$rate")
            mediaClock.syncAnchor(t, tServerNs, rate)
            currentTimeline?.let { scheduler.start(it, lifecycleScope, mediaClock) }
        }
        ch.onPause = { t, tServerNs ->
            Log.d(TAG, "pause media_t=$t")
            mediaClock.syncAnchor(t, tServerNs, 0.0)
            scheduler.stop()
        }
        ch.onSeek  = { t, tServerNs ->
            Log.d(TAG, "seek  media_t=$t")
            mediaClock.syncAnchor(t, tServerNs, mediaClock.rate)
            if (mediaClock.isPlaying) {
                currentTimeline?.let { scheduler.start(it, lifecycleScope, mediaClock) }
            }
        }
        ch.onRate  = { r, tServerNs ->
            Log.d(TAG, "rate  rate=$r")
            mediaClock.syncAnchor(mediaClock.mediaTime(), tServerNs, r)
            if (mediaClock.isPlaying) {
                currentTimeline?.let { scheduler.start(it, lifecycleScope, mediaClock) }
            }
        }
        ch.onDisconnected = {
            scheduler.stop()
            syncChannel?.close(); syncChannel = null
            currentTimeline = null
            connectionStatus = ConnectionStatus.Reconnecting(name)
            Log.d(TAG, "Disconnected from $name")
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                scheduleReconnect()
            }
        }

        ch.connect(lifecycleScope)
        controlChannel = ch
    }

    private fun scheduleReconnect() {
        discovery.start()

        val host = lastHost ?: return
        val delayMs = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(RECONNECT_DELAY_MAX_MS)
        Log.d(TAG, "Reconnect to $lastName in ${delayMs}ms (next: ${reconnectDelay}ms)")

        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            val status = connectionStatus
            if (status is ConnectionStatus.Reconnecting || status is ConnectionStatus.Searching) {
                Log.d(TAG, "Direct reconnect attempt → $host:$lastPort")
                connectTo(host, lastPort, lastName)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            showDndDialog = false
        } else if (!dndDismissed) {
            showDndDialog = true
        }
        reconnectDelay = RECONNECT_DELAY_MIN_MS
        discovery.start()
        val status = connectionStatus
        if (lastHost != null &&
            status !is ConnectionStatus.Connected &&
            status !is ConnectionStatus.Connecting) {
            scheduleReconnect()
        }
    }

    override fun onPause() {
        super.onPause()
        if (notificationManager.isNotificationPolicyAccessGranted) {
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        reconnectJob?.cancel()
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
    connectionStatus: ConnectionStatus,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler { showExitDialog = true }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title            = { Text("Exit") },
            text             = { Text("Are you sure you want to exit FeeltheSports?") },
            confirmButton    = {
                TextButton(onClick = onExit) { Text("Exit") }
            },
            dismissButton    = {
                TextButton(onClick = { showExitDialog = false }) { Text("Cancel") }
            },
        )
    }
    val statusText = when (connectionStatus) {
        is ConnectionStatus.Searching    -> "Searching for Haptic Server…"
        is ConnectionStatus.Reconnecting -> "Reconnecting to ${connectionStatus.name}…"
        is ConnectionStatus.Connecting   -> "Connecting to ${connectionStatus.name}…"
        is ConnectionStatus.Connected    -> "Connected to ${connectionStatus.name}"
    }
    val dotColor = when (connectionStatus) {
        is ConnectionStatus.Searching    -> Color(0xFF9E9E9E)
        is ConnectionStatus.Reconnecting -> Color(0xFFFF9800)
        is ConnectionStatus.Connecting   -> Color(0xFFFF9800)
        is ConnectionStatus.Connected    -> Color(0xFF4CAF50)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Main content centered in available space
            Box(
                modifier         = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        painter            = painterResource(R.drawable.haptic_icon),
                        contentDescription = "FeeltheSports Logo",
                        modifier           = Modifier.size(160.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text       = "FeeltheSports",
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(40.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier              = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(dotColor, CircleShape),
                            )
                            Text(
                                text  = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // University logo at the bottom
            androidx.compose.foundation.Image(
                painter            = painterResource(R.drawable.university_of_waikato_logo),
                contentDescription = "University of Waikato",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
            Spacer(Modifier.height(24.dp))
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            IconButton(onClick = onOpenAbout) {
                Icon(Icons.Filled.Info, contentDescription = "About")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    capabilities: HapticCapabilities,
    isPowerSaveMode: Boolean,
    eventCount: Int?,
    clockOffsetMs: Long?,
    lastSyncMediaT: Double?,
    strengthScale: Float,
    minIntensity: Float,
    onTestVibration: (visionType: String?) -> Unit,
    onTestTimeline: () -> Unit,
    onStrengthScaleChange: (Float) -> Unit,
    onMinIntensityChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (isPowerSaveMode) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text     = "Power Save Mode is active — haptics may be suppressed",
                        modifier = Modifier.padding(12.dp),
                        color    = MaterialTheme.colorScheme.onErrorContainer,
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
                        text     = "This device's haptic hardware is basic. Vibrations will be on/off only.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            HorizontalDivider()

            val diagColor = MaterialTheme.colorScheme.onSurfaceVariant
            val diagStyle = MaterialTheme.typography.bodySmall
            Text(
                text  = when (clockOffsetMs) {
                    null -> "Clock sync:  pending"
                    else -> "Clock sync:  $clockOffsetMs ms offset"
                },
                style = diagStyle, color = diagColor,
            )
            Text(
                text  = when (eventCount) {
                    null -> "Timeline:  none loaded"
                    else -> "Timeline:  $eventCount events"
                },
                style = diagStyle, color = diagColor,
            )
            Text(
                text  = when (lastSyncMediaT) {
                    null -> "UDP sync:  none received"
                    else -> "UDP sync:  last media_t = ${"%.2f".format(lastSyncMediaT)} s"
                },
                style = diagStyle, color = diagColor,
            )

            HorizontalDivider()

            Text("Haptic strength:  ${"%.2f".format(strengthScale)}×", style = MaterialTheme.typography.titleSmall)
            Slider(
                value         = strengthScale,
                onValueChange = onStrengthScaleChange,
                valueRange    = 0.5f..1.5f,
                modifier      = Modifier.fillMaxWidth(),
            )

            Text("Min intensity:  ${"%.2f".format(minIntensity)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value         = minIntensity,
                onValueChange = onMinIntensityChange,
                valueRange    = 0f..0.5f,
                modifier      = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            Text("Test vibration (intensity 0.8):", style = MaterialTheme.typography.titleSmall)
            Button(
                onClick  = { onTestVibration("strike") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Strike")
            }

            Button(
                onClick  = onTestTimeline,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test Timeline (6 events, ~3 s)")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // University logo
            androidx.compose.foundation.Image(
                painter            = painterResource(R.drawable.university_of_waikato_logo),
                contentDescription = "University of Waikato",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )

            HorizontalDivider()

            // App identity
            Text("FeeltheSports", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Real-time haptic feedback for tennis match viewing",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // People
            AboutRow(label = "Developed by",  value = "Chuan Xi")
            AboutRow(label = "Supervised by", value = "Assoc. Prof. David Nichols")
            AboutRow(label = "",              value = "Dr. Jemma König")

            HorizontalDivider()

            // Institution
            AboutRow(label = "Institution", value = "University of Waikato\nTe Whare Wānanga o Waikato")
            AboutRow(label = "School",      value = "School of Computing and\nMathematical Sciences")

            HorizontalDivider()

            // Build info
            AboutRow(label = "Version",    value = "1.0")
            AboutRow(label = "Build date", value = "4 Aug 2026")
            AboutRow(label = "Contact",    value = "xichuanxc@gmail.com")

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}
