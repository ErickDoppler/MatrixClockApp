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
#   full           Also the arm64 TTS engine (sherpa-onnx: natural neural voice, ~80 MB).
#   legacy         Also RHVoice, plus the SVOX Pico sources that ./build-old-device.sh compiles.
#
# Why the flavours differ: a neural engine is far and away the most natural, but it cannot run on
# old hardware — on an LG G Pad 8.3 sherpa-onnx took three minutes to load and never produced any
# audio. So 32-bit builds carry RHVoice and Pico, both of which are light enough to work there.
#
set -eu

cd "$(dirname "$0")"

VOSK_URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
VOSK_DIR="app/src/main/assets/model-en-us"

# sherpa-onnx TTS engine, arm64, Piper en_GB voice. Apache-2.0.
FULL_ENGINE_URL="https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-2/sherpa-onnx-1.10.0-arm64-v8a-en-tts-engine-vits-piper-en_GB-alan-medium.apk"
FULL_ENGINE_PATH="app/src/full/assets/tts-engines/com.k2fsa.sherpa.onnx.tts.engine.apk"

# RHVoice, from F-Droid. Universal APK. GPL-3.0. Voices are downloaded inside RHVoice itself.
RHVOICE_URL="https://f-droid.org/repo/com.github.olga_yakovleva.rhvoice.android_118040.apk"
RHVOICE_PATH="app/src/legacy/assets/tts-engines/com.github.olga_yakovleva.rhvoice.android.apk"

# SVOX Pico, the synthesiser Android itself shipped for years. Apache-2.0. Compiled by
# ./build-old-device.sh into a small engine APK; see the picotts module.
PICO_SRC_URL="https://github.com/ihuguet/picotts/archive/refs/heads/master.tar.gz"
PICO_CPP_DIR="picotts/src/main/cpp/pico"
PICO_VOICE_DIR="picotts/src/main/assets/pico"

FLAVOUR="${1:-}"

need() {
    command -v "$1" >/dev/null 2>&1 || { echo "Required tool '$1' not found in PATH." >&2; exit 1; }
}
need curl
need unzip
need tar

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

# ---- SVOX Pico sources (compiled into an engine APK by the legacy build) --------------------

fetch_pico_sources() {
    if [ -f "$PICO_CPP_DIR/picoapi.h" ] && [ -f "$PICO_VOICE_DIR/en-GB_ta.bin" ]; then
        echo "Pico sources already present, skipping."
        return 0
    fi
    echo "Downloading SVOX Pico sources (~12 MB)..."
    _tmp=$(mktemp -d -t pico.XXXXXX)
    curl -fL --retry 3 --progress-bar -o "$_tmp/pico.tar.gz" "$PICO_SRC_URL"
    tar -xzf "$_tmp/pico.tar.gz" -C "$_tmp"

    mkdir -p "$PICO_CPP_DIR" "$PICO_VOICE_DIR"
    cp "$_tmp"/picotts-master/pico/lib/*.c "$_tmp"/picotts-master/pico/lib/*.h "$PICO_CPP_DIR/"
    # Two English voices, about 1 MB each.
    cp "$_tmp"/picotts-master/pico/lang/en-GB_ta.bin \
       "$_tmp"/picotts-master/pico/lang/en-GB_kh0_sg.bin \
       "$_tmp"/picotts-master/pico/lang/en-US_ta.bin \
       "$_tmp"/picotts-master/pico/lang/en-US_lh0_sg.bin "$PICO_VOICE_DIR/"
    rm -rf "$_tmp"
    echo "Pico sources installed at $PICO_CPP_DIR"
}

# ---- Bundled TTS engines, per flavour -------------------------------------------------------

case "$FLAVOUR" in
    full)
        download_to "$FULL_ENGINE_URL" "$FULL_ENGINE_PATH" "sherpa-onnx TTS engine (~80 MB)"
        ;;
    legacy)
        download_to "$RHVOICE_URL" "$RHVOICE_PATH" "RHVoice TTS engine (~15 MB)"
        fetch_pico_sources
        ;;
    "") ;;
    *) echo "Unknown flavour '$FLAVOUR'. Use 'full' or 'legacy'." >&2; exit 2 ;;
esac

echo "Assets ready."
