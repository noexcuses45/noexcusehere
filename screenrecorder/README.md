# No Excuse Recorder — Android screen recorder

A small, native Android app (Kotlin) that records whatever is on your phone screen to an MP4 file.

## What it does

- **Record the whole screen or a single app.** Tap **Start recording**; Android asks what to share. On Android 14+ you can pick "A single app" so only that app is captured.
- **Optional microphone audio** (toggle in the app).
- **Quality presets:** High (full resolution, 12 Mbps), Medium (720p), Low (480p).
- **3 second countdown** so you can get to the screen you want first (toggle on/off).
- **Stop from anywhere:** the persistent notification has a Stop button, there is a **Quick Settings tile** ("Screen record"), or open the app and tap Stop.
- **Recordings list** inside the app: tap to play, long-press to share or delete.
- Files are saved to `Movies/NoExcuseRecorder` and appear in your Gallery / Google Photos automatically.

Works on Android 10 (API 29) and newer, including Android 14/15's stricter media projection rules.

## Getting the APK

### From GitHub (automatic)

Every push that touches `screenrecorder/` runs `.github/workflows/build-screen-recorder.yml`. When it finishes:

- **Releases** tab: download `NoExcuseRecorder.apk` from the newest `recorder-v…` release. This is the easiest route straight from your phone's browser.
- Or open the Actions run and grab the `no-excuse-recorder-apk` artifact.

You can also start a build by hand from the Actions tab (**Run workflow**).

### On your own PC

Requires JDK 17+ and the Android SDK (Android Studio installs it).

```
cd screenrecorder
./gradlew assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`.

## Installing on the phone

Open the APK on the phone (from the browser download, Google Drive, email, USB…) and tap Install. Android will ask you to allow installs from that source the first time.

On first use the app asks for notification permission (so you can see and stop the recording) and, if you enabled the microphone toggle, microphone permission.

## Signing (so updates install over the old version)

Android only installs a newer APK over an existing one when both are signed with the same key. No key is stored in this repository. Pick one:

- **Do nothing.** CI signs each build with a fresh debug key. The app works, but before installing a newer build you must uninstall the old one (your recordings stay in `Movies/NoExcuseRecorder`).
- **Add a release key as GitHub secrets (recommended).** Create a keystore once:

  ```
  keytool -genkeypair -keystore noexcuse-recorder.jks -alias noexcuse -keyalg RSA -keysize 2048 -validity 10000
  base64 -w0 noexcuse-recorder.jks   # on macOS: base64 -i noexcuse-recorder.jks
  ```

  Then in the repository go to Settings → Secrets and variables → Actions and add `KEYSTORE_BASE64` (the base64 text), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`noexcuse`) and `KEY_PASSWORD`. Every build after that is signed with your key. Keep the `.jks` file somewhere safe.

For local builds, set the environment variables `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, or create a git-ignored `signing/signing.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Code map

- `app/src/main/java/com/noexcusehere/screenrecorder/ScreenRecordService.kt` — foreground service: MediaProjection → VirtualDisplay → MediaRecorder → MediaStore.
- `MainActivity.kt` — settings, start/stop, recordings list.
- `RecorderTileService.kt` — Quick Settings tile.
- `Prefs.kt` — saved settings and quality presets.
