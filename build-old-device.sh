#!/bin/sh
#
# Builds Matrix Clock for older 32-bit devices (armeabi-v7a), such as the LG G Pad 8.3.
#
# This is the "legacy" flavour. It deliberately leaves out the bundled sherpa-onnx TTS engine:
# that engine is an arm64-only APK, so carrying it here would add ~82 MB that could never be
# installed on a 32-bit device. The result is about 51 MB instead of 133 MB.
#
# Consequence: on a device with no speech engine of its own, wake words are still recognised but
# nothing is spoken until any 32-bit TTS engine is installed separately.
#
# Usage:  ./build-old-device.sh [debug|release] [--install]
#
set -eu

BUILD_TYPE="${1:-debug}"
INSTALL="${2:-}"

case "$BUILD_TYPE" in
    debug)   TASK=":app:assembleLegacyDebug";   OUT="app/build/outputs/apk/legacy/debug/app-legacy-debug.apk" ;;
    release) TASK=":app:assembleLegacyRelease"; OUT="app/build/outputs/apk/legacy/release/app-legacy-release-unsigned.apk" ;;
    *) echo "Usage: $0 [debug|release] [--install]" >&2; exit 2 ;;
esac

cd "$(dirname "$0")"

# Pull the Vosk model if it is not already here. The TTS engine is arm64 only, so it is not needed.
./fetch-assets.sh

echo "Building armeabi-v7a ($BUILD_TYPE), without bundled TTS engine..."
./gradlew "$TASK"

if [ ! -f "$OUT" ]; then
    echo "Expected APK not found at $OUT" >&2
    exit 1
fi

SIZE=$(ls -l "$OUT" | awk '{printf "%.1f MB", $5/1048576}')
echo
echo "APK:  $OUT  ($SIZE)"
echo "ABI:  armeabi-v7a"
echo "TTS:  not bundled (install any 32-bit TTS engine on the device)"

if [ "$INSTALL" = "--install" ]; then
    if [ "$BUILD_TYPE" = "release" ]; then
        echo "Release builds are unsigned and cannot be installed directly." >&2
        exit 1
    fi
    echo
    # USB to some older devices is unreliable; adb over TCP is often the sturdier route:
    #   adb connect <tablet-ip>:5555 && adb -s <tablet-ip>:5555 install -r "$OUT"
    echo "Installing..."
    adb install -r "$OUT"
fi
