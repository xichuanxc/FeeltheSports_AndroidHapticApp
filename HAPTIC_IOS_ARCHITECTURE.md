# Haptic Sports Client — iOS Architecture & Implementation Reference

**Companion to `HAPTIC_PROTOCOL.md`** (read that first — it defines everything on the wire)

This document maps every Android concept from `HAPTIC_ANDROID_ARCHITECTURE.md`
to its iOS equivalent. The protocol is identical; only the platform APIs differ.

---

## 1. What you get for free

`HAPTIC_PROTOCOL.md` is fully reusable as-is:
- All message schemas (`hello`, `timeline`, `play`, `pause`, `seek`, `rate`, `sync`, `time_req/resp`)
- The TCP frame format (4-byte big-endian length + UTF-8 JSON)
- The UDP sync channel
- The clock model and clock-sync algorithm
- The timeline data format and `vision_type` semantics
- The example session trace

The only Android-specific content in that doc is the Kotlin code snippets in
§3 and §6.3 — use the Swift equivalents in this document instead.

---

## 2. Platform API mapping

| Concept | Android | iOS |
|---|---|---|
| Service discovery | `NsdManager` | `Network.framework` `NWBrowser` |
| TCP socket | `java.net.Socket` | `NWConnection` (TCP) |
| UDP socket | `java.net.DatagramSocket` | `NWListener` (UDP) |
| Monotonic clock | `System.nanoTime()` | `clock_gettime(CLOCK_MONOTONIC)` |
| Haptic Tier 1 | `VibrationEffect.Composition` | `CoreHaptics` `CHHapticEngine` |
| Haptic Tier 2 | `VibrationEffect.createOneShot` | `UIImpactFeedbackGenerator` |
| Haptic Tier 3 | `vibrator.vibrate(durationMs)` | `UINotificationFeedbackGenerator` |
| Capability check | `vibrator.arePrimitivesSupported()` | `CHHapticEngine.capabilitiesForHardware().supportsHaptics` |
| Screen keep-on | `view.keepScreenOn = true` | `UIApplication.shared.isIdleTimerDisabled = true` |
| Async concurrency | Kotlin coroutines / `delay()` | Swift `async/await` / `Task.sleep` |
| Persisted settings | `SharedPreferences` | `UserDefaults` |
| Activity lifecycle | `onResume` / `onPause` | `sceneDidBecomeActive` / `sceneWillResignActive` |
| Connection state | `sealed class ConnectionStatus` | `enum ConnectionStatus` |

---

## 3. Project structure

```
HapticActuatorIOS/
  HapticActuatorIOS.xcodeproj
  HapticActuatorIOS/
    App.swift                   // @main SwiftUI App + lifecycle observers
    ContentView.swift           // connection status + sliders + test buttons
    net/
      Discovery.swift           // NWBrowser for _haptics._tcp
      ControlChannel.swift      // NWConnection TCP + JSON framing + callbacks
      SyncChannel.swift         // NWListener UDP + sync dispatch
      ClockSync.swift           // SNTP-style time_req/resp handshake
    timeline/
      Timeline.swift            // HapticEvent struct, binary index
      Scheduler.swift           // Task-based scheduler (chunked sleep + batching)
    haptic/
      HapticCapabilities.swift  // tier detection + capability report
      HapticPlayer.swift        // CHHapticEngine / UIImpact; BatchEvent; playBatch
    clock/
      MediaClock.swift          // syncAnchor, mediaTime(), setOffset, isPlaying
```

`net/`, `timeline/`, `clock/`, and `haptic/` should have no SwiftUI dependency
and be independently testable.

---

## 4. Timeline data format (wire, as received)

```swift
struct HapticEvent {
    let time: Double        // media-time in SECONDS — not milliseconds
    let intensity: Float    // 0.0..1.0, per-video calibrated — do not renormalise
    let visionType: String? // "strike", "bounce", or nil
}
```

Parse `vision_type` defensively:
```swift
let visionType = json["vision_type"] as? String   // nil if absent or JSON null
```

`type` is always `"hit"` on the wire. Switch on `visionType` for haptic
character selection — never on `type`. Unknown values fall through to the
`nil` / default branch (never crash on them).

Sort events by `time` after loading and build a binary-search index for
`indexFrom(t: Double) -> Int`.

---

## 5. Service discovery (Bonjour)

