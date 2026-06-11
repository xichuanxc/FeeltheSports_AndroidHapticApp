# Haptic Sports Client — Android Architecture & Handoff

This document hands off the design for an Android app that vibrates in sync
with tennis (and later badminton) video being watched on a laptop. The laptop
side already exists (Python: an offline audio analyzer that produces a
"haptic timeline" JSON, and a PySide6 video player). This Android client is
the missing piece.

The reader of this doc is an LLM coding assistant (Claude Code). It needs to
build the Android project; this document tells it the system architecture,
data formats, protocol, and the design decisions already made. The why is
included where it constrains the how.

---

## 1. The system in one paragraph

A laptop plays a tennis/badminton video using a Python (PySide6/Qt) player.
The video has been analyzed offline: every racket strike and ball bounce was
detected from the audio and written to a JSON "timeline" — each event has a
timestamp, an intensity (0–1), a type (`strike` / `bounce`), and supporting
acoustic features. When the user starts playback, an Android phone — already
connected to the laptop over local Wi-Fi — receives that whole timeline, then
schedules a vibration for each event at its exact moment, in sync with the
video. The user feels the match.

The phone is a **dumb actuator**. All analysis, all decisions about timing,
all authority over the timeline lives on the laptop. The phone receives data
and translates it into vibration.

---

## 2. Architecture overview

```
   ┌──────────────────────── Laptop ────────────────────────┐
   │  Python video player (already built)                   │
   │   ├─ loads <video>.haptic.json (the timeline)          │
   │   ├─ plays the video                                   │
   │   └─ runs the network server (this is what's new       │
   │      from the laptop side; minimal additions)          │
   └─────────────────────────────────────────────────────────┘
                            │ Wi-Fi LAN
                            │
   ┌──────────────────────── Phone ─────────────────────────┐
   │  Android haptic client (THIS PROJECT)                  │
   │   ├─ discovers laptop via NSD                          │
   │   ├─ TCP control channel (timeline, config)            │
   │   ├─ UDP sync pulses (clock alignment)                 │
   │   ├─ schedules vibrations against a synced clock       │
   │   └─ minimal UI: connection state + a couple knobs     │
   └─────────────────────────────────────────────────────────┘
```

Key decisions, with the rationale that constrains the implementation:

**Transport: dual TCP + UDP.** TCP carries the things that must arrive in order
(handshake, capability report, the full timeline, pause/resume). UDP carries
the things that must be fast and are disposable if dropped (periodic sync
pulses giving the current media-time during playback). This split is the
standard real-time-system pattern and the rationale is "reliability where you
need it, speed where you don't."

**Timeline is pre-loaded, then only the clock streams.** Rather than streaming
individual event commands during playback, the laptop sends the *entire*
haptic timeline once at startup over TCP. During playback the laptop only
broadcasts lightweight "current media-time = X" sync pulses over UDP a few
times per second. The phone runs the pre-loaded timeline against its own
clock, corrected by those pulses. **Critical property:** a few dropped sync
pulses cost zero events — the phone already has all of them and coasts on its
local clock between pulses. This is the chief robustness win of this design
over event-streaming.

**Discovery: NSD (Bonjour/mDNS).** The laptop advertises a service like
`_haptics._tcp`; the phone browses for it and learns the IP/port
automatically. The user never types an IP. NSD on Android is `NsdManager`.

**Phone is a passive actuator.** The phone does no audio analysis, no event
detection, no classification. It receives a timeline, schedules vibrations,
and reports back its haptic capabilities so the laptop knows what it can do.

---

## 3. The timeline JSON (input data format)

The laptop sends one of these to the phone over TCP after connection. The
phone parses it and indexes by time. Schema (v2):

```jsonc
{
  "version": 2,
  "source": "match.mp4",          // informational
  "duration": 412.5,              // total video duration, seconds
  "sample_rate_analyzed": 22050,  // informational
  "calibration": {
    "pct_low": 10.0, "pct_high": 90.0,
    "lo_db": -37.6, "hi_db": -30.9,
    "note": "intensity is relative to THIS video's hit-loudness range"
  },
  "params": { /* analyzer settings used; informational */ },
  "events": [
    {
      "time": 12.480,             // seconds, matches laptop's media clock
      "intensity": 0.82,          // 0.0..1.0, calibrated per video (see note)
      "type": "strike",           // "strike" or "bounce"
      "db": -31.6,                // raw loudness, for re-mapping if desired
      "hf_ratio": 0.34,           // high-frequency-energy ratio (feature)
      "centroid": 2480.0          // spectral centroid Hz (feature)
    },
    ...
  ]
}
```

