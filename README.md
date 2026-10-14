# Remote Controller

Control your Windows PC from your Android phone over local Wi-Fi — adjust volume, send media keys, and simulate keyboard input.

## Stack (latest)

| Component | Version |
|-----------|---------|
| Gradle | 9.6.1 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin | 2.4.0 |
| Compose Multiplatform (desktop + client) | 1.12.1 |
| Compose BOM (Android) | 2026.06.00 |
| Ktor | 3.5.0 |
| Koin | 4.2.2 |

## Project structure

```
remote-controller/
├── shared/          # Wire protocol (KMP: Android, iOS, JVM for the desktop server)
├── client/
│   ├── domain/      # Models, repository contracts, use cases
│   ├── data/        # Repositories, Ktor client, DataStore; Android actuals + iOS TODOs
│   └── ui/          # Compose Multiplatform screens, ViewModels, navigation
├── android/         # Android application shell (Activity, manifest)
├── iosApp/          # Xcode shell that hosts the Compose UI
└── desktop/         # Windows PC server (Compose Desktop + Ktor + Koin)
```

## Features

**Android app (controller)**
- Connect to PC via IP address on local Wi-Fi
- Volume slider, up/down, mute
- Media keys (play/pause, next, previous, stop)
- Navigation and function keys

**Windows desktop app (server)**
- Shows local IP addresses for pairing
- WebSocket server receives commands from Android
- Executes volume changes and keyboard simulation via `java.awt.Robot`

## Requirements

- JDK 21
- Android Studio (for the Android app)
- A Mac with Xcode to compile the iOS app (the iOS target is in the project; Kotlin/Native builds it only on macOS)
- Windows PC and phone on the same Wi-Fi network

## Run the PC server

```bash
./gradlew :desktop:run
```

The server window shows your PC's IP address and listens on port **8765**.

## Run the Android app

Open the project in Android Studio and run the `:android` module on a device or emulator.

1. Start the PC server first
2. Enter the IP shown on the PC app (e.g. `192.168.1.100`)
3. Tap **Connect**

## Run the iOS app

On a Mac, open `iosApp/iosApp.xcodeproj`, set your team in `iosApp/Configuration/Config.xcconfig`, and run the `iosApp` target. The Xcode build calls `:client:ui:embedAndSignAppleFrameworkForXcode` and shows the shared Compose UI.

iOS platform code is still `TODO()`: network monitor, Ktor Darwin client, DataStore path, local-network permission, and logging. The screen is shared; those actuals are the next implementation step.

## Version

`version.properties` at the repository root is the product name and version:

```
name=Remote Controller Server
version=1.0.0
versionCode=1
```

Gradle reads that file for the desktop package name, package version, artifact file names, the version reported by the running server, and the Android `versionName` / `versionCode`. `version` must be numeric (`1.2.0`) because the desktop installer is built with jpackage.

## Build desktop artifacts

One command builds every desktop artifact for the machine you are on and copies them to `artifacts/desktop`:

```bash
./gradlew packageDesktop
```

| Host | Artifacts |
|------|-----------|
| Windows | `artifacts/desktop/Remote Controller Server-1.0.0.exe` and the portable app in `artifacts/desktop/Remote Controller Server-1.0.0/` |
| macOS | `artifacts/desktop/Remote Controller Server-1.0.0.dmg` and the portable `.app` in `artifacts/desktop/Remote Controller Server-1.0.0/` |

The folder name follows `name` and `version` from `version.properties`. Packaging needs a full JDK 21 that includes `jpackage` (not the Android Studio JBR). Point Gradle at it with `desktop.jdk.home` in `local.properties` or `JAVA_HOME`.

```bash
# Android APK, versioned from the same file
./gradlew :android:assembleDebug
```

## Architecture (client)

Shared across Android and iOS:

```
:client:ui       →  Compose screens, ViewModels, type-safe navigation, Koin graph
:client:domain   →  Use cases, repository interfaces, domain models
:client:data     →  Repository implementations, Ktor session, DataStore store
:shared          →  Protocol models used by the client and the desktop server
```

Platform code lives in `androidMain` / `iosMain` of `:client:data` and `:client:ui`:

- Android: Wi-Fi `NetworkMonitor`, OkHttp engine bound to the LAN, DataStore file, local-network permission prompt
- iOS: the same `actual` declarations, currently `TODO()`

`:android` and `iosApp` only start the process and show `App()`.

### Desktop DI (Koin)

```
executor/      →  VolumeController, KeyboardSimulator, CommandExecutor
server/        →  CommandServer (Ktor WebSocket)
presentation/  →  DesktopViewModel, Compose UI
di/            →  Koin modules (executor, server, presentation)
```

### Modern Kotlin patterns used

- **Sealed interfaces** — `RemoteCommand`, `ServerMessage`, `ConnectionState` with exhaustive `when`
- **Value classes** — `PcHost`, `ServerPort`, `VolumePercent` for type-safe domain values
- **Data objects** — `RemoteCommand.MuteToggle`, navigation `ConnectRoute` / `RemoteRoute`
- **Type-safe navigation** — `@Serializable` routes with Navigation Compose 2.9+
- **Explicit backing fields (Kotlin 2.4)** — replaces `_state`/`state.asStateFlow()` with a single property:

```kotlin
val status: StateFlow<ServerStatus>
    field = MutableStateFlow(initialStatus())

// inside class: status.value = ... (MutableStateFlow)
// outside class: read-only StateFlow
```

- **Flow `combine` + `stateIn`** — ViewModels derive UI state from multiple sources
- **`@Immutable` UI state** — Compose stability for recomposition
- **`kotlin.time`** — debounced volume slider (250ms) via `Flow.debounce`
- **Shared `RemoteJson`** — single `Json` config with `inline reified` encode/decode
- **Use case `operator invoke`** — callable use cases (`ConnectToPcUseCase()`, `SendRemoteCommandUseCase()`)

## Protocol

Commands are sent as JSON over WebSocket (`ws://<ip>:8765/ws`). Each command is a sealed `RemoteCommand` subtype:

- `SetVolume`, `VolumeUp`, `VolumeDown`, `MuteToggle`
- `KeyPress`, `KeyCombo`

Server info endpoint: `http://<ip>:8765/info`
