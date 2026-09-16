# Matrix Clock

A full-screen Matrix "code rain" clock for Android, with offline motion, clap and voice control.

It is built to live on a charging dock. While the device is charging it holds the screen on, watches
the front camera for movement, and listens for spoken commands — the clock face fades in when
someone is there and fades away to bare code when the room is empty. On battery it does none of
that: no camera, no microphone, no wake lock, just the clock over the rain.

Everything except the weather forecast works with no network connection at all.

---

## Quick start

You need **JDK 17**, the **Android SDK** (API 34), and `curl` + `unzip` on PATH. Nothing else —
the build scripts download the speech model and TTS engine themselves on first run (~50–120 MB,
once).

Pick the build that matches your device. If you are unsure, check with
`adb shell getprop ro.product.cpu.abilist`:

| Device | Script | APK |
|---|---|---|
| 64-bit, anything from the last decade | `build-new-device.sh` | ~132 MB |
| 32-bit, `armeabi-v7a` only | `build-old-device.sh` | ~61 MB |

### Windows

**1. Create `local.properties` in the project root**, pointing at your Android SDK:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

**2. Build.** Git for Windows puts `bash` on PATH, so run the script from PowerShell or CMD:

```
bash ./build-new-device.sh
```

**3. Install onto a connected device:**

```
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

### Linux

**1. Tell Gradle where the SDK is** — either create `local.properties`:

```properties
sdk.dir=/home/<you>/Android/Sdk
```

or export it in your shell instead:

```
export ANDROID_HOME=$HOME/Android/Sdk
```

**2. Build:**

```
./build-new-device.sh
```

**3. Install onto a connected device:**

```
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

### Notes

- For a 32-bit device use `build-old-device.sh` and install
  `app/build/outputs/apk/legacy/debug/app-legacy-debug.apk` instead.
