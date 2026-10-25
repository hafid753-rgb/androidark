# Handoff — next steps

State as of 2026-10-25. Read [HOW-IT-WAS-BUILT.md](HOW-IT-WAS-BUILT.md) first
for context on decisions; this file is what to do next.

## Where it stands

| | |
|---|---|
| Compiles | Yes — `./gradlew assembleDebug` is green |
| APK | 16.72 MB, debug-signed, installable |
| Run on a device or emulator | **No. Never once.** |
| Tested against real Home Assistant | **No** |
| Protocol verified against a mock | Yes, at the wire level only |

Treat this as a **code-complete but unproven** build.

---

## P0 — Run it

Nothing else matters until this is done. Every runtime path is unverified.

### Fastest route: a real phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` is at `C:\Users\lenovo\Android\Sdk\platform-tools\adb.exe`. Enable USB
debugging on the phone first. Or just copy the APK over and sideload it, which
needs "install unknown apps" for whatever app opens the file.

Then: launch, enter the HA URL and a long-lived token
(*Profile → Security → Long-Lived Access Tokens*), and work through all four
surfaces — entity list, toggles, Cameras tab, Climate sheet.

### Emulator route — be warned

This was attempted and **failed**. The emulator package installs fine, but
`android sdk install system-images/android-35/google_apis/x86_64` hung twice:
live HTTPS connection, ~14s CPU over hours, zero bytes written. If you retry,
**download the image zip directly** instead of trusting the CLI:

- Parse `https://dl.google.com/android/repository/repository2-1.xml` for the
  `system-images` package URL, fetch with `curl`, unzip into
  `$SDK/system-images/android-35/google_apis/x86_64/`, and keep the
  `source.properties` that ships inside it.
- Then `avdmanager create avd -n ark -k "system-images;android-35;google_apis;x86_64"`
  (note: `avdmanager` still uses the **old `;` syntax**, unlike the new
  `android` CLI).
- Host machine is reachable from the emulator at **`10.0.2.2`**, so the mock
  server is `http://10.0.2.2:8123`.

### Test without the real box

```bash
java tools/mock-ha/MockHa.java 8123     # 13 entities, 6 rooms, live diffs
java tools/mock-ha/WsProbe.java         # protocol self-check
```

The mock accepts **any non-empty token**. Good for exercising the UI before
pointing at production.

## P1 — Likely first bugs

Specific places to look when it misbehaves, rather than general suspicion:

1. **`subscribe_entities` subscription id.** `HaClient.afterAuth()` assumes the
   subscription's id matches `entitiesSubId`, captured from `nextId.get()`
   immediately before the command increments it. It is correct today only
   because this is the first command after auth. If a command is ever sent
   earlier, entity events will be routed to `handleEvent` instead of
   `handleEntitiesDelta` and **the list will silently stay empty**. Worth making
   robust.
2. **`command()`'s connected check.** It special-cases `subscribe_entities` to
   get past the status gate. Fragile if the auth sequence changes.
3. **Room grouping on a non-admin account.** `config/*_registry/list` is
   admin-only on many setups. The fallback is one flat `Other` section — if you
   see that, it's the registries being denied, not a parsing bug. Check logcat
   for `ArkViewModel: Area registry unavailable`.
4. **Camera snapshots.** Uses `/api/camera_proxy/<entity_id>` with a bearer
   token. Some camera integrations return nothing without a signed path. The
   card keeps the last good frame and shows "No frame available" if it never
   gets one.
5. **Climate local echo.** The target-temperature stepper echoes locally and
   re-syncs from the server via `LaunchedEffect(serverTarget)`. If a server is
   slow to confirm, expect a visible snap-back. Deliberate, but may want
   debouncing — it currently fires a `set_temperature` call **per tap**.
6. **`binary_sensor`/`sensor` noise.** `VISIBLE_DOMAINS` includes both, so a
   large install will produce a very long list. Consider filtering by
   `entity_category` (config/diagnostic entities should probably be hidden).

## P2 — Features deliberately left out

From the iOS app, roughly in ascending order of effort:

