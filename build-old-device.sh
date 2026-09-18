#!/bin/sh
#
# Builds Matrix Clock for older 32-bit devices (armeabi-v7a), such as the LG G Pad 8.3.
#
# This is the "legacy" flavour. It bundles two speech engines that are light enough for old
# hardware, because a neural engine is not: on an LG G Pad 8.3, sherpa-onnx took three minutes to
# load and never produced audio at all.
#
#   - Pico, compiled from SVOX sources by the picotts module. ~4 MB, entirely offline, and clearly
#     smoother than eSpeak. Built here, then bundled as an asset.
#   - RHVoice, ~15 MB. Clearer still, but one voice must be downloaded inside RHVoice afterwards.
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

# Pico is native code, so unlike the 64-bit build this one needs an NDK. Say so plainly rather than
# letting Gradle fail several screens later with a CXX error.
SDK_DIR=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | tr -d '\r')
SDK_DIR="${SDK_DIR:-${ANDROID_HOME:-}}"
if [ -z "$SDK_DIR" ] || [ ! -d "$SDK_DIR/ndk" ] || [ -z "$(ls -A "$SDK_DIR/ndk" 2>/dev/null)" ]; then
    echo "The Android NDK is required to compile the Pico speech engine, and none was found." >&2
    echo "Install one (r26 or newer) so it lands in <sdk>/ndk/<version>, for example with:" >&2
    echo "    sdkmanager --install \"ndk;26.3.11579264\"" >&2
    echo "Checked SDK location: ${SDK_DIR:-<unset>}" >&2
    exit 1
fi

# Pull the Vosk model, RHVoice and the Pico sources if they are not already here.
./fetch-assets.sh legacy

# Compile the Pico engine and bundle it alongside RHVoice, so the app can offer either.
# The engine is a separate app, so a debug-signed build is fine and keeps it installable.
echo "Compiling the Pico speech engine..."
./gradlew :picotts:assembleDebug

PICO_APK="picotts/build/outputs/apk/debug/picotts-debug.apk"
if [ ! -f "$PICO_APK" ]; then
    echo "Pico engine was not built at $PICO_APK" >&2
    exit 1
fi
mkdir -p app/src/legacy/assets/tts-engines
cp "$PICO_APK" app/src/legacy/assets/tts-engines/com.example.picotts.apk

echo "Building armeabi-v7a ($BUILD_TYPE), with bundled Pico and RHVoice engines..."
./gradlew "$TASK"

if [ ! -f "$OUT" ]; then
    echo "Expected APK not found at $OUT" >&2
    exit 1
fi

SIZE=$(ls -l "$OUT" | awk '{printf "%.1f MB", $5/1048576}')
echo
echo "APK:  $OUT  ($SIZE)"
echo "ABI:  armeabi-v7a"
echo "TTS:  Pico and RHVoice bundled (offered in Settings)"

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
