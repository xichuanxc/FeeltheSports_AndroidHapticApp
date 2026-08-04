# Settings Screen — Design & Implementation Record

## What was built

A Settings screen accessible via the gear icon (⚙) in the top-right corner of the Main Screen. It exposes two runtime-adjustable parameters (haptic strength and minimum intensity), diagnostic read-outs from the live session, and two test buttons for verifying the haptic pipeline.

No navigation library is used. The screen is shown by setting `showSettings = true` at the `MainActivity` level; the flag is reset to `false` when the user presses Back.

---

## Architecture decisions

**State at activity level, not inside the composable**

`strengthScale` and `minIntensity` are `mutableFloatStateOf` fields on `MainActivity`. This means the slider values survive configuration changes, are immediately visible to the `Scheduler` (which runs on the same object), and do not require a ViewModel layer for this single-activity app.

**Immediate write-through**

Every slider `onValueChange` callback does two things at once:

```kotlin
onStrengthScaleChange = { v ->
    strengthScale = v          // updates UI state
    scheduler.strengthScale = v // takes effect on the next event
    prefs.edit().putFloat(KEY_STRENGTH, v).apply()  // persists
}
```

There is no "Save" button. Values are live and durable immediately.

**SharedPreferences keys**

| Key | Default |
|---|---|
| `"strength_scale"` | `1.0f` |
| `"min_intensity"` | `0.0f` |

Both are read back in `onCreate` before the `Scheduler` is first used.

---

## UI sections (top to bottom)

### 1. Power Save Mode warning

If `PowerManager.isPowerSaveMode` is true at compose time, a `CardDefaults.errorContainer` card is shown at the top:

> "Power Save Mode is active — haptics may be suppressed"

This is read-only and not dismissable.

### 2. Haptic tier info

Read from `HapticCapabilities` (detected once in `onCreate`):

| Tier | Label |
|---|---|
| `COMPOSITION` | Tier 1 — Composition primitives (best) |
| `AMPLITUDE`   | Tier 2 — Amplitude-modulated waveform |
| `BASIC`       | Tier 3 — Basic on/off buzz |

Also shows: API level, amplitude control availability, supported primitives list. If tier is `BASIC`, an additional info card reminds the user that vibration is on/off only.

### 3. Diagnostics

Three read-only labels in muted body-small style:

- `Clock sync: pending` / `Clock sync: {n} ms offset`
- `Timeline: none loaded` / `Timeline: {n} events`
- `UDP sync: none received` / `UDP sync: last media_t = {n:.2f} s`

These values are owned by `MainActivity` state and flow in from the live connection.

### 4. Haptic Strength slider

| Property | Value |
|---|---|
| Range | `0.5f … 1.5f` |
| Default | `1.0f` |
| Label | `Haptic strength: 1.00×` (2 d.p.) |
| Effect | Multiplied against `event.intensity` before clamping to `[0, 1]` |

Values above `1.0×` boost quiet events (intensity < `0.67`) toward full strength. Events already near `1.0` are clamped and unaffected.

### 5. Min Intensity slider

| Property | Value |
|---|---|
| Range | `0.0f … 0.5f` |
| Default | `0.0f` |
| Label | `Min intensity: 0.00` |
| Effect | Events where `intensity × strengthScale < minIntensity` are dropped entirely |

Purpose: suppress light incidental hits below a perceptible threshold. Applied on all tiers, not just amplitude-capable ones. See `haptic_strength_flow.md` for the full data path.

### 6. Test buttons

**Strike** — calls `hapticPlayer.play("strike", 0.8f)` directly; fires a single composition primitive (or amplitude pulse / buzz on lower tiers) at 80% intensity.

**Test Timeline (6 events, ~3 s)** — calls `Timeline.createTest()`, creates a fresh `MediaClock` at `t=0`, starts the scheduler. Sets `eventCount = 6` so the diagnostic label updates.

---

## Entry/exit

The gear icon (`Icons.Filled.Settings`) sits in a `Row` overlaid at `Alignment.TopEnd` of the Main Screen `Box`. Info icon (ℹ) is to its left.

```kotlin
Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
    IconButton(onClick = onOpenAbout)    { Icon(Icons.Filled.Info, ...) }
    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, ...) }
}
```

`BackHandler` inside `SettingsScreen` calls `onBack = { showSettings = false }`, so the Android system back gesture/button also dismisses the screen.

---

## iOS equivalents

| Android | iOS |
|---|---|
| `SharedPreferences` | `UserDefaults` |
| `PowerManager.isPowerSaveMode` | `ProcessInfo.processInfo.isLowPowerModeEnabled` |
| `HapticCapabilities.tier` | Detect via `CHHapticEngine.capabilitiesForHardware()` |
| `Icons.Filled.Settings` | `Image(systemName: "gearshape.fill")` |
| Immediate `prefs.edit().apply()` | `UserDefaults.standard.set(...)` (synchronous) |

Slider ranges and defaults are identical on iOS.
