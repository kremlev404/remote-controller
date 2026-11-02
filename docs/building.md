# Building and releasing

```bash
./gradlew :desktop:run                  # laptop app; builds :web first and bundles it
./gradlew packageDesktop                # portable app for the host OS in artifacts/desktop
./scripts/build-linux.sh                # Linux: checks the tools, then the same as packageDesktop
./gradlew :web:wasmJsBrowserDistribution  # web page only → web/build/dist/wasmJs/productionExecutable
./gradlew :relay:installDist              # relay server; bundles the web page too
./gradlew :android:assembleDebug        # Android
```

iOS: open `iosApp` in Xcode (builds the `ClientUI` framework from `:client:feature:common`).

`:desktop:processResources` and `:relay:processResources` depend on `:web:wasmJsBrowserDistribution` and copy its output into the `web` resource folder, so both always ship the current page. Packaging needs a full JDK with `jpackage` (set `JAVA_HOME` or `DESKTOP_JDK_HOME`); on macOS also CMake for the volume library. On Windows `screencast.dll` needs Visual Studio 2022 with the C++ workload, which brings CMake; the build finds it by itself. GitHub's Windows runners already have it.

## CI and releases

There is one workflow: [.github/workflows/ci.yml](../.github/workflows/ci.yml). Releases need no tags.

1. Open a pull request from a branch into `main`. **Lint** runs.
2. After lint passes, the same run stops at **Release approval**. To release, open the run and press **Review deployments → Approve**. To skip, ignore it: new pushes to the pull request replace the waiting run.
3. After approval these jobs run, and **Publish GitHub release** goes last, only if all of them pass.

On Windows and Linux the desktop file is portable: unpack and run, nothing to install. macOS gets a `.dmg`. Each carries its own Java runtime.

| Job | Builds | Release files |
|---|---|---|
| Desktop · Windows x64 | portable app | `remote-controller-server-<v>-windows-x64.zip` |
| Desktop · macOS arm64 | `.dmg` (Apple Silicon) | `remote-controller-server-<v>-macos-arm64.dmg` |
| Desktop · Linux x64 | portable app | `remote-controller-server-<v>-linux-x64.tar.xz` |
| Android · release APK | signed `assembleRelease`, signature checked | `remote-controller-<v>.apk` |
| **Publish GitHub release** | release `v<version>`, with the tag created on the pull request's head commit | everything, plus `SHA256SUMS.txt` |

**Running them:**

| OS | Steps |
|---|---|
| Windows | unzip, run `Remote Controller Server\Remote Controller Server.exe` |
| macOS | open the `.dmg`, drag the app to Applications (or start it straight from the image) |
| Linux (Ubuntu, Kali, Arch) | `tar -xJf remote-controller-server-*-linux-x64.tar.xz`, run `Remote Controller Server/bin/Remote Controller Server` |

**Size.** The app no longer ships the 36 MB Material icons library: `DesktopIcons` keeps the 15 icons it uses. It also drops Netty's QUIC/HTTP3 native libraries (~11 MB), since the server speaks HTTP/1.1 only. Linux uses `xz -9e`, about 12% smaller than gzip. Measured on the macOS app: one compressed copy went from 147 MB to about 90 MB. The old Linux release had four copies per CPU (.deb, .rpm, .tar.gz, Arch), about 600 MB in all, and now has one.

**ProGuard.** Release builds (`packageDesktop`, `createReleaseDistributable`) run the jars through ProGuard, shrinking only: no renaming, no optimization. The rules are in [desktop/proguard-rules.pro](../desktop/proguard-rules.pro). Libraries that load classes by name are kept whole (Ktor, Netty, BouncyCastle, JNA, OkHttp, SLF4J, cryptography-kotlin), and Compose and the rest are shrunk.

If the release app fails where `./gradlew :desktop:run` works:
1. Build it without ProGuard: `./gradlew :desktop:packageDesktop -Pdesktop.proguard=false`.
2. If that build works, add a `-keep` rule for the class named in the error.

`./gradlew :desktop:runReleaseDistributable` starts the shrunk app directly.

