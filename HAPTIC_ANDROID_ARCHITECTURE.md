# Haptic Sports Client — Android Architecture & Implementation Reference

This document describes the as-built Android implementation. It reflects the
actual code, not the original design intent. Where design and code disagree,
this document follows the code.

Companion documents:
- `HAPTIC_PROTOCOL.md` — wire protocol (authoritative for message schemas)
- `HAPTIC_IOS_ARCHITECTURE.md` — iOS port guide using the same protocol

---

## 1. The system in one paragraph

A laptop plays a tennis/badminton video using a Python (PySide6/Qt) player.
Every racket strike and ball bounce was detected offline and written to a JSON
"timeline." When the user starts playback, an Android phone already on the
same Wi-Fi network receives the whole timeline, then schedules a vibration for
each event at exactly the right media-time, in sync with the video. The user
feels the match.

The phone is a **dumb actuator**. All analysis, all decisions about which
events matter, all authority over the timeline live on the laptop. The phone
receives data and translates it into vibration.

---

## 2. Architecture overview

```
   ┌──────────────────────── Laptop ─────────────────────────┐
   │  Python video player                                     │
   │   ├─ loads <video>.haptic.json (offline timeline)        │
   │   ├─ plays the video                                     │
   │   └─ haptic server: mDNS + TCP control + UDP sync       │
   └──────────────────────────────────────────────────────────┘
                            │ Wi-Fi LAN
                            │
   ┌──────────────────────── Phone ──────────────────────────┐
   │  Android haptic client (this project)                    │
   │   ├─ Discovery      NSD / mDNS browse for _haptics._tcp  │
   │   ├─ ControlChannel TCP JSON channel (timeline + clock)  │
   │   ├─ SyncChannel    UDP listener (sync pulses)           │
   │   ├─ ClockSync      SNTP-style offset estimation         │
   │   ├─ MediaClock     extrapolated media-time from anchor  │
   │   ├─ Scheduler      coroutine loop firing vibrations     │
   │   └─ HapticPlayer   tier-selected vibration execution    │
   └──────────────────────────────────────────────────────────┘
```

**Key design principle:** the timeline is pre-loaded once. During playback only
lightweight UDP sync pulses (5–10 Hz) stream from the laptop. A few dropped
pulses cost zero events — the phone already has all of them and coasts on its
local clock between pulses.

---

## 3. Project structure

```
app/src/main/java/com/feelthesports/hapticactuator/
  MainActivity.kt              // Activity + Compose UI + all wiring
  clock/
    MediaClock.kt              // extrapolated media-time, anchor + rate model
  haptic/
    Capabilities.kt            // detect tier at startup (object, runs once)
    HapticPlayer.kt            // play / playBatch per tier; BatchEvent
  net/
    Discovery.kt               // NsdManager wrapper for _haptics._tcp
    ControlChannel.kt          // TCP length-prefixed JSON channel
    SyncChannel.kt             // UDP DatagramSocket listener
    ClockSync.kt               // SNTP-style time_req/resp exchange
  timeline/
    Timeline.kt                // HapticEvent data class + JSON parsing + binary index
    Scheduler.kt               // coroutine scheduler with chunked sleep + batching
  ui/theme/                    // Compose Material3 theme (Color, Theme, Type)
```

`net/`, `timeline/`, `clock/`, and `haptic/` have no Activity dependency and
are independently testable. All UI lives in `MainActivity.kt`.

---

## 4. Timeline data format (wire, as received)

The server sends a stripped-down timeline — no research fields, only what the
client needs. Schema (v2 on the wire):

```jsonc
{
  "version": 2,
  "source": "match.mp4",
  "duration": 412.5,
  "events": [
    {
      "time": 12.480,        // media-time in SECONDS (float) — not milliseconds
      "intensity": 0.82,     // 0.0..1.0, calibrated per video — do not renormalise
      "type": "hit",         // always "hit" — switch on vision_type instead (§4.1)
      "vision_type": "strike" // "strike", "bounce", or null — see §4.1
    }
  ]
}
```

Fields `db`, `hf_ratio`, `centroid`, `calibration`, `params`, and
`sample_rate_analyzed` are **stripped by the server before sending**. Remove
any code that references them.