```swift
import Network

let browser = NWBrowser(
    for: .bonjourWithType("_haptics._tcp", domain: "local."),
    using: .tcp
)

browser.browseResultsChangedHandler = { results, changes in
    for change in changes {
        if case .added(let result) = change {
            self.lastEndpoint = result.endpoint
            self.connectTo(endpoint: result.endpoint, name: result.endpoint.debugDescription)
        }
        // .removed does NOT mean the TCP connection is dead — do not tear it down here.
        // Let NWConnection state drive disconnection logic.
    }
}
browser.start(queue: .main)
```

Key differences from Android's `NsdManager`:
- No separate resolve step — `NWEndpoint` from `NWBrowser` is directly
  connectable; the OS resolves mDNS on `NWConnection.start()`.
- Service type is `"_haptics._tcp"` without `.local.` suffix in the call —
  the framework adds it.
- Stop browsing once connected: `browser.cancel()`. Restart on disconnect.
- A result being removed is not authoritative — only `NWConnection` state
  `.failed` or `.cancelled` should drive reconnection.

---

## 6. TCP control channel (`ControlChannel.swift`)

```swift
let conn = NWConnection(to: endpoint, using: .tcp)
conn.stateUpdateHandler = { [weak self] state in
    switch state {
    case .ready:
        self?.sendHello()
        self?.receiveLoop()
    case .failed, .cancelled:
        self?.onDisconnected?()
    default: break
    }
}
conn.start(queue: .global(qos: .userInitiated))
```

### 6.1 Framing: read one message

```swift
func receiveExactly(_ n: Int) async throws -> Data {
    var collected = Data()
    while collected.count < n {
        let needed = n - collected.count
        let chunk = try await withCheckedThrowingContinuation { cont in
            conn.receive(minimumIncompleteLength: needed, maximumLength: needed) { data, _, _, err in
                if let err { cont.resume(throwing: err); return }
                cont.resume(returning: data ?? Data())
            }
        }
        collected.append(chunk)
    }
    return collected
}

func receiveMessage() async throws -> [String: Any] {
    let header = try await receiveExactly(4)
    let length = Int(header.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian })
    guard length > 0, length <= 64 * 1024 * 1024 else { throw ProtocolError.badFrame }
    let body = try await receiveExactly(length)
    guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
    else { throw ProtocolError.badJSON }
    return json
}
```

### 6.2 Framing: write one message

```swift
func sendMessage(_ json: [String: Any]) {
    guard let body = try? JSONSerialization.data(withJSONObject: json) else { return }
    var length = UInt32(body.count).bigEndian
    var frame = Data(bytes: &length, count: 4)
    frame.append(body)
    conn.send(content: frame, completion: .idempotent)
}
```

### 6.3 Receive loop — single owner of TCP reads

The receive loop is the **only** reader of the TCP socket. Clock-sync
responses route through an `AsyncStream` in `ClockSync` — never by reading
the socket inline. Reading from two places causes a race where clock-sync
consumes `timeline` or `play` messages intended for the main loop.

```swift
func receiveLoop() {
    Task {
        while !Task.isCancelled {
            guard let msg = try? await receiveMessage() else { break }
            await dispatch(msg)
        }
        await MainActor.run { onDisconnected?() }
    }
}

@MainActor
func dispatch(_ msg: [String: Any]) {
    switch msg["msg"] as? String {
    case "timeline":  onTimeline?(msg["data"] as! [String: Any])
    case "play":      onPlay?(msg["media_t"] as! Double,
                              msg["t_server_ns"] as! Int64,
                              msg["rate"] as! Double)
    case "pause":     onPause?(msg["media_t"] as! Double, msg["t_server_ns"] as! Int64)
    case "seek":      onSeek?(msg["media_t"] as! Double, msg["t_server_ns"] as! Int64)
    case "rate":      onRate?(msg["rate"] as! Double, msg["t_server_ns"] as! Int64)
    case "time_resp":
        // CRITICAL: t1 must be captured before MainActor dispatch —
        // queue latency otherwise inflates the RTT measurement.
        let t1 = nanoTime()
        onTimeResp?(msg["t0_client_ns"] as! Int64, msg["t_server_ns"] as! Int64, t1)
    default: break
    }
}
```

`t_server_ns` is present in every control message. Always use it when
anchoring the media clock.

### 6.4 `hello` message

