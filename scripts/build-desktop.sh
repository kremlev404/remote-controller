#!/usr/bin/env bash
set -euo pipefail

TARGET="all"
CLEAN=false

usage() {
    cat <<'EOF'
Usage: ./scripts/build-desktop.sh [--target all|portable|exe|msi] [--clean]

Builds the desktop app and copies outputs to artifacts/desktop/.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)
            TARGET="${2:-}"
            shift 2
            ;;
        --clean)
            CLEAN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage
            exit 1
            ;;
    esac
done

case "$TARGET" in
    all|portable|exe|msi) ;;
    *)
        echo "Invalid target: $TARGET" >&2
        usage
        exit 1
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ARTIFACTS_DIR="$PROJECT_ROOT/artifacts/desktop"
BINARIES_DIR="$PROJECT_ROOT/desktop/build/compose/binaries/main"
PACKAGE_NAME="Remote Controller Server"

resolve_java_home() {
    if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        echo "$JAVA_HOME"
        return
    fi

    local candidates=(
        "$HOME/.local/share/JetBrains/Toolbox/apps/AndroidStudio/jbr"
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
        "/usr/lib/jvm/java-21-openjdk"
        "/usr/lib/jvm/java-17-openjdk"
    )

    for candidate in "${candidates[@]}"; do
        if [[ -x "$candidate/bin/java" ]]; then
            echo "$candidate"
            return
        fi
    done

    if command -v java >/dev/null 2>&1; then
        return 0
    fi

    echo "JDK not found. Set JAVA_HOME or install JDK 17+." >&2
    exit 1
}

resolve_gradle_launcher() {
    if [[ -x "$PROJECT_ROOT/gradlew" ]]; then
        echo "$PROJECT_ROOT/gradlew"
        return
    fi

    if command -v gradle >/dev/null 2>&1; then
        command -v gradle
        return
    fi

    echo "Gradle not found. Generate Gradle Wrapper (gradlew) or install Gradle." >&2
    exit 1
}

run_gradle() {
    local java_home
    java_home="$(resolve_java_home || true)"
    if [[ -n "${java_home:-}" ]]; then
        export JAVA_HOME="$java_home"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "Using JAVA_HOME=$JAVA_HOME"
    fi

    local gradle
    gradle="$(resolve_gradle_launcher)"
    echo "Using Gradle=$gradle"
    echo "Running: $gradle $*"

    (cd "$PROJECT_ROOT" && "$gradle" "$@" --no-daemon)
}

copy_glob() {
    local pattern="$1"
    local destination="$2"
    shopt -s nullglob
    local files=($pattern)
    shopt -u nullglob

    for file in "${files[@]}"; do
        cp -f "$file" "$destination/"
        echo "Copied $(basename "$file") -> $destination"
    done
}

echo "Project: $PROJECT_ROOT"
echo "Artifacts: $ARTIFACTS_DIR"
mkdir -p "$ARTIFACTS_DIR"

TASKS=()
if [[ "$CLEAN" == true ]]; then
    TASKS+=(":desktop:clean")
fi

case "$TARGET" in
    portable) TASKS+=(":desktop:createDistributable") ;;
    exe) TASKS+=(":desktop:packageExe") ;;
    msi) TASKS+=(":desktop:packageMsi") ;;
    all)
        TASKS+=(
            ":desktop:createDistributable"
            ":desktop:packageExe"
            ":desktop:packageMsi"
        )
        ;;
esac

run_gradle "${TASKS[@]}"

if [[ "$TARGET" == "all" || "$TARGET" == "portable" ]]; then
    PORTABLE_SOURCE="$BINARIES_DIR/app/$PACKAGE_NAME"
    PORTABLE_DEST="$ARTIFACTS_DIR/portable"

    if [[ ! -d "$PORTABLE_SOURCE" ]]; then
        echo "Portable build output not found: $PORTABLE_SOURCE" >&2
        exit 1
    fi

    rm -rf "$PORTABLE_DEST"
    cp -R "$PORTABLE_SOURCE" "$PORTABLE_DEST"
    echo "Copied portable app -> $PORTABLE_DEST"
fi

if [[ "$TARGET" == "all" || "$TARGET" == "exe" ]]; then
    copy_glob "$BINARIES_DIR/exe/"*.exe "$ARTIFACTS_DIR"
fi

if [[ "$TARGET" == "all" || "$TARGET" == "msi" ]]; then
    copy_glob "$BINARIES_DIR/msi/"*.msi "$ARTIFACTS_DIR"
fi

echo
echo "Done. Desktop artifacts are in:"
echo "  $ARTIFACTS_DIR"
