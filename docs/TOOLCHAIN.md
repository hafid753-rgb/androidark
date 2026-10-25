# Toolchain — everything installed and used

Exact versions, install commands and the gotchas, so this is reproducible on a
clean Windows machine. The machine this was built on started with **none** of
it: no JDK, no Android SDK, no Gradle, no Node, no Git, no Python.

Host: Windows 11 Pro 10.0.26200, PowerShell 5.1.

## Installed

| Tool | Version | How |
|---|---|---|
| Microsoft OpenJDK | 17.0.20.1 LTS | `winget install Microsoft.OpenJDK.17` |
| Android cmdline-tools | 23.0 | direct zip download (see below) |
| Android platform-tools | 37.0.1 | `android sdk install platform-tools` |
| Android platform | android-35 | `android sdk install platforms/android-35` |
| Android build-tools | 35.0.0 (+34.0.0, pulled by AGP) | `android sdk install build-tools/35.0.0` |
| Android emulator | 37.2.12 | `android sdk install emulator` |
| Gradle | 8.9 | direct zip, then `gradle wrapper` |
| Git | latest | `winget install Git.Git` |

Paths:

```
JAVA_HOME    C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
ANDROID_HOME C:\Users\lenovo\Android\Sdk
Gradle       C:\Users\lenovo\Android\gradle-8.9
```

## Not installed (and not needed for this build)

Node, npm, Python, Go, gomobile, Android NDK, Android Studio. Noted because
`gh` is also absent — see [HANDOFF.md](HANDOFF.md) and the Tailscale section
for what the missing Go/NDK toolchain would block.

## Build versions

| | |
|---|---|
| compileSdk / targetSdk | 35 |
| minSdk | 26 (Android 8.0) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| Gradle | 8.9 |
| JDK target | 17 |

Chosen for a combination known to resolve together, not for newest-available.
android-37.x platforms and build-tools 37.0.0 existed but would have forced a
newer AGP.

## Dependencies

```kotlin
androidx.core:core-ktx:1.13.1
androidx.activity:activity-compose:1.9.3
androidx.lifecycle:lifecycle-runtime-ktx:2.8.6
androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6
androidx.security:security-crypto:1.0.0
com.squareup.okhttp3:okhttp:4.12.0
androidx.compose:compose-bom:2024.10.01   // ui, ui-graphics, material3, material-icons-extended
```

Deliberately **not** used: any JSON library (Android's `org.json` suffices and
suits HA's open-ended attributes), and any image loader (camera frames need an
authenticated request, so OkHttp + `BitmapFactory` is simpler than plumbing
auth headers into Coil).

## Gotchas that cost real time

### 1. `sdkmanager` is deprecated — the new `android` CLI uses `/`, not `;`

```bash
# fails: "Package platforms not found" / "Package android-35 not found"
sdkmanager "platforms;android-35" "build-tools;35.0.0"

# works
android sdk install platforms/android-35
android sdk install build-tools/35.0.0
```

One package per invocation; the syntax is `<package>[@<version>]`.

`avdmanager` was **not** migrated and still wants the old `;` form:

```bash
avdmanager create avd -n ark -k "system-images;android-35;google_apis;x86_64"
```

### 2. Concurrent installs into one SDK root deadlock

Two `android sdk install` processes against the same `ANDROID_HOME` hang each
other **indefinitely** — open HTTPS connection, near-zero CPU, nothing written
to disk. There is no error and the exit code can still look like success if the
process is killed.

**Install one package at a time, and verify bytes on disk** rather than
trusting the exit code:

```powershell
Test-Path "$env:ANDROID_HOME\platforms\android-35\android.jar"
```

This burned the android-35 system image twice; see the emulator note in
[HANDOFF.md](HANDOFF.md).

### 3. Finding the current cmdline-tools URL

Don't guess the build number. Parse the repository manifest:

```powershell
$r = Invoke-WebRequest "https://dl.google.com/android/repository/repository2-1.xml" -UseBasicParsing
$x = [xml]$r.Content
$x.GetElementsByTagName("remotePackage") |
  Where-Object { $_.path -like "cmdline-tools*" } |
  ForEach-Object { "$($_.path) -> $(($_.GetElementsByTagName('url') | % { $_.InnerText }) -join ', ')" }
```

At time of writing: `cmdline-tools;latest` → `commandlinetools-win-16111833_latest.zip`
(~148 MB). Unzip so that `$SDK/cmdline-tools/latest/bin/sdkmanager.bat` exists —
the zip's root folder is `cmdline-tools`, so it must be **moved into place**,
not extracted directly.

### 4. `Invoke-WebRequest` times out on large files

It died on a 148 MB download. Use `curl.exe` (ships with Windows) with resume:

```powershell
curl.exe -L --fail --retry 3 -C - -o out.zip "<url>"
```

### 5. PowerShell 5.1 specifics

- No `&&` / `||` — use `;` and `if ($?) { }`
- Avoid `2>&1` on native executables: it wraps stderr lines in `ErrorRecord`
  and sets `$?` to false even on exit code 0
- `$x.sdk-repository` is a parse error (`-` is an operator) — use
  `GetElementsByTagName`

### 6. Java single-file launch needs a plain path

`java C:\long\path\with-dashes\File.java` failed with "Could not find or load
main class". Run it from the containing directory with a relative path, or pass
`--source 17`.

## Commands

```bash
# build
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk

# install to a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# inspect the APK
"$ANDROID_HOME/build-tools/35.0.0/aapt.exe" dump badging app-debug.apk

# mock Home Assistant (no dependencies, bare JDK)
java tools/mock-ha/MockHa.java 8123
java tools/mock-ha/WsProbe.java ws://127.0.0.1:8123/api/websocket
```

Gradle needs `JAVA_HOME` and `ANDROID_HOME` set, and `local.properties` to
carry `sdk.dir` (gitignored — set your own on clone):

```properties
sdk.dir=C\:\\Users\\lenovo\\Android\\Sdk
```

## Test harness

Both files are dependency-free and run on a bare JDK 17:

- **`tools/mock-ha/MockHa.java`** — a Home Assistant stand-in. Hand-rolled
  RFC 6455 framing (HTTP upgrade, SHA-1 `Sec-WebSocket-Accept`, masked client
  frames, ping/pong) and string-built JSON. Serves 13 entities across 6 rooms,
  area and entity registries, working `toggle` / `set_hvac_mode` /
  `set_temperature` / `set_fan_mode` that push real `subscribe_entities` diffs
  back, and `/api/camera_proxy/<id>` returning a freshly rendered 640×360 PNG
  with a clock on it so snapshot refreshes are visibly working. Accepts any
  non-empty token.
- **`tools/mock-ha/WsProbe.java`** — drives the mock the way the app does,
  using the JDK's own `java.net.http.WebSocket`. Strictly step-driven so an
  unrelated push can't re-trigger an earlier request. Prints
  `OK: toggle and set_temperature both echoed back as diffs` on success.

From an emulator, the host is **`10.0.2.2`**, so point the app at
`http://10.0.2.2:8123`.
