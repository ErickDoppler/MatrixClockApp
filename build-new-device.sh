#!/bin/sh
#
# Builds Matrix Clock for modern 64-bit devices (arm64-v8a).
#
# This is the "full" flavour: it carries the bundled sherpa-onnx TTS engine, so a device with no
# speech engine of its own can install one offline from inside the app. That engine is an arm64
# APK, which is why it only ships in this flavour.
#
# Usage:  ./build-new-device.sh [debug|release] [--install]
#
set -eu

BUILD_TYPE="${1:-debug}"
INSTALL="${2:-}"

case "$BUILD_TYPE" in
    debug)   TASK=":app:assembleFullDebug";   OUT="app/build/outputs/apk/full/debug/app-full-debug.apk" ;;
    release) TASK=":app:assembleFullRelease"; OUT="app/build/outputs/apk/full/release/app-full-release-unsigned.apk" ;;
    *) echo "Usage: $0 [debug|release] [--install]" >&2; exit 2 ;;
esac

cd "$(dirname "$0")"

# Pull the Vosk model and the bundled TTS engine if they are not already here.
./fetch-assets.sh --with-tts-engine

echo "Building arm64-v8a ($BUILD_TYPE), with bundled TTS engine..."
./gradlew "$TASK"

if [ ! -f "$OUT" ]; then
    echo "Expected APK not found at $OUT" >&2
    exit 1
fi

SIZE=$(ls -l "$OUT" | awk '{printf "%.1f MB", $5/1048576}')
echo
echo "APK:  $OUT  ($SIZE)"
echo "ABI:  arm64-v8a"
echo "TTS:  sherpa-onnx engine bundled"

if [ "$INSTALL" = "--install" ]; then
    if [ "$BUILD_TYPE" = "release" ]; then
        echo "Release builds are unsigned and cannot be installed directly." >&2
        exit 1
    fi
    echo
    echo "Installing..."
    adb install -r "$OUT"
fi
