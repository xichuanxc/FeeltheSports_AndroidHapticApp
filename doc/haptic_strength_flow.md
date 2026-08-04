# How Server Amplitude Reaches `playBasic(scale)`

## 1. Server → Wire

The server sends a `timeline` message with a JSON array of events. Each event carries an `intensity` field in the range `[0, 1]`, set by the server's computer vision pipeline based on the estimated physical impact of the hit:

```json
{ "time": 1.24, "intensity": 0.85, "vision_type": "strike" }
```

## 2. Wire → `HapticEvent`

`Timeline.parse()` reads each event and stores `intensity` as a `Float`:

```kotlin
// Timeline.kt:33
intensity = e.getDouble("intensity").toFloat()
```

The comment on the field is explicit — `// 0..1, per-video relative — never re-normalise`. The server already normalised it; the client must not rescale it independently.

## 3. `HapticEvent.intensity` → `scale` in `Scheduler`

When the event fires, `strengthScale` (the user's slider, default `1.0×`) is applied:

```kotlin
// Scheduler.kt:58
val firstScaled = (event.intensity * strengthScale).coerceIn(0f, 1f)
```

This is the only transformation. The result is clamped to `[0, 1]` and becomes the `intensity` field of a `BatchEvent`.

## 4. `BatchEvent.intensity` → `playBasic(scale)`

`HapticPlayer.playBatch()` calls `play(visionType, intensity)` for each event, which routes to `playBasic()` on Tier 3:

```kotlin
// HapticPlayer.kt:89
private fun playBasic(scale: Float) {
    val durationMs = (20 + scale * 40).toLong()
    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
}
```

`scale` here is exactly `event.intensity * strengthScale`, clamped to `[0, 1]`.

## Summary

| `scale` value | Duration | Scenario |
|---|---|---|
| 0.0 | 20 ms | Minimum (filtered out by `minIntensity` if > 0) |
| 0.5 | 40 ms | Medium hit, default slider |
| 1.0 | 60 ms | Full-strength hit, default slider |
| 1.0 (slider at 1.5×) | 60 ms | Clamped — can't exceed 60 ms |

The motor always runs at `DEFAULT_AMPLITUDE`; only duration varies. The effective range is **20–60 ms**, which is perceptible but subtle. Slider values above `1.0×` only have effect on events that were below `0.67` intensity — they bring those up toward 60 ms. Events already near `1.0` are clamped and unaffected by the boost.
