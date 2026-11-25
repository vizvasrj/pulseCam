# PulseCam

Measures heart rate from a fingertip using the phone's rear camera and flash
(photoplethysmography, PPG). Kotlin, Jetpack Compose, CameraX. No network access, no data leaves the device.

## Run it
1. Open this folder in **Android Studio** (Ladybug or newer) and let Gradle sync.
   If it offers to upgrade the Gradle/AGP version, accept.
2. Use a **real phone** (emulators have no flash or pulse signal). Enable USB debugging and press Run.
3. Tap **Start measurement**, grant camera permission, and cover the rear camera **and** the flash
   with your index fingertip. Press gently and stay still for 20 seconds.

## How it works
| File | Role |
|---|---|
| `PpgCamera.kt` | CameraX preview + low-res `ImageAnalysis` (RGBA), torch on, reports mean red/green of the centre of each frame |
| `ppg/PpgProcessor.kt` | Resample to 30 Hz, invert, remove drift, smooth, normalise, detect peaks, median beat interval -> BPM |
| `HeartRateViewModel.kt` | Finger detection, 20 s session, live and final BPM, state for the UI |
| `PulseCamScreen.kt` | Compose UI: preview circle, BPM, live waveform, progress, start/stop |

Run the signal-processing unit tests (synthetic 72/95/120 bpm signals with drift and noise):
`./gradlew test` (or right-click `PpgProcessorTest` in Android Studio).

## Tuning
- Wrong or missing readings: adjust `MIN_RED` / `RED_GREEN_RATIO` in `HeartRateViewModel` for your phone's flash and camera.
- Noisy signal: lower `PEAK_THRESHOLD` or raise `MIN_QUALITY` in `PpgProcessor`.
- Some phones have the flash far from the main lens; try another finger position, or choose a different back camera in `PpgCamera`.

## Limitations
Resting heart rate only. Movement, hard pressing, cold fingers and bright ambient light degrade accuracy.
Not a medical device.

## Getting an APK
- **Android Studio:** Build > Build Bundle(s) / APK(s) > Build APK(s). The file lands in `app/build/outputs/apk/debug/app-debug.apk`.
- **Command line** (Android SDK + Gradle 8.9+ installed): `gradle :app:assembleDebug`.
- **No install at all:** push this folder to a GitHub repo. The workflow in `.github/workflows/build-apk.yml`
  builds it, and the APK appears under the run's **Artifacts** (Actions tab > latest run > PulseCam-debug-apk).

Install the APK on your phone (allow "install unknown apps" for your file manager or browser).
