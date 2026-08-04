# Main Screen — Design & Implementation Record

## What was built

The default screen of the app. Shows the app icon, name, live connection status, the University logo, and two navigation buttons. It is the root state — shown whenever both `showSettings` and `showAbout` are false.

---

## Layout structure

A full-screen `Box` with two independent layers:

1. **Content column** — fills the entire box, centred horizontally, 32dp horizontal padding. Split vertically into a weighted centre region and a pinned bottom.
2. **Icon button row** — overlaid at `Alignment.TopEnd`, 4dp padding from the corner. Sits on top of the content column without consuming layout space.

```
┌─────────────────────────────┐
│                        ℹ ⚙  │  ← overlaid Row, TopEnd
│                             │
│                             │
│         [icon 160dp]        │  ┐
│         FeeltheSports        │  │ weight(1f) Box, centred
│    ╭─ ● Searching… ─╮       │  ┘
│                             │
│   [University of Waikato]   │  ← pinned bottom, 56dp tall
│                             │  ← 24dp spacer
└─────────────────────────────┘
```

**Why `Box` with an overlaid Row rather than a `TopAppBar`?**
The screen has no title and the icon buttons don't need a full app bar chrome. Overlaying a bare `Row` keeps the centre region truly vertically centred in the full screen height, not in the height below an app bar.

---

## App icon

```kotlin
Image(
    painter            = painterResource(R.drawable.haptic_icon),
    contentDescription = "FeeltheSports Logo",
    modifier           = Modifier.size(160.dp),
)
```

Asset: `haptic_icon.png` — full-bleed artwork with rounded corners baked into the image. **Do not use `haptic_icon_padded.png` here** — that variant is for the launcher and splash screen (system contexts that apply their own mask). Applying the padded asset on the main screen results in a visibly shrunken icon surrounded by navy dead space.

| Asset | Use |
|---|---|
| `haptic_icon.png` | Main screen `Image` composable |
| `haptic_icon_padded.png` | Adaptive icon foreground, all mipmap density PNGs, splash screen |

Spacing below the icon: `Spacer(16.dp)` before the app name.

---

## App name

```kotlin
Text(
    text       = "FeeltheSports",
    style      = MaterialTheme.typography.headlineSmall,
    fontWeight = FontWeight.SemiBold,
)
```

`Spacer(40.dp)` between the name and the status pill.

---

## Connection status pill

A `Surface` with `RoundedCornerShape(50)` (fully rounded capsule) and `surfaceVariant` background. Inside: a `Row` with an 8dp circle dot and a `bodyMedium` text label.

```kotlin
Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
    Row(
        modifier              = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
    }
}
```

### States

| `ConnectionStatus` | Dot colour | Hex | Text |
|---|---|---|---|
| `Searching` | Grey | `#9E9E9E` | Searching for Haptic Server… |
| `Connecting(name)` | Orange | `#FF9800` | Connecting to \<name\>… |
| `Reconnecting(name)` | Orange | `#FF9800` | Reconnecting to \<name\>… |
| `Connected(name)` | Green | `#4CAF50` | Connected to \<name\> |

Connecting and Reconnecting share the same orange dot — both mean "not yet stable".

---

## University logo

```kotlin
Image(
    painter      = painterResource(R.drawable.university_of_waikato_logo),
    contentScale = ContentScale.Fit,
    modifier     = Modifier.fillMaxWidth().height(56.dp),
)
Spacer(Modifier.height(24.dp))
```

Pinned to the bottom of the content column (outside the weighted centre box), so it stays at the bottom of the screen regardless of content height.

---

## Navigation buttons

```kotlin
Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
    IconButton(onClick = onOpenAbout)    { Icon(Icons.Filled.Info,     "About") }
    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
}
```

Info (ℹ) is left of Settings (⚙). Both set their respective Boolean flags on `MainActivity`.

---

## Exit confirmation dialog

`BackHandler` inside `MainScreen` intercepts the system back gesture and sets a local `showExitDialog` state rather than finishing the activity immediately.

```kotlin
var showExitDialog by remember { mutableStateOf(false) }
BackHandler { showExitDialog = true }
```

Dialog copy:

- **Title**: "Exit"
- **Message**: "Are you sure you want to exit FeeltheSports?"
- **Confirm**: "Exit" → calls `onExit` (which calls `finish()` on the activity)
- **Dismiss**: "Cancel" → `showExitDialog = false`
- `onDismissRequest` (tap outside): same as Cancel

---

## Keep screen on

A side-effect composable is invoked at the `HapticActuatorTheme` level, above all screens:

```kotlin
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
```

This keeps the display on for the lifetime of the composable tree — the screen never dims while the app is in the foreground.

---

## iOS equivalents

| Android | iOS |
|---|---|
| `Box` with overlaid `Row` at `TopEnd` | `ZStack` with a `.topTrailing`-aligned `HStack` |
| `RoundedCornerShape(50)` capsule | `.clipShape(Capsule())` |
| `CircleShape` 8dp dot | `Circle().frame(width: 8, height: 8)` |
| `BackHandler { showExitDialog = true }` | `.confirmationDialog` on swipe-dismiss / custom Back button |
| `view.keepScreenOn = true` | `UIApplication.shared.isIdleTimerDisabled = true` in `onAppear` |
| `R.drawable.haptic_icon` | `Image("haptic_icon")` from the asset catalogue — no system clipping |
| `ContentScale.Fit` on logo | `.scaledToFit()` |
| `surfaceVariant` pill background | `.background(.secondary.opacity(0.15))` or a `Color(UIColor.secondarySystemFill)` |