```swift
let hello: [String: Any] = [
    "msg": "hello",
    "client": "ios-haptic",
    "client_version": "0.1",
    "udp_port": syncChannel.port,
    "capabilities": [
        "amplitude_control": CHHapticEngine.capabilitiesForHardware().supportsHaptics,
        "primitives": supportedPrimitiveNames(),
        "vibrator_api": Int(ProcessInfo.processInfo.operatingSystemVersion.majorVersion)
    ]
]
```

`supportedPrimitiveNames()` returns `["CLICK", "TICK", "LOW_TICK", "THUD"]`
if `supportsHaptics`, empty array otherwise. These strings are
protocol-defined, not platform-defined.

---

## 7. UDP sync channel (`SyncChannel.swift`)

```swift
let params = NWParameters.udp
let listener = try! NWListener(using: params)
var port: Int = 0

listener.stateUpdateHandler = { state in
    if case .ready = state, let p = listener.port {
        port = Int(p.rawValue)
    }
}
listener.newConnectionHandler = { conn in
    conn.start(queue: .global())
    self.receiveSync(from: conn)
}
listener.start(queue: .global())

func receiveSync(from conn: NWConnection) {
    conn.receiveMessage { data, _, _, _ in
        guard let data,
              let json    = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["msg"] as? String == "sync",
              let mediaT   = json["media_t"]    as? Double,
              let serverNs = json["t_server_ns"] as? Int64,
              let rate     = json["rate"]        as? Double
        else { self.receiveSync(from: conn); return }
        Task { @MainActor in
            self.mediaClock.syncAnchor(mediaT: mediaT, serverNs: serverNs, rate: rate)
            self.lastSyncMediaT = mediaT
        }
        self.receiveSync(from: conn)
    }
}
```

---

## 8. Monotonic clock

```swift
func nanoTime() -> Int64 {
    var ts = timespec()
    clock_gettime(CLOCK_MONOTONIC, &ts)
    return Int64(ts.tv_sec) * 1_000_000_000 + Int64(ts.tv_nsec)
}
```

Matches Android's `System.nanoTime()`: monotonic, not wall-clock, not
affected by NTP slew.

---

## 9. Clock sync (`ClockSync.swift`)

**8 rounds, 20 ms between requests, 2 s response timeout.**
Discard the high-RTT half, average the rest.

```swift
actor ClockSync {
    private var continuation: AsyncStream<(rtt: Int64, offset: Int64)>.Continuation?
    private let stream: AsyncStream<(rtt: Int64, offset: Int64)>

    init() {
        var cont: AsyncStream<(rtt: Int64, offset: Int64)>.Continuation?
        stream = AsyncStream { cont = $0 }
        continuation = cont
    }

    // Called from ControlChannel.dispatch; t1 already captured on receive queue.
    func onTimeResp(t0: Int64, tServer: Int64, t1: Int64) {
        let rtt    = t1 - t0
        let offset = tServer - (t0 + rtt / 2)
        continuation?.yield((rtt: rtt, offset: offset))
    }

    func sync(channel: ControlChannel, rounds: Int = 8) async -> Int64 {
        for _ in 0..<rounds {
            let t0 = nanoTime()
            await channel.send(["msg": "time_req", "t0_client_ns": t0])
            try? await Task.sleep(nanoseconds: 20_000_000)   // 20 ms between requests
        }
        var results: [(rtt: Int64, offset: Int64)] = []
        let deadline = ContinuousClock.now + .seconds(2)
        for await sample in stream {
            results.append(sample)
            if results.count >= rounds || ContinuousClock.now > deadline { break }
        }
        guard !results.isEmpty else { return 0 }
        let sorted = results.sorted { $0.rtt < $1.rtt }
        let kept   = sorted.prefix(max(1, sorted.count / 2))
        return kept.map(\.offset).reduce(0, +) / Int64(kept.count)
    }
}
```

**Critical:** `t1` must be captured in the `ControlChannel` receive task
**before** any `await` or `@MainActor` dispatch — capturing it after includes
queue latency and inflates RTT.

---

## 10. Media clock (`MediaClock.swift`)

