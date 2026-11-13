# Mashwave

A music **and** video player for Android with a built-in two-deck **Mashup Studio**.
Native Android app (Kotlin, Jetpack Compose, Media3 / ExoPlayer). It lives in its own
folder and does not touch the No Excuse Here fitness app in the rest of this repo.

## What it does

**Music**
- Finds every song on the phone: Songs, Albums, Artists and Folders, with sorting and search
- Background playback with lock-screen, notification, headset and Bluetooth controls
- Queue (play next / add to queue / remove), shuffle, repeat, speed 0.5×–2×, sleep timer
- Playlists you create, rename, reorder and delete; Liked, Recently played, Most played
- Equalizer with presets, bass boost and headphone surround
- Reopens where you left off

**Video**
- Video library with thumbnails and folders
- Full-screen player: double-tap either side to skip 10 s, swipe up/down on the left for
  brightness and on the right for volume, fit / zoom / stretch, rotate, lock controls
- Audio-track and subtitle selection, playback speed
- Keeps playing as audio when you leave the video

**Mashup Studio**
- Two decks that play at the same time, each with its own tempo, pitch (in semitones) and volume
- Automatic BPM detection, plus tap-tempo if you want to set it by hand
- "Match tempo" brings one deck to the other's beat
- Crossfader (the middle is both songs at full volume), start points, restart together
- Save mashups by name and reopen them later

**Opens almost anything**
- "Open with Mashwave" from any file manager, browser or chat app
- Open a file with the system picker, or paste a network stream address
- Formats: MP3, AAC/M4A, FLAC, OGG Vorbis, Opus, WAV, AMR, MIDI, MP4, MKV, WebM, MOV,
  AVI, TS, FLV, 3GP, plus HLS, DASH, SmoothStreaming and RTSP streams
- Bundled FFmpeg audio decoders add AC3, E-AC3, DTS, TrueHD and ALAC, which most phones
  can't decode by themselves (common in MKV movie files)

Video pictures are decoded by the phone's own hardware, so very old or unusual video
codecs (WMV, RealVideo, 10-bit H.264) may not play on every phone.

## Getting the APK

**From GitHub (nothing to install):** every push that changes this folder runs the
*Build Mashwave APK* workflow. Open the run under the **Actions** tab and download
**mashwave-apk** (unzip it to get `mashwave.apk`). Pushes to `main` also publish it under
**Releases** as a pre-release, with a direct `.apk` download.

**On a PC:** install JDK 17+ and the Android SDK, then from this folder run

```
./gradlew :app:assembleRelease
```

The APK appears at `app/build/outputs/apk/release/app-release.apk`.

## Installing on a phone

Copy the APK to the phone, tap it, and allow "install unknown apps" when asked — normal for
apps outside the Play Store. Needs Android 8.0 or newer. On first launch tap
**Allow access to music & videos**.

### Updates and the signing key

Android only lets a new version install over an old one when both are signed with the same
key. That key is **not** stored in this repo (a signing key should never be public):

- Keep the `mashwave-signing-key.jks` file and its password somewhere safe.
- **GitHub builds:** add two repository secrets (Settings → Secrets and variables → Actions):
  `MASHWAVE_KEYSTORE_BASE64` (the key file, base64-encoded) and `MASHWAVE_KEYSTORE_PASSWORD`.
  The workflow then signs every APK with your key.
- **PC builds:** put the file at `keystore/mashwave-signing-key.jks` and add
  `mashwave.keystore.password=...` to `local.properties` (both are git-ignored).

Without the key the build still succeeds, signed with a throwaway debug key. Such an APK is
fine for a first install, but it cannot be installed over a copy signed with your real key:
Android will ask you to uninstall first, which also removes your playlists and mashups.

## Where things are

```
app/src/main/java/com/noexcusehere/mashwave/
  MainActivity.kt, MashwaveApp.kt      app entry points
  data/                                 library scan (MediaStore), models, saved user data
  playback/PlaybackService.kt           the main player (background service + media session)
  playback/PlayerConnection.kt          what the screens use to control the player
  playback/MashupEngine.kt              the two mashup decks and their background service
  playback/BpmAnalyzer.kt               tempo detection
  playback/AudioFx.kt                   equalizer / bass / surround
  ui/                                   Compose screens, sheets, theme and components
app/src/test/                           unit tests (tempo detection, library grouping, saved data)
```

User data (playlists, likes, history, mashups, sound settings) is stored as JSON in the
app's private storage. Nothing is sent anywhere; the internet permission is only used for
streams you open yourself.

## Licences

- Outfit font: SIL Open Font License 1.1 (`licenses/Outfit-OFL.txt`)
- `org.jellyfin.media3:media3-ffmpeg-decoder` (FFmpeg audio decoders) is GPL-3.0. That is
  fine for personal use; if you ever distribute the app publicly, either release the source
  under GPL-3.0 as well or remove that one dependency from `app/build.gradle.kts` (the app
  still works, just without AC3 / DTS / TrueHD audio).
- AndroidX, Media3, Kotlin: Apache 2.0
