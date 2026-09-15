#!/bin/sh
#
# Downloads the large build assets that are deliberately kept out of git.
#
# Both build scripts call this automatically, so a fresh clone builds with no manual setup. Every
# step is idempotent: anything already in place is left alone, so re-running is cheap.
#
# Usage:  ./fetch-assets.sh [full|legacy]
#
#   (no argument)  Vosk speech model only.
#   full           Also the arm64 TTS engine  (sherpa-onnx, ~80 MB, high quality).
#   legacy         Also the 32-bit TTS engine (eSpeak NG,   ~10 MB, robotic but tiny).
#
# The two flavours carry different engines because sherpa-onnx's 32-bit build is still ~80 MB —
# almost all of it the ONNX runtime rather than the voice — which would more than double the size
# of the legacy APK. eSpeak NG is a fraction of that and runs anywhere.
#
set -eu

cd "$(dirname "$0")"

VOSK_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
VOSK_DIR="app/src/main/assets/model-en-us"

# sherpa-onnx TTS engine, arm64, Piper en_GB voice. Apache-2.0.
FULL_ENGINE_URL="https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-2/sherpa-onnx-1.10.0-arm64-v8a-en-tts-engine-vits-piper-en_GB-alan-medium.apk"
FULL_ENGINE_PATH="app/src/full/assets/tts-engine.apk"

# eSpeak NG, from F-Droid. Universal APK, includes armeabi-v7a. GPL-3.0.
LEGACY_ENGINE_URL="https://f-droid.org/repo/com.reecedunn.espeak_22.apk"
LEGACY_ENGINE_PATH="app/src/legacy/assets/tts-engine.apk"

FLAVOUR="${1:-}"

need() {
    command -v "$1" >/dev/null 2>&1 || { echo "Required tool '$1' not found in PATH." >&2; exit 1; }
}
need curl
need unzip

# Downloads to a temp file first, so an interrupted transfer cannot leave a broken asset behind.
download_to() {
    _url="$1"; _dest="$2"; _label="$3"
    if [ -f "$_dest" ]; then
        echo "$_label already present, skipping."
        return 0
    fi
    echo "Downloading $_label..."
    mkdir -p "$(dirname "$_dest")"
    _tmp=$(mktemp -t engine.XXXXXX)
    curl -fL --retry 3 --progress-bar -o "$_tmp" "$_url"
    mv "$_tmp" "$_dest"
    echo "Installed at $_dest"
}

# ---- Vosk speech model (required by every build) -------------------------------------------

# conf/model.conf is the last thing the archive writes, so its presence means a complete unpack.
if [ -f "$VOSK_DIR/conf/model.conf" ]; then
    echo "Vosk model already present, skipping."
else
    echo "Downloading Vosk speech model (~39 MB)..."
    TMP_ZIP=$(mktemp -t vosk.XXXXXX)
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

# ---- Bundled TTS engine, per flavour --------------------------------------------------------

case "$FLAVOUR" in
    full)   download_to "$FULL_ENGINE_URL"   "$FULL_ENGINE_PATH"   "sherpa-onnx TTS engine (~80 MB)" ;;
    legacy) download_to "$LEGACY_ENGINE_URL" "$LEGACY_ENGINE_PATH" "eSpeak NG TTS engine (~10 MB)" ;;
    "")     ;;
    *) echo "Unknown flavour '$FLAVOUR'. Use 'full' or 'legacy'." >&2; exit 2 ;;
esac

echo "Assets ready."