```swift
class MediaClock {
    private struct Anchor {
        var mediaT:   Double
        var serverNs: Int64
        var rate:     Double
    }
    private var anchor: Anchor? = nil
    private(set) var clockOffsetNs: Int64 = 0

    // isPlaying derives from rate — no separate bool (matches Android)
    var isPlaying: Bool { (anchor?.rate ?? 0.0) != 0.0 }
    var rate: Double    { anchor?.rate ?? 1.0 }

    func mediaTime() -> Double {
        guard let a = anchor else { return 0.0 }
        if a.rate == 0.0 { return a.mediaT }
        return a.mediaT + Double(nowServerNs() - a.serverNs) / 1_000_000_000.0 * a.rate
    }

    private func nowServerNs() -> Int64 { nanoTime() + clockOffsetNs }

    // Primary method — use for ALL incoming control messages and UDP sync pulses.
    // Uses the server's own t_server_ns, which is more accurate than estimating
    // from the local clock alone.
    func syncAnchor(mediaT: Double, serverNs: Int64, rate: Double) {
        anchor = Anchor(mediaT: mediaT, serverNs: serverNs, rate: rate)
    }

    // Update clock offset without disturbing running media time.
    // Compensates anchor.serverNs so mediaTime() stays continuous.
    func setOffset(_ offsetNs: Int64) {
        let delta = offsetNs - clockOffsetNs
        clockOffsetNs = offsetNs
        anchor = anchor.map { Anchor(mediaT: $0.mediaT, serverNs: $0.serverNs + delta, rate: $0.rate) }
    }
}
```

Wire all control messages to `syncAnchor`:
```swift
ch.onPlay  = { t, serverNs, rate in mediaClock.syncAnchor(mediaT: t, serverNs: serverNs, rate: rate) }
ch.onPause = { t, serverNs       in mediaClock.syncAnchor(mediaT: t, serverNs: serverNs, rate: 0.0) }
ch.onSeek  = { t, serverNs       in mediaClock.syncAnchor(mediaT: t, serverNs: serverNs, rate: mediaClock.rate) }
ch.onRate  = { r, serverNs       in mediaClock.syncAnchor(mediaT: mediaClock.mediaTime(), serverNs: serverNs, rate: r) }
```

Call `mediaClock.setOffset(offset)` after `clockSync.sync()` completes.
Never write `clockOffsetNs` directly.

---

## 11. Haptic tier detection (`HapticCapabilities.swift`)

```swift
enum HapticTier { case coreHaptics, uiImpact, none }

struct HapticCapabilities {
    let tier: HapticTier
    let supportsHaptics: Bool
}

func detectCapabilities() -> HapticCapabilities {
    let supports = CHHapticEngine.capabilitiesForHardware().supportsHaptics
    let tier: HapticTier = supports ? .coreHaptics
        : UIDevice.current.model.hasPrefix("iPhone") ? .uiImpact
        : .none
    return HapticCapabilities(tier: tier, supportsHaptics: supports)
}

func supportedPrimitiveNames(_ cap: HapticCapabilities) -> [String] {
    guard cap.supportsHaptics else { return [] }
    return ["CLICK", "TICK", "LOW_TICK", "THUD"]
}
```

Core Haptics is available on iPhone 8+ (iOS 13+). For this app, target
iOS 16 and assume `coreHaptics` on all supported devices.

---

## 12. Haptic playback (`HapticPlayer.swift`)

### 12.1 The playback cancellation problem

Starting a new `CHHapticPatternPlayer` while a previous one is still playing
can cause the engine to drop the first pattern early. For events within
~200 ms of each other, combine them into a single `CHHapticPattern` with
multiple `CHHapticEvent` objects at different `relativeTime` values. The
engine handles the inter-event timing internally without interference.

This mirrors the Android fix where a second `vibrate()` cancels the first —
the solution is identical: batch close events into one engine call.

### 12.2 `BatchEvent`

```swift
struct BatchEvent {
    let visionType: String?
    let intensity: Float
    let delayFromFirstS: Double   // 0.0 for the first event in the batch
}
```

### 12.3 `vision_type` → haptic character

| `visionType` | Sharpness | Feel |
|---|---|---|
| `"strike"` | 0.9 | crisp, sharp click |
| `"bounce"` | 0.2 | soft, heavy thud |
| `nil` / other | 0.5 | neutral hit |

```swift
func hapticSharpness(for visionType: String?) -> Float {
    switch visionType {
    case "strike": return 0.9
    case "bounce": return 0.2
    default:       return 0.5
    }
}
```

### 12.4 `playBatch`

