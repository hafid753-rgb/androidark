# ARK HOME (Android, simple build)

A stripped-down Android client for the same Home Assistant server the iOS app
([`arkhome-swift`](../arkhome-swift-main)) talks to. Jetpack Compose, no
React Native, no Xcode.

## What it does

- **Connect** — server URL + long-lived access token. The token is held in
  `EncryptedSharedPreferences` (Keystore-backed), the Android counterpart of the
  iOS app's Keychain storage.
- **Home** — every light, switch, fan, cover, lock, media player and sensor,
  grouped by the rooms defined on the box. Tap or flip a switch to toggle.
- **Cameras** — one card per camera entity, snapshot refreshed every 5 s. The
  last good frame stays on screen while the next loads, so cards don't flicker.
- **Climate** — a control sheet per thermostat/AC: HVAC mode, target
  temperature stepper, fan mode.

Live updates come over the Home Assistant WebSocket API via
`subscribe_entities` (compact diffs), with automatic reconnect and exponential
backoff — a direct port of `HaClient.swift`.

## What it deliberately leaves out

Compared to the iOS app: the 3D floor plan, Frigate/Tailscale transports, voice
("Hey Ark"), CarPlay, watch app, widgets, energy and people screens, automation
builder, activity feed, OAuth login (long-lived token only), and notifications.

## Requirements

- JDK 17
- Android SDK with platform 35 and build-tools 35.0.0
- `minSdk` 26 (Android 8.0), `targetSdk` 35

`local.properties` points at the SDK and is machine-specific — not checked in.

## Build

```
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`, signed with the
local debug key. To install over USB:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sideloading it instead requires "install unknown apps" for whatever app opens
the file.

## First run

1. Home Assistant → *Profile → Security → Long-Lived Access Tokens* → create one.
2. Launch ARK HOME, enter the server URL (e.g. `http://192.168.1.10:8123`) and
   paste the token.

Plain HTTP is allowed via `network_security_config.xml`, because Home Assistant
is normally reached over a LAN or Tailscale address — the same trade-off the iOS
app makes with `NSAppTransportSecurity`.

## Layout

| Path | What |
| --- | --- |
| `data/HaClient.kt` | WebSocket API: auth, command correlation, entity diffs, reconnect, camera proxy |
| `data/HaEntity.kt` | Entity model plus the compact/diff parsing from `subscribe_entities` |
| `data/Prefs.kt` | URL in prefs, token in Keystore-backed encrypted prefs |
| `ui/ArkViewModel.kt` | App state, room resolution (entity → device → area), service calls |
| `ui/SetupScreen.kt` | First-run connect form |
| `ui/HomeScreen.kt` | Tabs, room list, entity rows, connection chip |
| `ui/CameraCard.kt` | Camera cards with the 5 s snapshot loop |
| `ui/ClimateSheet.kt` | Climate list and the control sheet |

## Testing without a real server

A dependency-free Home Assistant stand-in ships in [tools/mock-ha](tools/mock-ha),
runnable on a bare JDK:

```
java tools/mock-ha/MockHa.java 8123
java tools/mock-ha/WsProbe.java
```

13 fake entities across 6 rooms, live `subscribe_entities` diffs, working
climate services and generated camera frames. It accepts any non-empty token.
From an emulator the host is `10.0.2.2`, so point the app at
`http://10.0.2.2:8123`.

## Documentation

| Doc | What |
| --- | --- |
| [docs/HOW-IT-WAS-BUILT.md](docs/HOW-IT-WAS-BUILT.md) | The full build story — decisions, the iOS source it was ported from, bugs hit, what was verified |
| [docs/HANDOFF.md](docs/HANDOFF.md) | Next steps, likely first bugs, features left out, the embedded-Tailscale assessment |
| [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md) | Every tool and version installed, exact commands, and the gotchas that cost time |

## Status

**Working.** Built, sideloaded onto a physical Android phone and run against
the live Home Assistant server — reported good by the owner on 2026-10-25.

Caveats worth keeping in mind:

- There is **no per-feature test log**. "Works" is an overall judgement from
  real use, not a surface-by-surface sign-off, and no automated tests exist
  (see [docs/HANDOFF.md](docs/HANDOFF.md)).
- It was never run on an emulator; the system-image download failed repeatedly.
  Everything known about its behaviour comes from one real phone against one
  real server.
- The wire protocol is additionally verified against the mock server above.