- `./build-new-device.sh debug --install` builds and installs in one step.
- If `adb install` keeps dropping the connection on an older device, use adb over the network
  instead — see [Troubleshooting](#troubleshooting).
- The app needs to be **charging** for motion detection and voice commands to run at all; on
  battery it is just the clock over the rain.

---

## Contents

- [Quick start](#quick-start)
- [Features](#features)
- [Controls](#controls)
- [Voice commands](#voice-commands)
- [Settings](#settings)
- [Building](#building)
- [Build assets](#build-assets)
- [How it works](#how-it-works)
- [Device requirements and caveats](#device-requirements-and-caveats)
- [Troubleshooting](#troubleshooting)
- [Project layout](#project-layout)
- [Credits and licences](#credits-and-licences)

---

## Features

### The rain

A vsync-aligned renderer with an allocation-free draw loop. Column state lives in primitive arrays,
glyphs are drawn straight out of a reusable `CharArray`, and the clock string is rebuilt only when
the displayed second actually changes — so no work is thrown at the garbage collector per frame.

The brightness envelope of each falling stream is computed from the *fractional* distance to the
stream head, so the glow slides continuously between cells rather than stepping once per row. On a
60 Hz panel this renders at a measured 60 fps with 0.0–0.2% janky frames and roughly 3–5 ms of GPU
time per frame.

Thirteen colour schemes are included, from the classic green through amber, red, blue and inverted
white-on-black.

### While charging

- **Motion detection.** The front camera is reduced to a coarse 32×24 luminance grid and compared
  frame to frame at about 6 fps. The frame-wide average change is subtracted before thresholding,
  so auto-exposure drift and gradual light changes do not read as movement. Images are never
  stored, never leave the class that analyses them, and never leave the device.
- **Clock visibility.** Movement fades the clock in. After a minute with no movement it fades back
  out, leaving only the rain.
- **Double clap.** Two claps show the clock for a minute. A clap is detected as a sharp onset far
  above the rolling background level that then collapses back within ~100 ms — requiring the fast
  decay is what separates a clap from speech or music.
- **Voice commands.** See below.

### On battery

The charger state gates everything expensive. Unplugged, the camera and microphone are never
opened, the screen-on flag is released, and the clock simply stays visible.

---

## Controls

| Gesture | Action |
|---|---|
| Triple tap | Cycle to the next colour scheme |
| Hold screen 2 seconds | Open settings |
| Hold Volume Down 3 seconds | Open settings (alternative) |
| Hold Volume Up 1 second | Quit |

Volume keys are swallowed by the app so the system volume UI never appears over the clock.

---

## Voice commands

Recognition is fully offline, using [Vosk](https://alphacephei.com/vosk/) with a four-phrase
grammar. Every command is prefixed with the keyword **`matrix`**, which is what makes it usable in
a room where people are talking — a bare "time" or "code" in conversation triggers nothing.

| Say | Effect |
|---|---|
| **`matrix time`** | Show the clock and speak the time |
| **`matrix code`** | Hide the clock, leaving just the rain |
| **`matrix date`** | Speak the date, sunrise and sunset |
| **`matrix weather`** | Speak current conditions plus any onboard sensor readings |

Example output:

```
"Today is Tuesday, 15 September. Sunrise at 6:34, sunset at 19:15."

"Currently 21 degrees, overcast, feels like 18, wind 12 kilometres per hour,
 humidity 36 percent. On board sensors read pressure 995 hectopascals."
```

**Accuracy.** Commands fire only on Vosk *final* results whose whole utterance equals a known
phrase and whose lowest per-word confidence clears 0.85. Acting on partial results instead — which
are speculative hypotheses that get revised — caused ambient room noise to trigger commands. With
the current gating a 150-second soak in a normal room produced zero false triggers.

**Sunrise and sunset** are computed locally with the NOAA solar equations from a last-known
location. No network and no API key. The implementation was checked against known values for
London, New York, Sydney and Tromsø (including correct polar-day handling) and agrees to within a
minute.

**Weather** is the only feature that goes online, via [Open-Meteo](https://open-meteo.com/), which
needs no API key or account. Onboard humidity, pressure and ambient-temperature sensors are read
where the hardware exists and reported alongside; where a sensor is absent it is simply omitted.
If an onboard humidity sensor exists it is preferred over the forecast value.

---

## Settings

Reached by holding the screen for 2 seconds, or Volume Down for 3 seconds. Changes are written
immediately — there is nothing to save or cancel.

| Setting | Range | Default |
|---|---|---|
| Glyph size | 8–40 dp | 16 dp |
| Glyph density | 20–100% of columns | 100% |
| Glyph speed | 0.2×–3× | 1× |
| Clock size | 4–25% of screen width | 10% |
| Motion detection | on / off | on |
| Speech engine | any installed TTS engine | system default |
| Voice | any voice in that engine | engine default |
| Voice speed | 0.5×–2× | 1× |

Turning motion detection off leaves the clock permanently visible; `matrix code` can still hide it
and a clap or `matrix time` brings it back.

---

## Building

Requirements: JDK 17, Android SDK with API 34, and `curl` + `unzip` on PATH. Gradle finds the SDK
through `local.properties` or `ANDROID_HOME` — see [Quick start](#quick-start). `local.properties`
is machine-specific and gitignored, so a fresh clone needs one written locally.

Two flavours exist because the two architectures need different speech engines. sherpa-onnx sounds
far better, but its 32-bit build is still ~80 MB — almost all of it the ONNX runtime rather than the
voice — which would more than double the size of the 32-bit APK. So the legacy flavour carries
eSpeak NG instead: robotic, but ~10 MB and it runs anywhere.

```bash
./build-new-device.sh      # arm64-v8a,   ~133 MB, sherpa-onnx engine bundled
./build-old-device.sh      # armeabi-v7a,  ~61 MB, eSpeak NG engine bundled
```

Both scripts download any missing build assets first, so a fresh clone builds with no manual setup.
Both accept a build type and an optional install:

```bash
./build-old-device.sh debug --install
./build-new-device.sh release
```

Release builds are minified and resource-shrunk with R8, and are **unsigned** — sign them before
installing. Or drive Gradle directly:

```bash
./gradlew :app:assembleFullDebug
./gradlew :app:assembleLegacyDebug
```

---

## Build assets

Two large binaries are required to build and are deliberately **not** committed to this repository,
which keeps clones small. `fetch-assets.sh` downloads them and is invoked automatically by both
build scripts; it is idempotent, so anything already present is left alone.

```bash
./fetch-assets.sh          # Vosk model only
./fetch-assets.sh full     # also the arm64 sherpa-onnx engine
./fetch-assets.sh legacy   # also the 32-bit eSpeak NG engine
```

| Asset | Size | Destination | Needed by |
|---|---|---|---|
| Vosk small English model | ~39 MB download, 68 MB unpacked | `app/src/main/assets/model-en-us/` | both flavours |
| sherpa-onnx TTS engine APK | ~80 MB | `app/src/full/assets/tts-engine.apk` | `full` only |
| eSpeak NG TTS engine APK | ~10 MB | `app/src/legacy/assets/tts-engine.apk` | `legacy` only |

The Vosk model directory must contain `am/`, `conf/`, `graph/` and `ivector/`. Any other Vosk model
works if you keep the same folder name — see [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models).

A TTS engine is bundled so a device with no speech engine of its own can install one entirely
offline, from inside the app's settings screen. Which engine is decided per flavour by the
`BUNDLED_TTS_PACKAGE` build-config field.

If the file is absent the build still succeeds and runs: `TtsEngineInstaller.isBundled()` detects it
at runtime and the settings screen simply hides the install option.

---

## How it works

### Rendering

`MatrixView` drives itself from a `Choreographer.FrameCallback` rather than a self-rescheduling
`Handler`, so frames are vsync-aligned and stop cleanly when the view detaches or the window is
hidden. Simulation is time-based, so rain speed is independent of frame rate, with the per-frame
delta clamped so a stall cannot teleport the streams.

Glyphs are locked to grid cells and mutate occasionally to shimmer; what moves is the brightness
envelope. Column pitch is the widest glyph advance in the set, because the mix of full-width
katakana and half-width ASCII would otherwise overlap.

### Audio

Both the clap detector and the Vosk recogniser need raw PCM, and only one component can hold an
`AudioRecord` at a time. `AudioEngine` therefore opens the microphone once at 16 kHz mono and hands
the same buffer to each consumer in turn.

### Clock visibility

`ClockPolicy` holds the decision, with inputs arriving from the camera and audio threads and the
render side only reading the result — all shared state is a `@Volatile` primitive, so no locking is
needed either way. The rules compose in order:

1. On battery, or with motion detection off, the clock shows by default.
2. Charging with motion detection on, it shows only while someone is around, fading out after a
   minute with no movement.
3. A double clap or `matrix time` pins it visible for a minute regardless.
4. `matrix code` hides it until something explicitly asks for it back.

---

## Device requirements and caveats

- **minSdk 23** (Android 6.0), targetSdk 34.
- **Permissions:** camera (motion detection), microphone (clap and voice), coarse location
  (sunrise/sunset and weather), internet (weather only). All are requested once at first launch and
  every feature degrades gracefully if denied.
- **Speech output needs a TTS engine on the device.** Most phones ship one; if not, either build can
  install its bundled engine from the settings screen — sherpa-onnx on arm64, eSpeak NG on 32-bit.
- **A device that never had an engine leaves `tts_default_synth` unset**, and in that state Android's
  no-engine `TextToSpeech` constructor fails to initialise even once an engine is installed. The app
  detects this and names an installed engine explicitly; a valid system default is always left alone.
- **Onboard sensors vary widely.** Many phones have a barometer and nothing else; a good number
  have none at all. `matrix weather` reports whatever is actually present and omits the rest.
- The app is landscape (`sensorLandscape`) and immersive, and holds the screen on only while
  charging.

---

## Troubleshooting

**`adb install` fails or the device keeps going offline.** USB to some older devices is unreliable.
ADB over TCP is usually far sturdier:

```bash
adb connect <device-ip>:5555
adb -s <device-ip>:5555 install -r app/build/outputs/apk/legacy/debug/app-legacy-debug.apk
```

**`INSTALL_FAILED_NO_MATCHING_ABIS`.** You are installing the arm64 build on a 32-bit device. Use
`./build-old-device.sh` instead. Check with `adb shell getprop ro.product.cpu.abilist`.

**Voice commands never fire.** Confirm the model loaded — `adb logcat -s VoiceCommands:*` should
show `Vosk ready ... grammar=[...]` shortly after launch. Remember that commands only work while
charging, and that the `matrix` prefix is required.

**Nothing is spoken.** Check that a TTS engine is installed and selected in settings; `Test voice`
on the settings screen is the quickest way to confirm.

---

## Project layout

```
app/src/main/java/com/example/matrixclock/
├── MainActivity.kt          Orchestration, permissions, key handling
├── MatrixView.kt            Rain renderer, clock drawing, touch gestures
├── ClockPolicy.kt           Decides whether the clock face should show
├── ambient/
│   ├── Announcer.kt         Composes and speaks the date and weather
│   ├── AmbientSensors.kt    One-shot pressure / humidity / temperature reads
│   ├── SunTimes.kt          NOAA sunrise and sunset, computed locally
│   └── WeatherService.kt    Open-Meteo client
├── sensing/
│   ├── AudioEngine.kt       Single microphone stream, fanned out
│   ├── ChargingMonitor.kt   Charger state
│   ├── ClapDetector.kt      Double-clap detection
│   └── MotionDetector.kt    Front-camera motion via CameraX
├── settings/
│   ├── Settings.kt          SharedPreferences-backed settings
│   └── SettingsActivity.kt  Settings screen
└── voice/
    ├── VoiceCommands.kt     Vosk wake-word spotting
    ├── Speaker.kt           TextToSpeech wrapper
    └── TtsEngineInstaller.kt Offers the bundled engine when none exists
```

---

## Credits and licences

- [Vosk](https://alphacephei.com/vosk/) — offline speech recognition (Apache-2.0)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — offline TTS engine, arm64 build (Apache-2.0)
- [eSpeak NG](https://github.com/espeak-ng/espeak-ng) — offline TTS engine, 32-bit build (GPL-3.0)
- [Open-Meteo](https://open-meteo.com/) — free weather API, no key required (CC-BY-4.0)
- [CameraX](https://developer.android.com/training/camerax) — camera frame analysis (Apache-2.0)

This project's own licence is in [LICENSE](LICENSE).