```swift
func playBatch(_ batch: [BatchEvent]) {
    guard !batch.isEmpty else { return }
    switch capabilities.tier {
    case .coreHaptics:
        batch.count == 1
            ? playSingle(visionType: batch[0].visionType, intensity: batch[0].intensity)
            : playCompositionBatch(batch)
    case .uiImpact:
        batch.forEach { playImpact(intensity: $0.intensity) }
    case .none:
        break
    }
}

private func playCompositionBatch(_ batch: [BatchEvent]) {
    guard let engine else { return }
    let events = batch.map { e in
        CHHapticEvent(
            eventType: .hapticTransient,
            parameters: [
                .init(parameterID: .hapticIntensity, value: e.intensity),
                .init(parameterID: .hapticSharpness, value: hapticSharpness(for: e.visionType)),
            ],
            relativeTime: e.delayFromFirstS
        )
    }
    if let pattern = try? CHHapticPattern(events: events, parameters: []),
       let player  = try? engine.makePlayer(with: pattern) {
        try? player.start(atTime: CHHapticTimeImmediate)
    }
}
```

### 12.5 `estimateBatchDuration`

Used by the Scheduler for the post-batch guard delay. Returns 0 for
non-Core-Haptics tiers (no guard needed).

```swift
func estimateBatchDuration(_ batch: [BatchEvent]) -> Double {
    guard capabilities.tier == .coreHaptics, let last = batch.last else { return 0 }
    return last.delayFromFirstS + 0.1   // CHHapticTransient ≈ 100 ms
}
```

### 12.6 `CHHapticEngine` setup

```swift
var engine: CHHapticEngine?

func setupEngine() throws {
    engine = try CHHapticEngine()
    engine?.stoppedHandler = { [weak self] _ in try? self?.engine?.start() }
    engine?.resetHandler   = { [weak self] in   try? self?.engine?.start() }
    try engine?.start()
}
```

### 12.7 Tier 2: `UIImpactFeedbackGenerator`

Must be called on the **main thread**:

```swift
@MainActor
func playImpact(intensity: Float) {
    let style: UIImpactFeedbackGenerator.FeedbackStyle =
        intensity > 0.6 ? .heavy : intensity > 0.3 ? .medium : .light
    let gen = UIImpactFeedbackGenerator(style: style)
    gen.prepare()
    gen.impactOccurred(intensity: CGFloat(intensity))
}
```

---

## 13. Scheduler (`Scheduler.swift`)

### 13.1 Constants

```swift
let staleThresholdS: Double = 0.3   // skip events more than 300 ms in the past
let maxSleepMs: Int64       = 200   // re-anchor clock at most every 200 ms
let batchWindowS: Double    = 0.2   // collect events within 200 ms into one batch
```

### 13.2 Loop structure

```swift
actor Scheduler {
    private var currentTask: Task<Void, Never>?

    func start(timeline: Timeline, mediaClock: MediaClock, player: HapticPlayer,
               strengthScale: Float, minIntensity: Float) {
        currentTask?.cancel()
        currentTask = Task {
            var i = timeline.indexFrom(mediaClock.mediaTime())

            while i < timeline.events.count, !Task.isCancelled {
                let event = timeline.events[i]

                // 1. Chunked sleep: re-read live clock every ≤200 ms
                while !Task.isCancelled {
                    let rate = max(mediaClock.rate, 0.01)
                    let remainingMs = Int64(((event.time - mediaClock.mediaTime()) / rate) * 1000)
                    if remainingMs <= 1 { break }
                    try? await Task.sleep(nanoseconds: UInt64(min(remainingMs, maxSleepMs)) * 1_000_000)
                }
                if Task.isCancelled { break }

                // 2. Stale check
                if event.time < mediaClock.mediaTime() - staleThresholdS {
                    i += 1; continue
                }

                // 3. Intensity filter
                let scaled = min(max(event.intensity * strengthScale, 0), 1)
                if scaled < minIntensity { i += 1; continue }

                // 4. Look-ahead batch: collect events within batchWindowS
                var batch = [BatchEvent(visionType: event.visionType, intensity: scaled, delayFromFirstS: 0)]
                var j = i + 1
                while j < timeline.events.count {
                    let next = timeline.events[j]
                    let gapS = next.time - event.time
                    if gapS > batchWindowS { break }
                    let nextScaled = min(max(next.intensity * strengthScale, 0), 1)
                    if nextScaled >= minIntensity {
                        batch.append(BatchEvent(visionType: next.visionType,
                                                intensity: nextScaled,
                                                delayFromFirstS: gapS))
                    }
                    j += 1
                }

                // 5. Fire batch
                let batchDurationS = player.estimateBatchDuration(batch)
                await MainActor.run { player.playBatch(batch) }

                // 6. Post-batch guard: wait for pattern to finish before continuing.
                //    Prevents the next playBatch from interrupting the current one's tail.
                let compositionEndsAtS = event.time + batchDurationS
                let rate = max(mediaClock.rate, 0.01)
                let guardMs = Int64(((compositionEndsAtS - mediaClock.mediaTime()) / rate) * 1000)
                if guardMs > 1 {
                    try? await Task.sleep(nanoseconds: UInt64(min(guardMs, maxSleepMs)) * 1_000_000)
                }

                // 7. Advance past all events consumed in this batch
                i = j
            }
        }
    }

    func stop() { currentTask?.cancel(); currentTask = nil }
}
```

