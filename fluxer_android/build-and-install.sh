#!/usr/bin/env bash
# Build Fluxer Android APK on Termux (ARM64)
#
# Usage:
#   ./build-and-install.sh [debug|release] [clean] [--web]
#
# Options:
#   debug     — build debug APK (default)
#   release   — build release APK
#   clean     — clean before building
#   --web     — also build web assets from fluxer_app and bundle them
#
# Prerequisites:
#   - Android SDK at ~/android-sdk
#   - Java 17+ (Termux: pkg install openjdk-21)
#   - aapt2 (Termux: pkg install aapt2, or bundled ARM64 binary)
#   - For --web: pnpm, node, rustc+wasm-pack

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Environment ---
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/34.0.0:$PATH"

BUILD_TYPE="${1:-debug}"
CLEAN="${2:-}"
BUILD_WEB=false

# Parse args
for arg in "$@"; do
    case "$arg" in
        debug)   BUILD_TYPE="debug" ;;
        release) BUILD_TYPE="release" ;;
        clean)   CLEAN="clean" ;;
        --web)   BUILD_WEB=true ;;
    esac
done

echo "=== Fluxer Android Build ==="
echo "  Build type:  $BUILD_TYPE"
echo "  Android SDK: $ANDROID_HOME"
echo "  Java:        $(java -version 2>&1 | head -1)"
echo ""