### 4.1 `vision_type` — the field to switch on

`type` is always `"hit"`. The refined classification is in `vision_type`:

| `vision_type` | Meaning | Haptic character |
|---|---|---|
| `"strike"` | racket hit | sharp, crisp click |
| `"bounce"` | ball bounce | heavy, soft thud |
| `null` | undetermined | generic hit |

Switch on `vision_type`, not `type`. Crash if unknown values reach the haptic
layer — use the `else` branch as the fallback.

### 4.2 Kotlin representation

```kotlin
data class HapticEvent(
    val time: Double,
    val intensity: Float,
    val visionType: String?,   // "strike", "bounce", or null
)
```

Parse `vision_type` defensively:
```kotlin
visionType = if (!e.has("vision_type") || e.isNull("vision_type")) null
             else e.getString("vision_type")
```

Sort events by `time` on receipt (the server sorts, but don't assume it).
Build a binary-search index (`Timeline.indexFrom(t: Double)`) for fast
"first event at or after t" lookups.

---

## 5. Network protocol

See `HAPTIC_PROTOCOL.md` for authoritative message schemas. This section
documents the as-built implementation decisions.

### 5.1 Discovery (`Discovery.kt`)

```kotlin
nsdManager.discoverServices("_haptics._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
```

Implementation notes:
- `onServiceFound` posts to the main thread before calling `resolveService`.
  Resolving directly inside `onServiceFound` fails on some devices.
- An `AtomicBoolean(resolveInFlight)` prevents concurrent resolve calls —
  `NsdManager` throws if a second resolve starts before the first finishes.
- On `onResolveFailed`, one retry fires after 500 ms (`retryOnFail` flag
  prevents infinite retry loops).
- `onServiceLost` does **not** tear down the TCP connection. It only restarts
  NSD browsing. The TCP connection is only closed when it actually fails
  (see `onDisconnected`). Tearing down TCP on mDNS cache eviction causes
  spurious disconnects when the network is perfectly healthy.

### 5.2 TCP control channel (`ControlChannel.kt`)

Frame format: 4-byte big-endian length + UTF-8 JSON body (no newline).

The **TCP recv loop is the single owner of TCP reads**. Clock-sync responses
are routed through an in-memory `Channel<Pair<Long, Long>>` in `ClockSync`,
not by reading the socket inline. Reading from two places causes a race where
clock-sync consumes messages intended for the recv loop.

Callbacks (all dispatched to Main thread):
```
onConnected    ()
onTimeline     (JSONObject)           // the data object, not the outer envelope
onPlay         (mediaT, tServerNs, rate)
onPause        (mediaT, tServerNs)
onSeek         (mediaT, tServerNs)
onRate         (rate, tServerNs)
onTimeResp     (t0ClientNs, tServerNs, t1ClientNs)
onDisconnected ()
```

`t_server_ns` is present in **every** control message (`play`, `pause`,
`seek`, `rate`). Always use it — never estimate the server time from
`System.nanoTime()` alone for anchoring.

**`t1ClientNs` capture timing:** the receive timestamp `t1` for clock-sync
must be captured **on the IO thread** at the moment of socket read, before
dispatching to Main. Capturing it after the dispatch includes queue latency
and inflates the measured RTT.

```kotlin
"time_resp" -> {
    val t1 = System.nanoTime()   // on IO thread — captured here, not in onTimeResp
    withContext(Dispatchers.Main) {
        onTimeResp?.invoke(msg.getLong("t0_client_ns"), msg.getLong("t_server_ns"), t1)
    }
}
```

### 5.3 UDP sync channel (`SyncChannel.kt`)

`DatagramSocket` bound to a random OS-assigned port (`socket.localPort`).
That port is declared in `hello.udp_port` so the server knows where to send
sync pulses.

The receive loop uses a 500 ms `soTimeout` so `isActive` is checked regularly
even when no pulses arrive. The loop exits cleanly on `SocketException`
(socket closed) and tolerates `SocketTimeoutException` without logging.

### 5.4 Clock sync (`ClockSync.kt`)

SNTP-style exchange: 8 rounds, 20 ms between requests, 2 s response timeout.

```
rtt_ns    = t1ClientNs - t0ClientNs
offset_ns = tServerNs - (t0ClientNs + rtt_ns / 2)
```

After collecting all responses: sort by RTT, keep the lowest half, average
the offsets. Result is `clock_offset_ns` — add this to `System.nanoTime()`
to get the server's monotonic clock.

Responses are routed via a `Channel<Pair<Long, Long>>` in `ClockSync.onTimeResp`.
This avoids the TCP race described in §5.2.

---

## 6. Clock model (`MediaClock.kt`)

### 6.1 Fields

```kotlin
@Volatile var clockOffsetNs: Long = 0L  // set by ClockSync after handshake

private data class Anchor(
    val mediaT: Double,    // media-time at the anchor moment
    val serverNs: Long,    // server clock at the anchor moment
    val rate: Double,      // playback rate (0.0 = paused)
)
@Volatile private var anchor: Anchor? = null
```

### 6.2 `mediaTime()` — the core formula

```kotlin
fun mediaTime(): Double {
    val a = anchor ?: return 0.0
    if (a.rate == 0.0) return a.mediaT   // paused
    return a.mediaT + (nowServerNs() - a.serverNs) / 1_000_000_000.0 * a.rate
}

private fun nowServerNs() = System.nanoTime() + clockOffsetNs
```

### 6.3 Anchoring methods

| Method | When to use |
|---|---|
| `syncAnchor(mediaT, tServerNs, rate)` | Primary method — use for play, pause, seek, rate, UDP sync pulses. Uses the server's own timestamp, most accurate. |
| `play(mediaT, rate)` | Convenience — anchors to local estimated server time. Less accurate; prefer `syncAnchor`. |
| `pause(mediaT)` | Freezes clock at mediaT using local estimate. |
| `seek(mediaT)` | Jumps to mediaT, preserves current rate. |
| `setRate(rate)` | Re-anchors with new rate. |

In practice, `MainActivity` uses `syncAnchor` for all incoming messages because
every control message carries `t_server_ns`.

### 6.4 `setOffset` — updating clock offset without disturbing media time

Naively changing `clockOffsetNs` would shift `nowServerNs()` and therefore
change `mediaTime()`. `setOffset` compensates by shifting `anchor.serverNs`
by the same delta:

```kotlin
fun setOffset(offsetNs: Long) {
    val delta = offsetNs - clockOffsetNs
    clockOffsetNs = offsetNs
    anchor?.let { anchor = it.copy(serverNs = it.serverNs + delta) }
}
```

Call `setOffset` after clock sync completes. Never write `clockOffsetNs`
directly.

### 6.5 `isPlaying`

```kotlin
val isPlaying: Boolean get() = (anchor?.rate ?: 0.0) != 0.0
```

Paused = rate 0.0. There is no separate `playing` boolean.

---

## 7. Haptic tier detection (`Capabilities.kt`)

Detected once at app start, passed everywhere as an immutable
`HapticCapabilities` data class.

```kotlin
enum class HapticTier { COMPOSITION, AMPLITUDE, BASIC }

data class HapticCapabilities(
    val tier: HapticTier,
    val hasAmplitudeControl: Boolean,
    val supportedPrimitives: List<String>,   // e.g. ["CLICK", "TICK", "LOW_TICK", "THUD"]
    val apiLevel: Int,
)
```

Tier selection:
```kotlin
val tier = when {
    supportedPrimitives.isNotEmpty() -> HapticTier.COMPOSITION   // at least one primitive works
    hasAmplitude -> HapticTier.AMPLITUDE
    else -> HapticTier.BASIC
}
```

Note: `arePrimitivesSupported()` is not reliable on API 30 (returns `true`
for everything). `minSdk = 31` avoids this. On real API 31+ devices, many
OEM builds (e.g. Samsung budget line) return no supported primitives, so tier
falls through to AMPLITUDE or BASIC — this is correct behavior.

The four primitives checked: `CLICK`, `TICK`, `LOW_TICK`, `THUD`.
These names are also the strings sent in `hello.capabilities.primitives`.

---

## 8. Haptic playback (`HapticPlayer.kt`)

### 8.1 The vibration-cancellation problem

Android's `Vibrator.vibrate()` **cancels any in-progress vibration** before
starting the new one. If two haptic events are within ~200 ms of each other,
the second `vibrate()` call cuts off the first, making both events weaker or
making the first disappear entirely.

**Fix:** collect events that fall within a 200 ms window and fire them as a
single `VibrationEffect.Composition` call. The hardware engine handles the
inter-event timing internally without any cancellation.

### 8.2 `BatchEvent`

```kotlin
data class BatchEvent(
    val visionType: String?,
    val intensity: Float,
    val delayFromFirstMs: Int,   // 0 for the first event; gap from first event for subsequent ones
)
```

### 8.3 `playBatch(events: List<BatchEvent>)`

- If tier ≠ COMPOSITION, or batch size == 1: calls `play()` for each event
  individually (Tier 2/3 doesn't support Composition).
- If tier == COMPOSITION and batch size > 1: builds one
  `VibrationEffect.Composition` with all events at their respective delays.

The delay passed to `addPrimitive(id, scale, delayMs)` is the **gap after
the previous primitive ends**, not after it starts:

```kotlin
val gapMs = event.delayFromFirstMs - events[i - 1].delayFromFirstMs
val prevDurationMs = vibrator.getPrimitiveDurations(prevId)[0]
val delayMs = (gapMs - prevDurationMs).coerceAtLeast(0)
composition.addPrimitive(id, event.intensity, delayMs)
```

### 8.4 `estimateBatchDurationMs`

Used by the Scheduler to compute a post-batch guard delay. Returns 0 for
non-COMPOSITION tiers (guard not needed since `playBatch` falls through to
individual `play()` calls):

```kotlin
fun estimateBatchDurationMs(events: List<BatchEvent>): Int {
    if (events.isEmpty() || capabilities.tier != HapticTier.COMPOSITION) return 0
    val last = events.last()
    return last.delayFromFirstMs + vibrator.getPrimitiveDurations(pickPrimitive(last.visionType))[0]
}
```

### 8.5 `pickPrimitive(visionType: String?)`

Maps `vision_type` to a preference-ordered list of primitives, picks the first
one the device actually supports:

```kotlin
val prefs = when (visionType) {
    "bounce" -> listOf("THUD", "LOW_TICK", "TICK", "CLICK")   // soft/heavy preferred
    else     -> listOf("CLICK", "TICK", "LOW_TICK", "THUD")   // "strike", null, unknown
}
val name = prefs.firstOrNull { it in capabilities.supportedPrimitives }
    ?: capabilities.supportedPrimitives.firstOrNull()
    ?: return VibrationEffect.Composition.PRIMITIVE_CLICK      // safe fallback if empty
return NAME_TO_ID[name] ?: VibrationEffect.Composition.PRIMITIVE_CLICK
```

If `supportedPrimitives` is empty (non-COMPOSITION device), the safe fallback
`PRIMITIVE_CLICK` is never actually reached because `estimateBatchDurationMs`
guards with the tier check before calling `pickPrimitive`.

### 8.6 Tier 2 and 3

```kotlin
// Tier 2: amplitude-modulated one-shot
val amplitude = (scale * 255).toInt().coerceIn(1, 255)
vibrator.vibrate(VibrationEffect.createOneShot(30L, amplitude))

// Tier 3: basic on/off buzz
val durationMs = (20 + scale * 40).toLong()
vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
```

---

## 9. Scheduler (`Scheduler.kt`)

The scheduler is a single coroutine on `Dispatchers.Default`. It runs through
the timeline event-by-event.

### 9.1 Constants

```kotlin
private const val STALE_THRESHOLD_S = 0.3   // skip events more than 300 ms in the past
private const val MAX_SLEEP_MS = 200L        // max sleep between clock re-anchors
private const val BATCH_WINDOW_MS = 200      // collect events within 200 ms into one batch
```

### 9.2 Loop structure

For each event:

1. **Chunked sleep**: sleep toward the event's deadline in ≤200 ms chunks so
   the live clock stays current between sleeps. The scheduler re-reads
   `mediaClock.mediaTime()` on each chunk iteration, incorporating any
   `syncAnchor` calls that arrived in the meantime.

2. **Stale check**: if the event is more than 300 ms in the past when the
   scheduler wakes up, skip it and log.

3. **Intensity filter**: if `event.intensity × strengthScale < minIntensity`,
   skip and log.

4. **Look-ahead batch**: scan forward through events within `BATCH_WINDOW_MS`
   (200 ms) of the current event. Build a `List<BatchEvent>` for them all.
   Events that fail the intensity filter within the window are skipped but
   the scan still continues to find events beyond them.

5. **Fire**: `player.playBatch(batch)` on Main thread.

6. **Post-batch guard**: wait until the estimated composition end time before
   advancing. This prevents the next `vibrate()` call from cancelling the
   tail of the current composition:
   ```kotlin
   val compositionEndsAtS = event.time + batchDurationMs / 1_000.0
   val guardMs = ((compositionEndsAtS - mediaClock.mediaTime()) / rate * 1_000.0).toLong()
   if (guardMs > 1L) delay(guardMs.coerceAtMost(MAX_SLEEP_MS))
   ```

7. **Advance index** to `j` (past all events consumed in the batch).

### 9.3 Start / stop

- `start()` cancels any running job and starts a new one from
  `timeline.indexFrom(mediaClock.mediaTime())`.
- Called on: `play`, `seek` (if playing), `rate` (if playing).
- `stop()` cancels the job.
- Called on: `pause`, `onDisconnected`, `onPause` (Activity lifecycle).

---

## 10. Connection and reconnection (`MainActivity.kt`)

### 10.1 Connection states

```kotlin
sealed class ConnectionStatus {
    object Searching : ConnectionStatus()
    data class Reconnecting(val name: String) : ConnectionStatus()
    data class Connecting(val name: String) : ConnectionStatus()
    data class Connected(val name: String) : ConnectionStatus()
}
```

### 10.2 `connectTo(host, port, name)`

Central method called from both NSD resolution and direct reconnect attempts:

1. Cancels any pending reconnect job.
2. Sets status to `Connecting`.
3. Closes existing `SyncChannel` and `ControlChannel`.
4. Creates a new `SyncChannel` (binds a fresh UDP socket, gets a new port).
5. Creates a new `ControlChannel`, wires all callbacks.
6. Calls `ch.connect(lifecycleScope)`.

The last known host/port/name is stored in `lastHost`, `lastPort`, `lastName`
for direct reconnect.

### 10.3 `scheduleReconnect()` — exponential backoff

```kotlin
private fun scheduleReconnect() {
    discovery.start()   // NSD fallback — catches IP changes or re-advertisement

    val host = lastHost ?: return   // no known address yet; NSD only
    val delayMs = reconnectDelay
    reconnectDelay = (reconnectDelay * 2).coerceAtMost(RECONNECT_DELAY_MAX_MS)   // doubles each time, cap 30 s

    reconnectJob?.cancel()
    reconnectJob = lifecycleScope.launch {
        delay(delayMs)
        if (connectionStatus is ConnectionStatus.Reconnecting ||
            connectionStatus is ConnectionStatus.Searching) {
            connectTo(host, lastPort, lastName)
        }
    }
}
```

Dual strategy: NSD discovery runs in parallel for IP changes, while the
direct TCP fast-path retries the last known address (succeeds in ~100 ms if
the server is up). The first to succeed wins.

### 10.4 `onResume` / `onPause`

**`onResume`**: resets backoff to 1 s, restarts NSD, triggers immediate
reconnect attempt if not already connected/connecting.

**`onPause`**: cancels reconnect job, stops scheduler, closes sync/control
channels, stops NSD. Resets status to `Searching`. The app does not hold
connections in the background.

---

## 11. UI (`MainActivity.kt` — Jetpack Compose)

The UI is built with Jetpack Compose + Material3. All state is held as
Compose `mutableStateOf` / `mutableFloatStateOf` on the Activity.

### 11.1 Status card

Color-coded by connection state:
- `Searching` → `surfaceVariant`
- `Reconnecting` → `errorContainer` (red-tinted)
- `Connecting` → `secondaryContainer`
- `Connected` → `primaryContainer` (green-tinted)

### 11.2 Diagnostics (always visible, no collapse)

- Clock sync offset in ms (`null` = not yet synced)
- Timeline event count (`null` = no timeline loaded)
- Last UDP sync media_t in seconds (`null` = no pulse received)
- Haptic tier label + API level + amplitude control + primitive list

### 11.3 Banners

- **Power Save Mode**: shown when `PowerManager.isPowerSaveMode` is true.
  Haptics may be suppressed by the OS.
- **Basic tier**: shown when `capabilities.tier == HapticTier.BASIC`.
  Informs user the device only vibrates on/off.

### 11.4 Controls

- **Strength slider**: 0.5×–1.5×, persisted to `SharedPreferences` as
  `strength_scale`. Applied multiplicatively to event intensity before
  intensity filter check.
- **Min intensity slider**: 0.0–0.5, persisted as `min_intensity`. Events
  with `scaled intensity < minIntensity` are skipped silently.
- **Strike / Bounce test buttons**: fires `hapticPlayer.play(visionType, 0.8f)`
  immediately, outside the scheduler.
- **Test Timeline button**: loads `Timeline.createTest()` (6 events over ~3 s)
  and runs the scheduler against a fresh clock starting at t=0.

### 11.5 Screen-on

`view.keepScreenOn = true` is set via a Compose `DisposableEffect` that
follows the composable lifecycle. No WakeLock is used.

---

## 12. `hello` message (sent on connect)

```json
{
  "msg": "hello",
  "client": "android-haptic",
  "client_version": "0.1",
  "udp_port": 41665,
  "capabilities": {
    "amplitude_control": true,
    "primitives": ["CLICK", "TICK", "LOW_TICK", "THUD"],
    "vibrator_api": 33
  }
}
```

`udp_port` is the OS-assigned port from `SyncChannel.port`.
`primitives` is `capabilities.supportedPrimitives` — the actual list from
`arePrimitivesSupported()`, not a hardcoded list.

---

## 13. Known caveats and design constraints

- **Haptics require foreground / screen on** on most Android devices. This is
  not fixed; the assumed use case is the viewer holding the phone while
  watching.
- **Power Save Mode** suppresses haptics on many devices. Detected and
  surfaced in the UI. No workaround.
- **`vibrate()` cancels in-progress vibration.** Close events (< 200 ms)
  must be batched into one `VibrationEffect.Composition` or the earlier one
  is cut short. See §8.
- **`arePrimitivesSupported()` is unreliable on API 30.** `minSdk = 31` avoids
  this. Some API 31+ OEM builds report no supported primitives; the tier
  correctly falls through to AMPLITUDE or BASIC.
- **Scheduler jitter on `Dispatchers.Default`**: `delay()` can overshoot by
  100–200 ms under load. The 300 ms stale threshold and chunked sleep
  compensate. This is not fixable without a real-time thread, which Android
  doesn't expose.
- **NSD resolve races**: `NsdManager.resolveService()` must not be called
  concurrently. The `AtomicBoolean(resolveInFlight)` guard prevents this.
- **Internet connectivity not required.** This is a local-network app.
  Remove any `ACCESS_NETWORK_STATE` or internet-related code if it appears.

---

## 14. Out of scope (do not build)

- Phone-side audio capture or analysis
- Playing video on the phone
- Multi-phone broadcast mode
- Cloud sync, accounts, telemetry beyond local logs
- Internet connectivity
- ML inference on the phone

---

## 15. Quick reference

| Item | Value |
|---|---|
| `minSdk` | 31 (Android 12) |
| mDNS service type | `_haptics._tcp` |
| TCP port | from NSD resolution (default 47821) |
| TCP frame | 4-byte BE length + UTF-8 JSON |
| UDP port | OS-assigned, reported in `hello.udp_port` |
| Stale threshold | 300 ms |
| Batch window | 200 ms |
| Max sleep chunk | 200 ms |
| Clock sync rounds | 8, 20 ms apart |
| Reconnect backoff | 1 s → 2 s → 4 s → … → 30 s cap |
| Strength slider range | 0.5× – 1.5× |
| Min intensity default | 0.15 |
| SharedPreferences | `haptic_settings` / `strength_scale`, `min_intensity` |
| UI framework | Jetpack Compose + Material3 |