**When to call `start` / `stop`:**

| Message | Action |
|---|---|
| `play` | `syncAnchor` → `scheduler.start()` |
| `pause` | `syncAnchor(rate: 0.0)` → `scheduler.stop()` |
| `seek` | `syncAnchor` → `scheduler.start()` if `isPlaying` |
| `rate` | `syncAnchor` → `scheduler.start()` if `isPlaying` |
| `onDisconnected` | `scheduler.stop()` |
| Scene inactive | `scheduler.stop()` |

Do not start the scheduler on `timeline` receipt — wait for the `play`
message, which carries the correct media position and `t_server_ns`.

---

## 14. Connection and reconnection

### 14.1 Connection states

```swift
enum ConnectionStatus: Equatable {
    case searching
    case connecting(name: String)
    case reconnecting(name: String)
    case connected(name: String)
}
```

### 14.2 `connectTo(endpoint:name:)`

```swift
@MainActor
func connectTo(endpoint: NWEndpoint, name: String) {
    reconnectTask?.cancel()
    connectionStatus = .connecting(name: name)
    clockOffsetMs = nil; lastSyncMediaT = nil

    syncChannel?.close();         syncChannel    = nil
    controlChannel?.disconnect(); controlChannel = nil

    let sync = SyncChannel()
    sync.onSyncPulse = { [weak self] mediaT, serverNs, rate in
        self?.mediaClock.syncAnchor(mediaT: mediaT, serverNs: serverNs, rate: rate)
        self?.lastSyncMediaT = mediaT
    }
    sync.start()
    syncChannel = sync

    let ch = ControlChannel(endpoint: endpoint, udpPort: sync.port)
    let clockSync = ClockSync()

    ch.onConnected = { [weak self] in
        guard let self else { return }
        connectionStatus = .connected(name: name)
        reconnectDelay = reconnectDelayMin
        Task {
            let offset = await clockSync.sync(channel: ch)
            await MainActor.run {
                self.mediaClock.setOffset(offset)
                self.clockOffsetMs = offset / 1_000_000
            }
        }
    }
    ch.onTimeResp  = { t0, ts, t1 in Task { await clockSync.onTimeResp(t0: t0, tServer: ts, t1: t1) } }
    ch.onTimeline  = { [weak self] data in
        let tl = Timeline.parse(data)
        self?.currentTimeline = tl
        self?.eventCount = tl.events.count
        // Do NOT start the scheduler here — wait for the play message
    }
    ch.onPlay  = { [weak self] t, serverNs, rate in
        guard let self else { return }
        mediaClock.syncAnchor(mediaT: t, serverNs: serverNs, rate: rate)
        if let tl = currentTimeline {
            Task { await scheduler.start(timeline: tl, mediaClock: mediaClock, player: hapticPlayer,
                                         strengthScale: strengthScale, minIntensity: minIntensity) }
        }
    }
    ch.onPause = { [weak self] t, serverNs in
        self?.mediaClock.syncAnchor(mediaT: t, serverNs: serverNs, rate: 0.0)
        Task { await self?.scheduler.stop() }
    }
    ch.onSeek  = { [weak self] t, serverNs in
        guard let self else { return }
        mediaClock.syncAnchor(mediaT: t, serverNs: serverNs, rate: mediaClock.rate)
        if mediaClock.isPlaying, let tl = currentTimeline {
            Task { await scheduler.start(timeline: tl, mediaClock: mediaClock, player: hapticPlayer,
                                         strengthScale: strengthScale, minIntensity: minIntensity) }
        }
    }
    ch.onRate  = { [weak self] rate, serverNs in
        guard let self else { return }
        mediaClock.syncAnchor(mediaT: mediaClock.mediaTime(), serverNs: serverNs, rate: rate)
        if mediaClock.isPlaying, let tl = currentTimeline {
            Task { await scheduler.start(timeline: tl, mediaClock: mediaClock, player: hapticPlayer,
                                         strengthScale: strengthScale, minIntensity: minIntensity) }
        }
    }
    ch.onDisconnected = { [weak self] in
        guard let self else { return }
        Task { await scheduler.stop() }
        syncChannel?.close(); syncChannel = nil
        currentTimeline = nil
        connectionStatus = .reconnecting(name: name)
        scheduleReconnect()
    }

    ch.connect()
    controlChannel = ch
    lastEndpoint = endpoint
    lastName = name
}
```