**macOS.** The app is built for Apple Silicon only, on GitHub's `macos-15` runners. Intel Macs are not covered by the release. It is not signed with an Apple Developer ID. On first launch, macOS blocks it, and the user allows it once in **System Settings → Privacy & Security → Open Anyway**. Removing that step needs a paid Apple Developer account, signing and notarization.

**Version.** It is `version` in `version.properties`. If `v<version>` already exists, the run stops before building, so bump `version` and `versionCode` in the pull request first.

**Without a pull request.** Use **Actions → CI → Run workflow**, pick a branch, and it runs lint and then the release with no approval step.

**One-time setup** (repository Settings):
- **Environments → New environment `release` → Required reviewers:** add yourself. The approval job checks this rule and refuses to release without it, so a missing setting cannot release every pull request. Private repositories on the Free plan cannot add reviewers to an environment; use **Run workflow** there.
- **Secrets and variables → Actions:** add the APK signing secrets below. `scripts/local/make-android-signing.sh --gh` creates and uploads them. That script stays local: `scripts/local/` is git-ignored.
- **Branch protection:** if you use it, make only **Lint** required, so an unapproved release does not block merging.

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -i release.jks` |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | keystore password, key alias, key password |

For a local signed build, set the same names as environment variables or in `~/.gradle/gradle.properties`, with `ANDROID_KEYSTORE_FILE` pointing at the `.jks` (the script's `--local` does this). Without them `assembleRelease` builds an unsigned APK.

## Linux

The release ships one portable `.tar.xz` for x64. ARM Linux is not covered by the release. It runs on **Ubuntu (22.04 and newer), Kali and Arch**. It brings its own Java runtime, so nothing has to be installed:

```bash
tar -xJf remote-controller-server-<version>-linux-x64.tar.xz
"./Remote Controller Server/bin/Remote Controller Server"
```

**Building.** `./scripts/build-linux.sh` checks the tools, then runs `packageDesktop`. It needs:
- Linux, because jpackage builds only for the OS and CPU it runs on
- a full JDK 21 with `jpackage`
- `xz`

Build on the oldest distribution you support, because newer glibc is fine and older is not. CI builds on Ubuntu 22.04.

**At runtime:**

| Feature | Uses |
|---|---|
| Volume | `wpctl` (PipeWire), `pactl` (PulseAudio) or `amixer` (ALSA), whichever exists |
| Play, pause, next, previous | `playerctl` (install it; any MPRIS player works) |
| Start at login | `~/.config/autostart` |
| **Trust this computer** | adds the CA to `~/.pki/nssdb` for Chromium browsers (needs `certutil` from `libnss3-tools` / `nss-tools`); import `local-ca.crt` into Firefox yourself |
| Firewall | with firewalld running (Fedora), the window shows the port command and **Allow again** asks through `pkexec` |
| App folder | `~/.local/share/remote-controller` |

Mouse, keyboard and screen sharing need an **X11 session**: on GNOME or KDE with Wayland, choose "Xorg" or "Plasma (X11)" at login. Install `xdotool` to type more than plain Latin letters from the phone.

## Modules

| Module | What it is |
|---|---|
| `:desktop` | Laptop app and server (Ktor, TLS, LinkGate, approvals, relay link, input, volume, screen) |
| `:host:screencast` | Capture and H.264 on each OS: a native library (C++, Objective-C++) and its Kotlin API |
| `:web` | Browser client (Kotlin/Wasm), served at `/web` and by the relay |
| `:relay` | Self-hosted rendezvous relay (Ktor): PC IDs, session pipes |
| `:android`, `iosApp` | Phone apps |
| `:client:feature:*` | Phone screens (`api` route + `impl` screen), `common` shell, `umbrella` wiring |
| `:client:core` | Shared route contract |
| `:client:domain`, `:client:data`, `:client:storage` | Use cases, socket, saved servers |
| `:shared` | Protocol, models, link crypto (v2), relay protocol |
| `build-logic` | Gradle convention plugins |

Product name and version: `version.properties`.
