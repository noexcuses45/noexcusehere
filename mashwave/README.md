# Ultimate Beats

A music **and** video player for Android that can also make mashups (automatically or by hand
on two decks), edit videos, and play on a TV.
Native Android app (Kotlin, Jetpack Compose, Media3 / ExoPlayer). It lives in its own
folder and does not touch the No Excuse Here fitness app in the rest of this repo.

The app used to be called Mashwave. Only the name people see changed: the folder, the code
package and the app's internal id are still `mashwave`, so the new version installs over the
old one and keeps playlists, likes and mashups.

## What it does

**Music**
- Finds every song on the phone: Songs, Albums, Artists and Folders, with sorting and search
- Background playback with lock-screen, notification, headset and Bluetooth controls
- Queue (play next / add to queue / remove), shuffle, repeat, speed 0.5×–2×, sleep timer
- Playlists you create, rename, reorder and delete; Liked, Recently played, Most played
- Equalizer with presets, bass boost and headphone surround
- Reopens where you left off

**Video**
- Video library with thumbnails, filtered by where a video came from (WhatsApp, Camera,
  Downloads, Google Drive ...) or by folder
- **Watch lists**: playlists for videos. Make one from the Videos tab or a video's menu
  ("Add to watch list"), then play it through, reorder it, rename it
- Full-screen player: double-tap either side to skip 10 s, swipe up/down on the left for
  brightness and on the right for volume, fit / zoom / stretch, rotate, lock controls
- Audio-track and subtitle selection, playback speed
- Keeps playing as audio when you leave the video

**Auto Mashup** (Mashup tab → Auto)
- Pick 2 to 6 songs and press **Create mashup**; the app does the rest and saves one new track
  into `Music/Ultimate Beats`, where it shows up in the library like any other song
- It finds each song's tempo, beat grid, bars and notes, chooses a shared tempo (counting a song
  in half or double time when that needs less stretching) and a pitch shift of at most three
  semitones per song so their notes agree
- The mix is a chain of eight-bar sections: each song first rides on top of the previous one,
  then takes over the rhythm, then hands over to the next
- "Try another version" re-arranges the same songs differently
- Everything runs on the phone; nothing is uploaded

How it layers songs: it cannot pull a voice out of a recording, so instead it splits by
frequency the way a DJ uses the EQ - bass and drums from one song, the middle (voices,
melodies) from the other, with the bass swapped exactly on the bar line. Songs with a clear,
steady beat work best; live recordings, rubato and beatless music don't.

**Manual decks** (Mashup tab → Manual decks)
- Two decks that play at the same time, each with its own tempo, pitch (in semitones) and volume
- Automatic BPM detection, plus tap-tempo if you want to set it by hand
- "Match tempo" brings one deck to the other's beat
- Crossfader (the middle is both songs at full volume), start points, restart together
- Save mashups by name and reopen them later

**Video editor** (Edit tab, next to Mashup)
- "Add clips" shows every photo and video on the phone in one grid, with filters for where
  they came from - Camera, WhatsApp, Messenger, Texts, Screenshots, Downloads - and a search
  box, plus buttons that reach into Google Photos and Google Drive
- A film-strip timeline: drag to move through the video, pinch to zoom, tap a caption or the
  music bar to change it. Tools work on the clip under the white line
- Split, trim, copy, re-order and delete clips; set how long a photo shows
- Speed from 0.25× to 4× (voices keep their pitch), clip volume and mute, rotate, flip
- Filters (Vivid, Warm, Cool, Fade, B&W, Noir, Sepia) and brightness / contrast / colour
- Background music from the app's library, including your own mashups: choose where the song
  starts and how loud it is; it repeats if the video is longer
- Text and emoji, with colour, size, position and timing
- Canvas shapes (original, 16:9, 9:16, 1:1, 4:5) with black bars or a blurred background
- Undo / redo; projects are saved as you go
- **Save** makes an MP4 (480p, 720p or 1080p) in `Movies/Ultimate Beats`; it keeps going in
  the background and the notification shows progress

Not in the editor: transitions, stickers / GIFs, picture-in-picture, cut-out, auto captions,
reverse and freeze-frame.

**Play on a TV** (cast button on Home, Videos and both players)
- **Chromecast / Google TV / "Chromecast built-in" TVs**, through Google Play services
- **Smart TVs and speakers that support DLNA / UPnP** (most Samsung, LG, Sony, Hisense ...)
- **Fire TV Stick:** it has no built-in receiver, so install a free receiver app on it (for
  example AirScreen); the stick then shows up in the list
- While connected, anything you start in the app plays on the TV, the queue moves on by
  itself, and the phone is the remote (play / pause, seek, next / previous, TV volume)
- The TV fetches the file from the phone over your Wi-Fi; phone and TV must be on the same
  network. The TV has to be able to play the file's format itself - the phone does not convert
- The manual two-deck mashup is mixed live on the phone and cannot be cast; saved Auto
  Mashups are ordinary songs and can

**Google Drive, Google Photos and other apps**
- Library has an "add from Google Drive or files" button, Videos has "From Google Photos" and
  "From Google Drive or files" under **+**. They open Android's own pickers; whatever you
  choose is kept in the library (marked with where it came from) until you remove it
