# About Screen — Design & Implementation Record

## What was built

A scrollable informational screen showing app identity, credits, build metadata, and the University of Waikato logo. Accessible via the info icon (ℹ) in the top-right corner of the Main Screen.

Like the Settings screen, it is controlled by a Boolean flag on `MainActivity`: `showAbout = true` to enter, reset to `false` on Back.

---

## Layout decisions

**University logo at the bottom, not the top**

Initial implementations placed the logo at the top of the scroll content. It was moved to the bottom (after the last `HorizontalDivider`) so the app's own identity — name, tagline, and credits — is the first thing the reader sees. The logo closes the screen as an institutional footer.

**Fixed-width label column**

`AboutRow` uses a two-column `Row`: the label is a fixed `100.dp` wide in `onSurfaceVariant` colour; the value takes the remaining width with `Modifier.weight(1f)` and `FontWeight.Medium`. This gives the screen a clean key-value register without reaching for a table composable.

```kotlin
@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = label, style = bodyMedium, color = onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(text = value, style = bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
```

Multi-line values (institution, school, secondary supervisor) are handled naturally by the value column wrapping.

---

## Content (top to bottom)

```
App name: "FeeltheSports"          headlineMedium / Bold
Tagline:  "Real-time haptic feedback for tennis match viewing"
          bodyLarge / onSurfaceVariant

─── divider ───

Developed by     Chuan Xi
Supervised by    Assoc. Prof. David Nichols
(blank label)    Dr. Jemma König

─── divider ───

Institution      University of Waikato
                 Te Whare Wānanga o Waikato
School           School of Computing and
                 Mathematical Sciences

─── divider ───

Version          1.0.0   ← read from BuildConfig.VERSION_NAME
Build date       4 Aug 2026
Contact          xichuanxc@gmail.com

─── divider ───

[University of Waikato logo — full width, 56 dp tall, aspect-fit]
[24 dp spacer]
```

---

## Version display

The version string is read dynamically so the About screen never needs touching when the version is bumped:

```kotlin
AboutRow(label = "Version", value = BuildConfig.VERSION_NAME)
```

Two requirements for this to compile:

1. `buildFeatures { buildConfig true }` must be set in `app/build.gradle` (AGP 8+ no longer generates `BuildConfig` by default).
2. **No import** for `BuildConfig` — it lives in the same package (`com.feelthesports.hapticactuator`) and is generated post-compile. Adding an explicit import causes an unresolved-reference error.

Current value: `versionName "1.0.0"`, `versionCode 1`.

---

## Entry / exit

The info icon sits to the left of the gear icon in the top-right overlay of the Main Screen:

```kotlin
IconButton(onClick = onOpenAbout) { Icon(Icons.Filled.Info, contentDescription = "About") }
```

`BackHandler` inside `AboutScreen` calls `onBack = { showAbout = false }`. The `TopAppBar` back arrow does the same.

---

## iOS equivalents

| Android | iOS |
|---|---|
| `BuildConfig.VERSION_NAME` | `Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String` |
| `HorizontalDivider()` | `Divider()` |
| `AboutRow` label column `width(100.dp)` | `frame(width: 110)` on the label `Text` in an `HStack` |
| `ContentScale.Fit` on logo image | `.scaledToFit()` |
| Logo `height(56.dp)` | `.frame(height: 56)` |
| `onSurfaceVariant` colour | `.foregroundStyle(.secondary)` |

University logo at the bottom rule applies equally on iOS — do not place it at the top of the scroll content.
