#!/bin/sh
#
# Downloads the large build assets that are deliberately kept out of git.
#
# Both build scripts call this automatically, so a fresh clone builds with no manual setup. Every
# step is idempotent: anything already in place is left alone, so re-running is cheap.
#
# Usage:  ./fetch-assets.sh [--with-tts-engine]
#
#   (no flag)           Vosk speech model only — enough for the 32-bit "legacy" build.
#   --with-tts-engine   Also the sherpa-onnx TTS engine APK, bundled into the arm64 "full" build.
#
set -eu

cd "$(dirname "$0")"

VOSK_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
VOSK_DIR="app/src/main/assets/model-en-us"

# Any arm64 sherpa-onnx TTS engine works; this is a Piper en_GB voice. Apache-2.0.
ENGINE_URL="https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-2/sherpa-onnx-1.10.0-arm64-v8a-en-tts-engine-vits-piper-en_GB-alan-medium.apk"
ENGINE_PATH="app/src/full/assets/sherpa-onnx-tts-engine.apk"

WITH_ENGINE="${1:-}"

need() {
    command -v "$1" >/dev/null 2>&1 || { echo "Required tool '$1' not found in PATH." >&2; exit 1; }
}
need curl
need unzip

# ---- Vosk speech model (required by every build) -------------------------------------------

# conf/model.conf is the last thing the archive writes, so its presence means a complete unpack.
if [ -f "$VOSK_DIR/conf/model.conf" ]; then
    echo "Vosk model already present, skipping."
else
    echo "Downloading Vosk speech model (~39 MB)..."
    TMP_ZIP=$(mktemp -t vosk.XXXXXX)
    # Download to a temp file first so an interrupted transfer cannot leave a half-unpacked model.
    curl -fL --retry 3 --progress-bar -o "$TMP_ZIP" "$VOSK_URL"

    echo "Unpacking..."
    TMP_DIR=$(mktemp -d -t voskdir.XXXXXX)
    unzip -q "$TMP_ZIP" -d "$TMP_DIR"

    mkdir -p "$(dirname "$VOSK_DIR")"
    rm -rf "$VOSK_DIR"
    mv "$TMP_DIR"/vosk-model-* "$VOSK_DIR"

    rm -rf "$TMP_ZIP" "$TMP_DIR"
    echo "Vosk model installed at $VOSK_DIR"
fi

# ---- sherpa-onnx TTS engine (arm64 "full" flavour only) ------------------------------------

if [ "$WITH_ENGINE" = "--with-tts-engine" ]; then
    if [ -f "$ENGINE_PATH" ]; then
        echo "TTS engine already present, skipping."
    else
        echo "Downloading sherpa-onnx TTS engine (~84 MB)..."
        mkdir -p "$(dirname "$ENGINE_PATH")"
        TMP_APK=$(mktemp -t engine.XXXXXX)
        curl -fL --retry 3 --progress-bar -o "$TMP_APK" "$ENGINE_URL"
        mv "$TMP_APK" "$ENGINE_PATH"
        echo "TTS engine installed at $ENGINE_PATH"
    fi
fi

echo "Assets ready."