- In the file picker, open the side menu to switch to Google Drive, then long-press to select
  several files, or use the menu's "Select all"
- Android lets an app keep a few hundred picked files; beyond that they play but are not kept

What the app can and cannot reach, and why:
- **WhatsApp:** received photos and videos are ordinary files on the phone, so they all show
  up once photo and video access is allowed (unless "media visibility" is off in WhatsApp)
- **Messenger:** only pictures and videos you saved out of a chat. The rest never leaves
  Messenger's own storage and no other app can read it
- **Text messages:** only pictures you saved from a text. Reading messages directly needs the
  SMS permission, which the app deliberately does not ask for: Google Play Protect treats
  sideloaded apps with that permission as suspicious and can refuse to install them, and
  modern chat-style texts (RCS) are not readable that way in any case
- **Google Drive / Google Photos:** only through the pickers above. Listing a whole Drive
  automatically would need Google sign-in registered in a Google Cloud project of your own

**Opens almost anything**
- "Open with Ultimate Beats" from any file manager, browser or chat app
- Open a file with the system picker, or paste a network stream address
- Formats: MP3, AAC/M4A, FLAC, OGG Vorbis, Opus, WAV, AMR, MIDI, MP4, MKV, WebM, MOV,
  AVI, TS, FLV, 3GP, plus HLS, DASH, SmoothStreaming and RTSP streams
- Bundled FFmpeg audio decoders add AC3, E-AC3, DTS, TrueHD and ALAC, which most phones
  can't decode by themselves (common in MKV movie files)

Video pictures are decoded by the phone's own hardware, so very old or unusual video
codecs (WMV, RealVideo, 10-bit H.264) may not play on every phone.

## Getting the APK

**From GitHub (nothing to install):** every push that changes this folder runs the
*Build Ultimate Beats APK* workflow. Open the run under the **Actions** tab and download
**ultimate-beats-apk** (unzip it to get `UltimateBeats.apk`). Pushes to `main` also publish it under
**Releases** as a pre-release, with a direct `.apk` download.

**On a PC:** install JDK 17+ and the Android SDK, then from this folder run

```
./gradlew :app:assembleRelease
```

The APK appears at `app/build/outputs/apk/release/app-release.apk`.

## Installing on a phone

Copy the APK to the phone, tap it, and allow "install unknown apps" when asked — normal for
apps outside the Play Store. Needs Android 8.0 or newer. On first launch tap
**Allow access to music, photos & videos**.

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
  playback/MashupEngine.kt              the two manual mashup decks and their background service
  automash/Analysis.kt                  song analysis: beat tracking, bars, notes
  automash/MixPlanner.kt                the automatic arrangement (tempo, pitch shifts, sections)
  automash/Mixer.kt                     time-stretching, band splitting and mixing
  automash/AudioIo.kt                   decoding songs and encoding the result (AAC)
  automash/AutoMashupMaker.kt           runs the whole thing and saves the file
  playback/BpmAnalyzer.kt               tempo detection
  playback/AudioFx.kt                   equalizer / bass / surround
  data/MediaSources.kt                  works out where a file came from (WhatsApp, Camera, Drive ...)
  ui/Import.kt                          adding files from Google Drive, Google Photos and other apps
  editor/EditorModels.kt, EditorOps.kt  a video project and every edit that can be made to it
  editor/CompositionFactory.kt          turns a project into what the video engine plays / saves
  editor/EditorSession.kt               the open project (undo / redo) and the live preview
  editor/ExportManager.kt               saving the finished MP4 into the phone's library
  cast/MediaServer.kt                   the small web server a TV fetches files from
  cast/Dlna.kt                          finding and controlling smart TVs (UPnP / DLNA)
  cast/Chromecast.kt                    Chromecast through Google Play services
  cast/CastController.kt                the queue that plays on the TV
  KeepAliveService.kt                   keeps a save or a cast session alive in the background
  ui/                                   Compose screens, sheets, theme and components
app/src/test/                           unit tests (tempo and beat detection, the auto-mixer, saved data,
                                        editing rules, the media server, TV control)
```

User data (playlists, likes, history, mashups, video projects, sound settings) is stored as
JSON in the app's private storage. The app itself sends nothing to the internet; the network is
only used for streams you open yourself and, while casting, to talk to the TV on your own Wi-Fi
(the TV fetches the song or video straight from the phone).

One exception to know about: Chromecast support uses Google's own Cast library. It is only
switched on when you open the cast list, and like every app that can cast it may then report
its own usage statistics to Google. It never gets your library. To leave it out entirely,
remove the `play-services-cast-framework` line from `app/build.gradle.kts` together with
`cast/Chromecast.kt`; smart-TV (DLNA) casting keeps working.

## Licences

- Outfit font: SIL Open Font License 1.1 (`licenses/Outfit-OFL.txt`)
- `org.jellyfin.media3:media3-ffmpeg-decoder` (FFmpeg audio decoders) is GPL-3.0. That is
  fine for personal use; if you ever distribute the app publicly, either release the source
  under GPL-3.0 as well or remove that one dependency from `app/build.gradle.kts` (the app
  still works, just without AC3 / DTS / TrueHD audio).
- AndroidX, Media3, Kotlin: Apache 2.0
- Google Play services Cast framework (Chromecast): Android Software Development Kit License
