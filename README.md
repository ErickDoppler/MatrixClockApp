# Matrix Clock

A full-screen Matrix "code rain" clock for Android, with offline motion, clap and voice control.

It is built to live on a charging dock. While the device is charging it holds the screen on, watches
the front camera for movement, and listens for spoken commands — the clock face fades in when
someone is there and fades away to bare code when the room is empty. On battery it does none of
that: no camera, no microphone, no wake lock, just the clock over the rain.

Everything except the weather forecast works with no network connection at all.

---

## Contents

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

Requirements: JDK 17, Android SDK with API 34, and `curl` + `unzip` on PATH.

Two flavours exist because the bundled TTS engine is an **arm64-only APK**. Shipping it to a 32-bit
device would add ~82 MB that could never be installed there.

```bash
./build-new-device.sh      # arm64-v8a,   ~133 MB, TTS engine bundled
./build-old-device.sh      # armeabi-v7a, ~51 MB,  no bundled engine
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
./fetch-assets.sh                     # Vosk model only
./fetch-assets.sh --with-tts-engine   # also the sherpa-onnx engine
```

| Asset | Size | Destination | Needed by |
|---|---|---|---|
| Vosk small English model | ~39 MB download, 68 MB unpacked | `app/src/main/assets/model-en-us/` | both flavours |
| sherpa-onnx TTS engine APK | ~84 MB | `app/src/full/assets/sherpa-onnx-tts-engine.apk` | `full` only |

The Vosk model directory must contain `am/`, `conf/`, `graph/` and `ivector/`. Any other Vosk model
works if you keep the same folder name — see [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models).

The TTS engine is bundled so a device with no speech engine of its own can install one entirely
offline, from inside the app's settings screen. If the file is absent the `full` flavour still
builds and runs: `TtsEngineInstaller.isBundled()` detects it at runtime and the settings screen
hides the install option.

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
- **Speech output needs a TTS engine on the device.** Most phones ship one. If not, the arm64 build
  can install the bundled sherpa-onnx engine from its settings screen. On a 32-bit device you must
  install any TTS engine yourself — wake words are still recognised, they just are not spoken back.
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
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — offline TTS engine (Apache-2.0)
- [Open-Meteo](https://open-meteo.com/) — free weather API, no key required (CC-BY-4.0)
- [CameraX](https://developer.android.com/training/camerax) — camera frame analysis (Apache-2.0)

This project's own licence is in [LICENSE](LICENSE).
