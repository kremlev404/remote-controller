# Remote Controller

Phone client controls a PC on the local network: volume, media keys, keyboard, mouse. Protocol is JSON over WebSocket `ws://<ip>:8765/ws`, info at `http://<ip>:8765/info`.

## Stack

| | |
|---|---|
| JDK | 21 |
| Gradle | 9.8.1 |
| AGP | 9.4.1 |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.1 |
| Ktor | 3.6.0 |
| Koin | 4.2.2 |
| Coroutines | 1.11.0 |
| kotlinx.serialization | 1.11.0 |
| Navigation (KMP) | 2.10.0-beta01 |
| DataStore | 1.2.1 |
| JNA | 5.19.1 |

Android `minSdk` 26, `compileSdk` / `targetSdk` 37. Versions live in `gradle/libs.versions.toml`.

## Modules

| Module | What | Targets |
|---|---|---|
| `:shared` | Wire protocol (`RemoteCommand`, messages) | Android, iOS, JVM |
| `:client:domain` | Models, repository contracts, use cases | Android, iOS |
| `:client:data` | Ktor client, DataStore, platform actuals | Android (OkHttp), iOS (Darwin) |
| `:client:ui` | Compose UI, ViewModels, navigation. iOS binary is a static framework `ClientUI` | Android, iOS |
| `:android` | Android app shell. Shows `App()` | Android |
| `iosApp` | Xcode shell. Not a Gradle module | iOS |
| `:desktop` | PC server: Compose Desktop UI, Ktor, volume and input | JVM, Windows and macOS |

iOS Kotlin targets are `iosArm64` and `iosSimulatorArm64`.

`:desktop` input and volume: Windows via JNA, macOS volume via `libmacvolume.dylib` (Core Audio). Gradle builds that library with CMake (`:desktop:compileMacVolume`).

`version.properties` sets the product name, `version`, and `versionCode`. Gradle uses it for the desktop package name, artifact file names, the version the server reports, and Android `versionName` / `versionCode`. `version` must be numeric (`1.2.0`) because the installer is built with `jpackage`.

## Build

**PC server**

```bash
./gradlew :desktop:run
```

Listens on port **8765**.

Packaged app, copied to `artifacts/desktop`:

```bash
./gradlew packageDesktop
```

| Host | Output |
|---|---|
| Windows | `Remote Controller Server-1.0.0.exe` and a portable folder |
| macOS | `Remote Controller Server-1.0.0.dmg` and a portable `.app` |

Names follow `version.properties`. Packaging needs a full JDK 21 with `jpackage` (Android Studio JBR does not have it). On macOS the volume library also needs CMake and `jni.h`. Point Gradle at that JDK with `desktop.jdk.home` in `local.properties` or `JAVA_HOME`.

**Android**

```bash
./gradlew :android:assembleDebug
```

**iOS, Kotlin only**

```bash
./gradlew :client:ui:linkDebugFrameworkIosArm64            # device
./gradlew :client:ui:linkDebugFrameworkIosSimulatorArm64   # simulator
```

**iOS app**

Open `iosApp/iosApp.xcodeproj` and run the `iosApp` target. The Xcode build calls:

```bash
./gradlew :client:ui:embedAndSignAppleFrameworkForXcode
```

That task picks device or simulator and Debug or Release from the Xcode environment and writes `ClientUI` to `client/ui/build/xcode-frameworks/`. A real iPhone needs automatic signing with your Apple team. `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` is empty.