# --- Prerequisites check ---
if [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: Android SDK not found at $ANDROID_HOME"
    echo "Install with: sdkmanager --install 'platforms;android-36' 'build-tools;34.0.0'"
    exit 1
fi

if ! command -v java &>/dev/null; then
    echo "Error: Java not found. Install: pkg install openjdk-21"
    exit 1
fi

# --- Find aapt2 ---
# Priority: bundled ARM64 binary > for-android tools > Termux pkg
AAPT2_BIN=""
if [ -x "$HOME/git/for-android/tools/aapt2-arm64/aapt2" ]; then
    AAPT2_BIN="$HOME/git/for-android/tools/aapt2-arm64/aapt2"
    echo "  aapt2:       $AAPT2_BIN (ARM64 binary, SDK 36+)"
elif command -v aapt2 &>/dev/null; then
    AAPT2_BIN="$(command -v aapt2)"
    echo "  aapt2:       $AAPT2_BIN (Termux pkg)"
else
    echo "Error: aapt2 not found."
    echo "Install: pkg install aapt2"
    echo "Or copy ARM64 binary to tools/aapt2-arm64/aapt2"
    exit 1
fi

# --- Write local.properties ---
cat > "$SCRIPT_DIR/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
EOF

# --- Ensure aapt2 in gradle cache is ARM64 (Termux only) ---
# AGP's AarResourcesCompilerTransform uses aapt2 from the maven jar cache,
# which is x86_64 by default. On ARM64 Termux we repack it once so builds
# survive gradle cache clears.
if [ "$(uname -m)" = "aarch64" ]; then
    AAPT2_JAR=$(find ~/.gradle/caches/modules-2 -path "*aapt2*linux.jar" -type f 2>/dev/null | head -1)
    if [ -n "$AAPT2_JAR" ]; then
        CACHED_ARCH=$(unzip -p "$AAPT2_JAR" aapt2 2>/dev/null | file - | grep -o "x86-64" || true)
        if [ -n "$CACHED_ARCH" ]; then
            echo "  Repacking gradle aapt2 jar with ARM64 binary..."
            REPACK_DIR=$(mktemp -d)
            (cd "$REPACK_DIR" && unzip -qo "$AAPT2_JAR" && cp "$AAPT2_BIN" aapt2 && chmod +x aapt2 && jar cf repacked.jar META-INF/MANIFEST.MF aapt2 NOTICE && cp repacked.jar "$AAPT2_JAR")
            rm -rf "$REPACK_DIR"
            # Clear stale transforms so they rebuild with ARM64 aapt2
            find ~/.gradle/caches -maxdepth 2 -name "transforms" -type d -exec rm -rf {} + 2>/dev/null || true
            echo "  Done — gradle aapt2 cache is now ARM64."
        fi
    fi
fi

# --- Optionally build web assets ---
if [ "$BUILD_WEB" = true ]; then
    echo ""
    echo "=== Building Web Assets ==="
    cd "$PROJECT_ROOT"

    if ! command -v pnpm &>/dev/null; then
        echo "Error: pnpm not found. Install: npm i -g pnpm"
        exit 1
    fi

    echo "  Installing dependencies..."
    pnpm install --frozen-lockfile 2>&1 | tail -3

    echo "  Building fluxer_app..."
    pnpm --filter fluxer_app build 2>&1 | tail -5

    # Copy built assets to Android project
    WEB_DIST="$PROJECT_ROOT/fluxer_app/dist"
    ASSETS_DIR="$SCRIPT_DIR/app/src/main/assets/www"

    if [ -d "$WEB_DIST" ]; then
        rm -rf "$ASSETS_DIR"
        mkdir -p "$ASSETS_DIR"
        cp -r "$WEB_DIST"/* "$ASSETS_DIR/"
        echo "  Web assets copied to $ASSETS_DIR"
    else
        echo "Warning: fluxer_app/dist not found after build."
        echo "  The app will load from remote URL instead."
    fi

    cd "$SCRIPT_DIR"
fi

# --- Gradle build ---
echo ""
echo "=== Building APK ==="
cd "$SCRIPT_DIR"

GRADLE_ARGS=(
    "-Pandroid.aapt2FromMavenOverride=$AAPT2_BIN"
    "--no-daemon"
    "-Dorg.gradle.jvmargs=-Xmx2048m -XX:MaxMetaspaceSize=512m"
)

if [ "$CLEAN" = "clean" ]; then
    echo "  Cleaning..."
    ./gradlew "${GRADLE_ARGS[@]}" clean 2>&1 | tail -3
fi

if [ "$BUILD_TYPE" = "release" ]; then
    TASK="assembleRelease"
else
    TASK="assembleDebug"
fi

echo "  Running: ./gradlew $TASK"
./gradlew "${GRADLE_ARGS[@]}" "$TASK" 2>&1

# --- Find APK ---
echo ""
echo "=== Locating APK ==="
APK_DIR="$SCRIPT_DIR/app/build/outputs/apk/$BUILD_TYPE"
APK_FILE=$(find "$APK_DIR" -name "*.apk" -type f 2>/dev/null | head -1)

if [ -z "$APK_FILE" ]; then
    echo "Error: No APK found in $APK_DIR"
    exit 1
fi

APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
echo "  APK: $APK_FILE ($APK_SIZE)"

# --- Install via ADB ---
echo ""
echo "=== Installing ==="

install_via_adb() {
    if adb devices 2>/dev/null | grep -q "device$"; then
        echo "  Installing via ADB..."
        adb install -r "$APK_FILE" && return 0
    fi
    return 1
}

install_via_adb_wireless() {
    local ip
    ip=$(ip -4 addr show wlan0 2>/dev/null | grep -oP 'inet \K[\d.]+' | head -1)
    if [ -z "$ip" ]; then
        ip=$(ip -4 addr show 2>/dev/null | grep -oP 'inet \K[\d.]+' | grep -v '127.0.0.1' | head -1)
    fi
    if [ -z "$ip" ]; then
        return 1
    fi

    local subnet="${ip%.*}"
    echo "  Scanning for ADB devices on $subnet.0/24..."

    # Try common ADB wireless ports
    for port in $(seq 37000 37100) $(seq 42000 42100) $(seq 5555 5558); do
        if timeout 0.3 bash -c "echo >/dev/tcp/$subnet.1/$port" 2>/dev/null; then
            echo "  Found device at $subnet.1:$port"
            adb connect "$subnet.1:$port" 2>/dev/null
            sleep 1
            if adb devices 2>/dev/null | grep -q "device$"; then
                adb install -r "$APK_FILE" && return 0
            fi
        fi
    done
    return 1
}

install_via_termux_open() {
    if command -v termux-open &>/dev/null; then
        local dest="/sdcard/Download/fluxer-${BUILD_TYPE}.apk"
        cp "$APK_FILE" "$dest" 2>/dev/null || return 1
        echo "  APK copied to $dest"
        termux-open "$dest" && return 0
    fi
    return 1
}

if install_via_adb; then
    echo "  Installed successfully via ADB."
elif install_via_adb_wireless; then
    echo "  Installed successfully via ADB wireless."
elif install_via_termux_open; then
    echo "  Opened with termux-open."
else
    echo "  Could not install automatically."
    echo "  Copy APK to device: $APK_FILE"
fi

echo ""
echo "=== Done ==="