### 14.3 Exponential backoff reconnect

```swift
private var lastEndpoint: NWEndpoint?
private var lastName: String = ""
private var reconnectDelay: TimeInterval = 1.0
private let reconnectDelayMin: TimeInterval = 1.0
private let reconnectDelayMax: TimeInterval = 30.0
private var reconnectTask: Task<Void, Never>?

@MainActor
private func scheduleReconnect() {
    browser.start(queue: .main)   // NSD fallback: catches IP changes / re-advertisement

    guard let endpoint = lastEndpoint else { return }
    let delay = reconnectDelay
    reconnectDelay = min(reconnectDelay * 2, reconnectDelayMax)

    reconnectTask?.cancel()
    reconnectTask = Task { @MainActor in
        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
        guard !Task.isCancelled else { return }
        if case .reconnecting = connectionStatus {
            connectTo(endpoint: endpoint, name: lastName)
        }
    }
}
```

### 14.4 App lifecycle

```swift
.onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
    reconnectDelay = reconnectDelayMin
    if case .connected   = connectionStatus { return }
    if case .connecting  = connectionStatus { return }
    scheduleReconnect()
}
.onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in
    reconnectTask?.cancel()
    Task { await scheduler.stop() }
    syncChannel?.close();         syncChannel    = nil
    controlChannel?.disconnect(); controlChannel = nil
    browser.cancel()
    connectionStatus = .searching
}
```

---

## 15. UI (`ContentView.swift` — SwiftUI)

```swift
@State private var connectionStatus: ConnectionStatus = .searching
@State private var eventCount: Int?      = nil
@State private var clockOffsetMs: Int64? = nil
@State private var lastSyncMediaT: Double? = nil
@State private var strengthScale: Float = 1.0
@State private var minIntensity:  Float = 0.15
```

Persist slider values to `UserDefaults` on change and restore in `onAppear`.

### 15.1 Status card colors

| State | Color |
|---|---|
| `.searching` | `Color(.systemGray5)` |
| `.reconnecting` | `Color(.systemRed).opacity(0.2)` |
| `.connecting` | `Color(.systemYellow).opacity(0.2)` |
| `.connected` | `Color(.systemGreen).opacity(0.2)` |

### 15.2 Diagnostics (always visible)

- Clock sync offset in ms (`nil` = not yet synced)
- Timeline event count (`nil` = no timeline loaded)
- Last UDP sync `media_t` in seconds (`nil` = no pulse received)
- Haptic tier label

### 15.3 Controls

- **Strength slider**: 0.5×–1.5×, persisted as `"strength_scale"`
- **Min intensity slider**: 0.0–0.5, persisted as `"min_intensity"`
- **Strike / Bounce test buttons**: call `hapticPlayer.play(visionType:intensity:)` directly
- **Test Timeline button**: loads a built-in 6-event sequence over ~3 s

### 15.4 Screen idle disable

```swift
.onAppear  { UIApplication.shared.isIdleTimerDisabled = true  }
.onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
```

---

## 16. iOS-specific caveats

### 16.1 `CHHapticEngine` lifetime

The engine stops on phone calls, Siri, alerts, and background entry. Always
implement both handlers:

```swift
engine?.stoppedHandler = { [weak self] _ in try? self?.engine?.start() }
engine?.resetHandler   = { [weak self] in   try? self?.engine?.start() }
```

### 16.2 Haptics require foreground

iOS suppresses haptics when the app is in the background. The use case
(user holding the phone while watching) means the app is always in foreground.
The `willResignActive` handler tears everything down cleanly.

### 16.3 Simulator

