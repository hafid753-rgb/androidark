# How this was built

A record of what was done, in order, and why — written so someone picking this
up cold knows which decisions were deliberate and which were forced.

Built on 2026-10-25, on a Windows 11 Pro machine that started with **no
developer toolchain at all**: no JDK, no Android SDK, no Gradle, no Node, no
Git, no Python.

## 1. The starting point

The iOS app was already on the machine at `Downloads/arkhome-swift-main/` — a
SwiftUI rewrite of an earlier React Native app (`ark-home`), acting as a custom
Home Assistant client. Around it sat `Downloads/haos-tools/`, a large pile of
shell scripts driving the same HA box (`ark-check.sh`, `ark-status.sh`,
dashboards, TV/SmartIR/Broadlink work).

Being an Xcode project, it cannot be built on Windows — `SSH-TO-MAC.md` in that
repo indicates it was built remotely on a Mac.

The ask was a "simple version APK for Android". The iOS app is large (~100
Swift files: 3D floor plan, voice control, CarPlay, a watch app, widgets,
energy and people screens, an automation builder, Frigate and Tailscale
transports). A faithful port was never on the table; the point was a usable
subset.

## 2. Scope chosen

Agreed up front rather than discovered later:

- **Baseline** — connect screen, live entity list grouped by room, toggles.
- **Plus camera snapshots** — explicitly requested.
- **Plus climate / AC control** — explicitly requested.

Everything else was dropped on purpose. See [HANDOFF.md](HANDOFF.md) for the
full list of what is missing and what it would take to add.

## 3. Reading the iOS source first

Rather than guess at the protocol, `ArkHome/Networking/HaClient.swift` (897
lines) was read in full. It is the only file that really mattered, because it
defines the whole server conversation:

- WebSocket to `/api/websocket`, `auth_required` → `auth` → `auth_ok`
- `subscribe_entities` for **compact state diffs**, falling back to
  `subscribe_events`/`state_changed` if the server rejects it
- Command correlation by integer `id`, with per-command timeouts
- Exponential reconnect backoff, and a deliberate tolerance for transient
  `auth_invalid` (HA briefly rejects valid tokens just after a restart)
- REST side: `/api/camera_proxy/<entity_id>` with a bearer token

Those behaviours were ported deliberately, not reinvented. The Kotlin
[HaClient.kt](../app/src/main/java/com/arkhome/app/data/HaClient.kt) is a
direct descendant, including the 3-attempt `auth_invalid` tolerance and the
`2, 4, 8, 15, 15…s` backoff curve.

## 4. Bootstrapping the toolchain

From nothing, in this order:

1. **Microsoft OpenJDK 17** via `winget` (17.0.20.1).
2. **Android command-line tools 23.0** — downloaded directly, because `winget`
   only carries platform-tools. The current filename was found by parsing
   `https://dl.google.com/android/repository/repository2-1.xml` rather than
   guessing a URL.
3. **SDK packages** — platform-tools 37.0.1, `platforms/android-35`,
   `build-tools/35.0.0`.
4. **Gradle 8.9** — downloaded as a binary zip, then used once to generate the
   project's own wrapper, so the repo is self-contained afterwards.

Two things cost time and are worth knowing:

- `sdkmanager` is **deprecated and replaced by a new `android` CLI**, which
  uses `/` as the package separator, not `;`. `platforms;android-35` fails with
  "Package platforms not found"; `platforms/android-35` works.
- Running **two `android sdk install` processes against the same SDK root
  deadlocks them both**. They sit with an open HTTPS connection, burn almost no
  CPU, and download nothing — indefinitely. Always install one package at a
  time, and verify bytes landed on disk rather than trusting the exit code.

See [TOOLCHAIN.md](TOOLCHAIN.md) for exact versions and commands.

## 5. Version matrix

Picked conservatively, for combinations known to resolve together rather than
newest-available:

| | |
|---|---|
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| Gradle | 8.9 |
| JDK | 17 |

Newer platforms (android-37.x) and build-tools (37.0.0) were available but
would have meant a newer AGP and more risk of an unresolvable combination.

## 6. Dependencies, and what was avoided

Kept deliberately thin:

- Compose (UI, Material 3, extended icons), activity-compose, lifecycle
- **OkHttp 4.12.0** — WebSocket and REST
- **androidx.security:security-crypto 1.0.0** — Keystore-backed token storage

Avoided on purpose:

- **No JSON library.** Android ships `org.json`, which is enough for HA's
  open-ended attribute bags. This also keeps the entity model honest:
  attributes stay raw JSON because every integration contributes different keys.
- **No image loader (Coil/Glide).** Camera frames need an authenticated
  request, so they are fetched with the same OkHttp client and decoded with
  `BitmapFactory`. One less dependency, and no auth-header plumbing into a
  loader.

## 7. Architecture

