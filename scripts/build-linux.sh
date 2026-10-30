#!/usr/bin/env bash
# Builds the portable Linux app into artifacts/desktop/remote-controller-server-<version>-linux-<arch>.tar.xz.
# It carries its own Java runtime and runs on Ubuntu, Kali and Arch (and any glibc distribution):
#   tar -xJf remote-controller-server-*-linux-x64.tar.xz && "Remote Controller Server/bin/Remote Controller Server"
#
# Needs Linux, a full JDK 21 (jpackage) and xz. Build on the oldest distribution you support:
# the bundled runtime runs on that glibc and newer.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "The Linux app is built on Linux (jpackage cannot cross-build)." >&2
    exit 1
fi

missing=()
command -v xz >/dev/null 2>&1 || missing+=("xz (apt: xz-utils, pacman: xz)")
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/jpackage" ]]; then
    command -v jpackage >/dev/null 2>&1 || missing+=("JDK 21 with jpackage (apt: openjdk-21-jdk, pacman: jdk21-openjdk)")
fi
if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing tools:" >&2
    printf '  - %s\n' "${missing[@]}" >&2
    exit 1
fi

(cd "$PROJECT_ROOT" && ./gradlew :desktop:packageDesktop --no-daemon "$@")

echo
ls -lh "$PROJECT_ROOT"/artifacts/desktop/*.tar.xz