| Feature | Notes |
|---|---|
| Scenes & automations | Easy. `scene.turn_on`, `automation.toggle`. Chips row. |
| Covers & locks | Rows render today but only toggle; no position/open-close-stop. |
| Media player controls | Shows state only; no transport controls. |
| Energy & people screens | Need `history/history_during_period` — already ported in spirit, see `fetchHistory` in the Swift client. |
| Notifications | iOS has a local notification engine driven by `onStateChange`; the Kotlin client exposes the same hook but nothing consumes it. |
| OAuth login | Currently long-lived token only. iOS has `HaOAuth.swift` plus a token refresher hook — the Kotlin `HaClient` has no `tokenRefresher` equivalent. |
| Activity feed | Replays an app-level helper entity's history. |
| Automation builder | Needs `validate_config` + the REST config endpoints. |
| 3D floor plan | iOS embeds `ARK.glb` + a WebView card. Would need a WebView or Filament/SceneView. |
| Voice ("Hey Ark") | On-device phrase matcher + HA `conversation/process` fallback. |
| Frigate / HLS streaming | Live video, not snapshots. ExoPlayer with an OkHttp data source. |
| CarPlay / watch / widgets | Android Auto, Wear OS, Glance equivalents — each a project. |
| **Embedded Tailscale** | See below. |

### Embedded Tailscale — assessed, not started

The iOS app vendors `TailscaleKit.xcframework` (Go `libtailscale` compiled
in-process) and drives it from `TailscaleTransport.swift` (411 lines) and
`TailscaleHLSProxy.swift` (278 lines). **That xcframework is Mach-O for
`ios-arm64` and the iOS simulator — unusable on Android.**

Android path, if wanted:

- Compile `libtailscale` for `arm64-v8a`/`x86_64` via
  `go build -buildmode=c-shared` + JNI glue, or `gomobile bind` for an AAR.
  Go, gomobile and the NDK are **not installed**; `gomobile bind` is awkward on
  Windows and usually easier under WSL.
- `tailscale_loopback` returns a **local SOCKS5 address plus credentials** —
  exactly the `loopbackAddress` the Swift code tracks. OkHttp consumes it
  directly: `.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))`.
- **Refactor needed:** `HaClient` builds its own `OkHttpClient` today. It must
  accept an injected one for this to work. Camera snapshots then route through
  the tunnel for free.

Real costs:

- **Most of those 411 Swift lines are a recovery ladder** (probe → engine
  bounce → full recreate, with a `generation` counter invalidating live
  sessions), which exists because iOS freezes the embedded node on suspend.
  Android has *different* problems — Doze, background network limits, process
  death — so that's a rewrite against new constraints, not a port.
- **MagicDNS gotcha:** OkHttp resolves DNS locally for SOCKS proxies, so
  `*.ts.net` names will not resolve. The Swift code keeps `tailnetIP` around,
  suggesting it dials by IP. Do the same, or supply a custom `Dns`.
- **Size:** the Go binary is **22.1 MB for the iOS arm64 slice alone**. Against
  a 16.7 MB APK, a universal build with two ABIs lands near 60 MB; ABI splits
  keep it ~40 MB per device. Plus a userspace WireGuard node in-process, with
  the battery cost that implies.

Estimate: **2–4 days** for something trustworthy. Routing traffic through the
tunnel is an afternoon; the toolchain and lifecycle work is the rest.

**Consider skipping it.** Install the official Tailscale Android app and this
build already reaches the box over the tailnet IP with zero code changes.
Embedding earns its keep on iOS because the separate app is awkward there; on
Android that friction is much lower.

## P3 — Housekeeping

- **No tests.** Not one. The pure functions are the obvious starting point:
  `HaEntity.fromCompact`, `HaEntity.applyDiff`, `HaClient.normalizeBaseUrl`.
  The iOS project has `ArkHomeTests/` and keeps its parsing helpers internal
  specifically so they can be unit-tested — mirror that.
- **Release signing.** Debug-signed only. A release build needs a keystore and
  a `signingConfigs` block; `isMinifyEnabled` is currently `false` for release,
  and `proguard-rules.pro` has only OkHttp's `-dontwarn` lines.
- **No CI.** A GitHub Actions workflow running `assembleDebug` would catch
  compile regressions for free.
- **Hardcoded English.** The iOS app preserves en/fr/ar. Strings here are
  inline in the composables, not in `strings.xml`.
- **`local.properties` is gitignored** (correct) — anyone cloning needs their
  own `sdk.dir`.
- **Launcher icon** is a hand-written vector house glyph, not the iOS app's
  `icon.png`. Replace if brand consistency matters.