| Layer | File | Notes |
|---|---|---|
| Networking | `data/HaClient.kt` | WebSocket lifecycle, command correlation, entity diffs, reconnect, camera proxy |
| Model | `data/HaEntity.kt` | Entity plus the compact/diff parsing from `subscribe_entities` |
| Persistence | `data/Prefs.kt` | URL in prefs; token in `EncryptedSharedPreferences` |
| State | `ui/ArkViewModel.kt` | Client lifecycle, room resolution, service calls |
| UI | `ui/*.kt` | Setup, room list, camera cards, climate sheet |

Two details worth preserving:

**Room resolution** mirrors Home Assistant's own precedence: an entity's own
`area_id` from the entity registry wins; otherwise it inherits the area of its
device via the device registry. If the registries can't be read — a non-admin
household account isn't allowed to — it logs and degrades to one flat list
rather than failing to load.

**Token storage** uses `EncryptedSharedPreferences`, the Android counterpart of
the iOS app keeping its token in the Keychain (itself an upgrade over the React
Native version's plain AsyncStorage). If the Keystore entry is broken or
rotated, it falls back to plain prefs so the app isn't bricked — the user can
at least re-enter the token.

## 8. Bugs hit along the way

Recorded because they're the kind of thing that recurs:

1. **`security-crypto` 1.0.0 has no `MasterKey`.** That class arrived in
   1.1.0-alpha. The stable release uses `MasterKeys.getOrCreate(...)` returning
   a String alias, with a different `EncryptedSharedPreferences.create(...)`
   argument order. Fixed by using the 1.0.0 API rather than taking an alpha
   dependency.
2. **Non-local `continue` inside inline lambdas.** The first `ArkViewModel`
   draft used `.ifBlank { continue }` inside `buildMap`. Kotlin rejects that.
   Rewritten with explicit null checks and a `forEachObject` helper.
3. **Hand-written Compose shims that didn't exist.** The first `HomeScreen`
   draft invented a `collectAsStateLifecycle` wrapper and a circular
   `Modifier.background` extension. Replaced with the real
   `androidx.compose.runtime.collectAsState` and the actual `background` import.
4. **Missing experimental opt-ins.** `FlowRow` needs
   `@OptIn(ExperimentalLayoutApi::class)`; clickable `Card` needs
   `ExperimentalMaterial3Api`.
5. **Tangled flow wiring.** The first ViewModel nested `collect` calls inside
   `combine` with a dummy flow, and leaked collectors when the client was
   replaced. Rewritten with `map`/`stateIn` and an explicit job list cancelled
   in `stopClient()`.

Only #1 was caught by the compiler on the first build; #2–#5 were fixed before
building, by reading back what had been written.

## 9. Verification — and its limits

**A mock Home Assistant was built** so the client could be exercised without
touching the real box: [tools/mock-ha/MockHa.java](../tools/mock-ha/MockHa.java).
Hand-rolled RFC 6455 framing and string-built JSON, because it has to run on a
bare JDK with no dependencies. It serves 13 fake entities across 6 rooms, both
registries, working `toggle` / `set_hvac_mode` / `set_temperature` /
`set_fan_mode`, and a freshly rendered PNG per snapshot request with a clock
drawn on it so the 5-second refresh is visibly working.

`tools/mock-ha/WsProbe.java` drives it the way the app does, using the JDK's
own `java.net.http.WebSocket`. It passes: auth handshake, compact entity
snapshot, area and entity registries, a toggle diff, and a `set_temperature`
attribute diff all come back in the expected shapes.

**What that does and does not prove.** It confirms the wire protocol the Kotlin
client was written against is correct, and that the mock is a faithful stand-in.
It does **not** exercise a single line of the Kotlin or Compose code.

### Running it — eventually on real hardware

The build finished well before it could be run, and for a while it shipped
entirely unproven:

- `./gradlew assembleDebug` succeeded; the APK was real and debug-signed.
- `adb devices` found no attached device.
- An emulator was attempted: the **emulator package installed fine (1.0 GB)**,
  but the `android-35 google_apis x86_64` **system image download hung twice**
  via the `android` CLI — a live HTTPS connection, ~14s of CPU over hours, and
  zero bytes on disk. It was abandoned, not completed.

The repo was published in that state, with the caveat stated plainly.

**It was then sideloaded onto a physical phone and run against the live Home
Assistant server, and reported working (2026-10-25).** So the port holds up:
the WebSocket auth handshake, `subscribe_entities` diffs, room resolution,
service calls and camera proxy all behave against real Home Assistant, not
just against the mock.

What that still doesn't give us: a **per-feature record**. "It is good" is an
overall judgement from real use, not a surface-by-surface sign-off, and there
are no automated tests to pin the behaviour down. Nobody has written down
whether, say, a non-admin account degrades to the flat `Other` list correctly,
or how the climate stepper behaves against a slow thermostat. Those remain
open questions rather than known failures — see
[HANDOFF.md](HANDOFF.md) for the list.

## 10. What shipped

- `app-debug.apk` — 16.72 MB, `com.arkhome.app`, label "ARK HOME", minSdk 26,
  targetSdk 35, launchable `MainActivity`, debug-signed.

Next steps are in [HANDOFF.md](HANDOFF.md).
