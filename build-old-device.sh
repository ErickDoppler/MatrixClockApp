#!/bin/sh
#
# Builds Matrix Clock for older 32-bit devices (armeabi-v7a), such as the LG G Pad 8.3.
#
# This is the "legacy" flavour. It bundles eSpeak NG rather than sherpa-onnx, because:
#   - sherpa-onnx has no usable small 32-bit build: its armeabi-v7a engine is still ~80 MB, almost
#     all of it the ONNX runtime rather than the voice.
#   - eSpeak NG is ~10 MB, runs on 32-bit hardware, and needs no voice downloads.
#
# The result is about 61 MB instead of 133 MB. eSpeak is robotic but perfectly intelligible, and
# it works with no network at all.
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

# Pull the Vosk model and the small eSpeak NG engine if they are not already here.
./fetch-assets.sh legacy

echo "Building armeabi-v7a ($BUILD_TYPE), with bundled eSpeak NG engine..."
./gradlew "$TASK"

if [ ! -f "$OUT" ]; then
    echo "Expected APK not found at $OUT" >&2
    exit 1
fi

SIZE=$(ls -l "$OUT" | awk '{printf "%.1f MB", $5/1048576}')
echo
echo "APK:  $OUT  ($SIZE)"
echo "ABI:  armeabi-v7a"
echo "TTS:  eSpeak NG bundled (offered if the device has no engine)"

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