Important properties the phone code must respect:

- **`time` is in seconds** as a float; *not* milliseconds. The phone's internal
  scheduling can use milliseconds but the wire format is seconds.
- **`intensity` is relative to each video**, not absolute. A "0.7" in one
  video does not mean the same dB as a "0.7" in another. This is intentional
  — every video is calibrated to its own loudness range so it feels right
  without per-video manual tuning. The phone should not try to "correct" or
  re-scale it.
- **`type` can be `strike` or `bounce`** in v2. The phone may use type to
  select a different haptic *pattern* (e.g. crisp click for strike, soft thud
  for bounce), but treating them as a single class is also valid. Type is not
  guaranteed to be one of just those two in future timeline versions; an
  unknown type should fall back to a default pattern, not crash.
- **`hf_ratio`, `centroid`, `db`** are extra features the phone does not need
  to use, but should preserve if it ever logs or echoes events.

The phone should sort events by `time` after loading (in case the source
isn't strictly sorted) and build a binary-searchable index for fast "next
event after t" lookups.

---

## 4. Network protocol

### 4.1 Discovery

The laptop registers an NSD service with type `_haptics._tcp` and a chosen
port (e.g. 47821). The phone uses `NsdManager.discoverServices(...)` with the
same type, then `resolveService(...)` on the matching `NsdServiceInfo` to
get the host/port, then opens a TCP socket to that host:port.

On Android, the relevant pieces are:
- `NsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)`
- A `NsdManager.DiscoveryListener` whose `onServiceFound` filters by
  service name/type, then calls `nsdManager.resolveService(...)`
- A `NsdManager.ResolveListener` whose `onServiceResolved` yields the
  `NsdServiceInfo` with `.host` (an `InetAddress`) and `.port`
- **Always** call `stopServiceDiscovery(listener)` on app pause/destroy.
  `NsdManager` is leaky if discovery is left running.
- Resolve sometimes fails sporadically; retry once on
  `onResolveFailed`.

### 4.2 TCP control channel — messages

The TCP socket is a length-prefixed JSON message stream. Each message:

```
[4-byte big-endian length N][N bytes of UTF-8 JSON]
```

Length-prefixing avoids needing newline framing (timelines may contain
newlines inside JSON). The phone reads exactly 4 bytes, decodes N, reads
exactly N bytes, parses as JSON.

Messages, identified by a `"msg"` field:

**Phone → Laptop on connection:**
```json
{ "msg": "hello",
  "client": "android-haptic",
  "client_version": "0.1",
  "capabilities": {
    "amplitude_control": true,        // Vibrator.hasAmplitudeControl()
    "primitives": ["CLICK","TICK","LOW_TICK","THUD"],  // those supported
    "vibrator_api": 31                // Build.VERSION.SDK_INT, roughly
  }
}
```

**Laptop → Phone, in response (or whenever a new video is selected):**
```json
{ "msg": "timeline", "data": { ...the full timeline JSON above... } }
```

**Clock-sync exchange** — repeat several times right after `hello`/`timeline`:
- Phone → Laptop:  `{"msg":"time_req","t0_client_ns":1234567890}`
  (`t0_client_ns` = phone's `System.nanoTime()` at send)
- Laptop → Phone:  `{"msg":"time_resp","t0_client_ns":1234567890,
                    "t_server_ns":9876543210}`
  (laptop fills `t_server_ns` from its own monotonic clock when received)
- Phone records on receive: t1_client_ns = `System.nanoTime()`. Then:
    rtt = t1_client_ns - t0_client_ns
    offset = t_server_ns - (t0_client_ns + rtt/2)
  Do this 5–10 times in quick succession; **discard the highest-RTT half**
  and average the rest. Store `clock_offset_ns` for later use.

**Playback control (laptop → phone):**
```json
{ "msg": "play",  "media_t": 12.345 }     // playback started at this media-time
{ "msg": "pause", "media_t": 12.345 }
{ "msg": "seek",  "media_t": 42.000 }     // jumped; clear upcoming scheduled vibrations
{ "msg": "rate",  "rate": 0.5 }           // playback rate changed (slow-mo)
```

**Disconnection:** either side may close the TCP socket cleanly. The phone
should treat unexpected close as "go back to discovering."

### 4.3 UDP sync pulses — during playback

The laptop sends short UDP datagrams to the phone's port (the phone's UDP
port is included as `"udp_port": NNNN` in its `hello` message). Each pulse
is plain JSON:

```json
{ "msg": "sync", "media_t": 12.345, "t_server_ns": 9876543210, "rate": 1.0 }
```

Sent at ~5–10 Hz during playback. The phone uses each pulse to:
1. Convert `t_server_ns` to phone-local time via the clock offset.
2. Verify that its assumed media-time isn't drifting from the laptop's.
3. Re-anchor its local media-time clock: `media_t_at_local_time(now)` becomes
   the source of truth for "when is each event?"

The phone should **not** wait for sync pulses before scheduling. Sync pulses
are corrections, not commands. The timeline + the initial clock-sync gives
the phone everything it needs to schedule vibrations; sync pulses just keep
it from drifting.

If sync pulses stop arriving for > ~2 seconds, the phone may assume the
laptop paused or disconnected. (Pause/seek messages over TCP are the
authoritative signal, but sync-pulse silence is a useful corroboration.)

---

## 5. Clock model

The phone maintains a function `mediaTime() -> Float (seconds)` that returns
the current media-time as accurately as possible. The model:

- After clock-sync, the phone knows `clock_offset_ns` between its monotonic
  clock and the laptop's monotonic clock.
- When `play` arrives with `media_t = M` and a server timestamp `S`, the
  phone records an anchor: `(anchor_media_t = M, anchor_server_ns = S,
  rate = 1.0)`.
- `mediaTime()` is then computed each call as:
    ```
    now_server_ns = System.nanoTime() + clock_offset_ns
    elapsed_s = (now_server_ns - anchor_server_ns) / 1e9
    media_t = anchor_media_t + elapsed_s * rate
    ```
- Each incoming `sync` re-anchors the phone's anchor to the laptop's
  authoritative `media_t` and `t_server_ns`. This corrects drift.
- `pause` freezes by setting rate to 0 (so `mediaTime()` returns a constant).
- `seek` clears any pending scheduled vibrations whose media_t is now in the
  past relative to the new anchor.
- `rate` changes update the anchor's rate, so scheduling math still works at
  slow/fast playback.

**Scheduling vibrations:** for each event with media-time `e.time`, compute
the phone-local nanosecond time at which to fire it:
    ```
    delta_media_s = e.time - mediaTime()
    fire_at_local_ns = System.nanoTime() + delta_media_s * 1e9 / rate
    ```
Use a `ScheduledExecutorService` (or coroutine `delay`) to fire at that
moment. On `seek`, cancel scheduled tasks beyond a small lookahead and
reschedule the new set.

A reasonable strategy is "schedule the next ~1 second of upcoming events; on
each sync pulse, top up to maintain the 1-second lookahead." This keeps the
scheduler small and lets re-anchoring affect events that haven't fired yet.

---

## 6. Haptics: tiered playback by device capability

This is the most platform-specific part of the project, and the most
device-dependent. The phone should detect what its vibrator can do and pick
the best available tier.

**API minimums and detection (verified against current Android docs):**
- `VibrationEffect` (general) — API 26+ (Build.VERSION_CODES.O)
- `VibrationEffect.createOneShot(duration, amplitude)` — API 26+, amplitude
  0–255, requires `Vibrator.hasAmplitudeControl() == true` for the amplitude
  to actually take effect (else amplitude is ignored).
- `VibrationEffect.Composition` with primitives — API 30+
  (Build.VERSION_CODES.R).
- `Vibrator.arePrimitivesSupported(...)` returning per-primitive booleans
  reliably — API 31+. (On API 30 the call exists but returns `true` for
  everything regardless, which is misleading; treat API 30 as "compositions
  may not actually work as named.")
- `VibratorManager` (the new accessor) — API 31+. On API < 31 use the
  deprecated `(Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE)`.

**Recommended minSdk: 31 (Android 12).** This gets you reliable primitive
capability detection and a clean haptics story. minSdk 26 is possible but
the bottom tier dominates and the experience suffers.

**Three tiers, in descending preference:**

**Tier 1 — Composition with primitives (API 31+, primitives supported).**
Build a `VibrationEffect` per event type by composing primitives:
- `strike` → e.g. `PRIMITIVE_CLICK` scaled by intensity (the sharp, crisp
  one)
- `bounce` → e.g. `PRIMITIVE_THUD` scaled by intensity, or `PRIMITIVE_LOW_TICK`
- The `addPrimitive(id, scale)` `scale` parameter is 0.0–1.0 and maps
  directly from our `intensity`.

Example pseudocode:
```kotlin
val effect = VibrationEffect.startComposition()
    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity)
    .compose()
vibratorManager.defaultVibrator.vibrate(effect)
```

Check primitive support at startup:
```kotlin
val primitives = intArrayOf(
    VibrationEffect.Composition.PRIMITIVE_CLICK,
    VibrationEffect.Composition.PRIMITIVE_TICK,
    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
    VibrationEffect.Composition.PRIMITIVE_THUD
)
val supported = vibrator.arePrimitivesSupported(*primitives)
// `supported[i]` corresponds to `primitives[i]`
```

**Tier 2 — Amplitude-modulated one-shot waveform (any amplitude-control device).**
If `vibrator.hasAmplitudeControl()` but compositions aren't usable:
```kotlin
val duration = 30L  // ms; short for impact-like feel
val amplitude = (intensity * 255).toInt().coerceIn(1, 255)
val effect = VibrationEffect.createOneShot(duration, amplitude)
vibrator.vibrate(effect)
```

**Tier 3 — Plain timed buzz (last-resort, no amplitude control).**
Use `vibrator.vibrate(durationMs)` with duration proportional to intensity.
This is buzzy and ugly; tell the user the device's haptic hardware is basic.

Always require the `VIBRATE` permission in `AndroidManifest.xml`. No runtime
permission prompt is needed for vibration on any current API.

**Reported capabilities in `hello`:** include `amplitude_control` (boolean),
the list of supported primitive names (strings, not ints), and the API level.
The laptop doesn't currently change behavior based on this, but the data is
useful for diagnostics and future use.

---

## 7. The minimum viable UI

Don't overbuild the UI. The phone is a haptic device, not an app to interact
with. A minimal UI is:

- **Connection status**: "Searching for laptop…" / "Connected to <name>" /
  "Disconnected — searching again…"
- **Playback state**: a small indicator showing playing / paused / current
  media-time.
- **Haptic strength scaler** (a slider): multiplies all intensities by 0.5–1.5
  so the user can taste-tune to their device. Persist with `SharedPreferences`
  or DataStore.
- **Minimum intensity threshold** (a slider): below this, no vibration is
  played (the per-video "soft strikes don't buzz" semantic we use on the
  laptop). Default ~0.15.
- A small diagnostics view (collapsed by default): clock offset estimate,
  events scheduled, last sync pulse time, frame the user can read if things
  go wrong.

That's it. No browsing of timelines, no in-app video, no settings beyond the
two sliders.

---

## 8. Project structure (suggested)

```
app/
  build.gradle                 // minSdk 31, vibrate permission, kotlinx-coroutines
  src/main/
    AndroidManifest.xml
    java/com/example/haptic/
      MainActivity.kt          // hosts the UI + lifecycle
      ui/
        ConnectionView.kt      // status + sliders + diagnostics
      net/
        Discovery.kt           // NsdManager wrapper
        ControlChannel.kt      // TCP + JSON message protocol
        SyncChannel.kt         // UDP listener loop
        ClockSync.kt           // SNTP-style handshake
      timeline/
        Timeline.kt            // data class + parsing + indexed lookup
        Scheduler.kt           // schedule vibrations against media-clock
      haptic/
        Capabilities.kt        // detect tier at startup, report
        HapticPlayer.kt        // play one event according to its tier
      clock/
        MediaClock.kt          // mediaTime() with anchors + rate
    res/
      layout/, values/, ...
```

Keep `net/`, `timeline/`, and `clock/` free of Android-Activity dependencies
where reasonable — they're plain Kotlin and unit-testable.

---

## 9. Build it in this order

The reason the project is naturally layered: each step is testable before
the next, so failures are isolated.

1. **Project skeleton + permissions + capability detection.** App launches,
   prints capability tier to a TextView. No networking. Verifies haptic
   detection works on the target device.
2. **One-shot vibration on button tap.** Confirm Tier 1/2/3 actually fire
   different feels on the device. Useful baseline before any timing.
3. **NSD discovery.** App finds a fake laptop service (you can advertise one
   from `dns-sd` on Mac during dev). Status updates to "Connected to <X>".
4. **TCP control channel.** Define the message classes, implement
   length-prefixed JSON read/write loop. Echo a `hello` and a fake `timeline`
   message; parse and log.
5. **Timeline + scheduler (no clock sync yet).** Schedule events relative to
   "first event time = now" — fires vibrations as if playback started this
   instant. Lets you feel a real timeline through the device end-to-end.
6. **Clock sync.** Implement the SNTP-style handshake. Verify offset is in
   the low milliseconds on a quiet LAN.
7. **UDP sync pulses.** Receive and re-anchor the media-clock.
8. **Play / pause / seek / rate messages.** Re-schedule on each.
9. **UI polish** (sliders, persistence). Add reconnect logic for when the
   laptop's app restarts.

Test step 5 with a hard-coded timeline before involving the laptop. Test
step 6/7 against a tiny standalone Python sync-server before involving the
real laptop player. Layered tests = isolated bugs.

---

## 10. Known caveats and design constraints to honor

- **Haptics require the foreground / screen-on** on most Android devices.
  This is not a project requirement to "fix"; the assumed use is that the
  viewer holds the phone while watching. Just don't be surprised by it.
- **Low Power Mode suppresses haptics.** Detect with
  `PowerManager.isPowerSaveMode()` and surface a banner if so.
- **Phone wakelock**: hold a partial wake lock during playback so the screen
  staying on isn't required, OR set the window's `KEEP_SCREEN_ON` flag while
  connected. The latter is simpler and matches the "user is watching" assumption.
- **Don't process audio on the phone.** Even if it seems tempting later
  (live analysis would be cool). It violates the architecture: the laptop is
  the authority. Keep the phone dumb.
- **Don't add ML on the phone.** Same reason.
- **Cross-platform later.** The protocol is plain JSON + TCP/UDP +
  mDNS, deliberately not tied to anything Android-specific. iOS or web
  clients could be added later by re-implementing this same protocol.
- **Type set is not closed.** A timeline could include `bounce`, `strike`,
  and in the future `scrape` or others. Default fallback: treat unknown type
  the same as `strike` in Tier 1, or as a generic short vibration in lower
  tiers. Don't crash on unknown types.
- **Intensity is per-video relative.** Resist any urge to "normalize" or
  "correct" across videos. The laptop calibration is intentional.

---

## 11. Out of scope (don't build these)

- Phone-side audio capture or analysis.
- Playing video on the phone.
- A multi-room / multi-phone broadcast mode.
- Cloud sync, accounts, telemetry beyond local logs.
- Internet connectivity. This is a local-network app.

---

## 12. Quick reference: the four files Claude Code should produce first

To prove the foundation works, the smallest possible useful build is:

1. `MainActivity.kt` — single Activity with a status TextView and two
   sliders, holds `KEEP_SCREEN_ON`.
2. `Capabilities.kt` — detects haptic tier; logs it.
3. `Discovery.kt` — finds `_haptics._tcp` and surfaces (host, port).
4. `HapticPlayer.kt` — plays one event according to the detected tier.

Once those four work and you can tap a button to vibrate, layering on the
TCP channel, timeline, clock sync, and UDP loop is straightforward.

---

**End of architecture document.** When in doubt, the design principle is:
*the phone receives data and translates it into vibration; everything else
is the laptop's job.*