`CHHapticEngine.capabilitiesForHardware().supportsHaptics` returns `false`
on the Simulator. Test on a real iPhone 8 or later.

### 16.4 `UIImpactFeedbackGenerator` thread

Must be called on the main thread. The Scheduler fires `playBatch` via
`await MainActor.run { ... }`, which satisfies this requirement.

### 16.5 `t1ClientNs` capture timing

Capture the receive timestamp in the `NWConnection` receive callback
**before** any `await` or `@MainActor` jump:

```swift
// CORRECT — on receive queue, before dispatch:
let t1 = nanoTime()
Task { @MainActor in onTimeResp?(t0, tServer, t1) }

// WRONG — t1 captured after MainActor dispatch:
Task { @MainActor in
    let t1 = nanoTime()   // includes queue latency
    onTimeResp?(t0, tServer, t1)
}
```

### 16.6 `onServiceLost` — do not tear down TCP

When `NWBrowser` removes a result (mDNS cache eviction), do not close the
TCP connection — it may be perfectly healthy. Only act on `NWConnection`
state `.failed` or `.cancelled`.

### 16.7 Required Info.plist entries

Without both keys, `NWBrowser` silently fails to find any services:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>HapticActuator connects to your laptop over Wi-Fi to sync haptic feedback.</string>
<key>NSBonjourServices</key>
<array>
    <string>_haptics._tcp</string>
</array>
```

### 16.8 Minimum target

iOS 16. Core Haptics is reliable from iPhone 8+ (iOS 13+); `NWBrowser` is
stable from iOS 14+; iOS 16 gives margin and full Swift concurrency support.

---

## 17. Build order

1. **Haptic engine.** App launches, tap a button, feel `CHHapticEngine`
   fire on real device. Verify all three sharpness values feel different.
2. **Info.plist + local network permission.** Add both required keys. Verify
   the permission prompt appears on first launch.
3. **Bonjour discovery.** `NWBrowser` finds `_haptics._tcp`, logs the endpoint.
4. **TCP control channel.** Connect, send `hello`, receive and log `timeline`
   and `play` messages.
5. **Timeline + scheduler.** Parse events, schedule relative to `now`. Feel a
   real timeline before any clock sync.
6. **Clock sync.** 8-round `time_req/resp`. Verify `clockOffsetMs` < 5 ms on
   a quiet local network.
7. **UDP sync channel.** `NWListener`, re-anchor `MediaClock` on each `sync`.
8. **Play / pause / seek / rate.** Cancel and restart scheduler on each.
9. **Reconnection.** Exponential backoff + `NWBrowser` fallback.
10. **UI polish.** Sliders, `UserDefaults`, status card colors, screen-idle disable.

---

## 18. Quick reference

| Item | Value |
|---|---|
| Xcode template | iOS → App |
| mDNS service type | `_haptics._tcp` (NWBrowser); add to `NSBonjourServices` |
| TCP port | from NWBrowser endpoint (default 47821) |
| TCP frame | 4-byte big-endian length + UTF-8 JSON |
| UDP | NWListener on random port; report in `hello.udp_port` |
| Client identifier | `"client": "ios-haptic"` |
| Monotonic clock | `clock_gettime(CLOCK_MONOTONIC)` → nanoseconds |
| Haptic tier 1 | `CHHapticEngine` + `CHHapticEvent(.hapticTransient)` |
| Haptic tier 2 | `UIImpactFeedbackGenerator` (main thread only) |
| `vision_type` → sharpness | `"strike"` → 0.9 · `"bounce"` → 0.2 · `nil` → 0.5 |
| Estimated transient duration | ~100 ms (`CHHapticTransient`) |
| Stale threshold | 300 ms |
| Batch window | 200 ms |
| Max sleep chunk | 200 ms |
| Clock sync rounds | 8, 20 ms apart, 2 s timeout |
| Reconnect backoff | 1 s → 2 s → 4 s → … → 30 s cap |
| Strength slider range | 0.5× – 1.5× |
| Min intensity default | 0.15 |
| Required Info.plist keys | `NSLocalNetworkUsageDescription`, `NSBonjourServices` |
| Minimum OS | iOS 16 |
| Test device | iPhone 8 or later, NOT Simulator |

---

**End of iOS architecture document.**

For all wire-level questions, `HAPTIC_PROTOCOL.md` is authoritative. This
document only covers the iOS platform layer on top of it.
