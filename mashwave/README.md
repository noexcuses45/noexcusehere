# Ultimate Media Manager

A music **and** video player for Android that also plays your own TV service (live channels,
movies, shows), makes mashups (automatically or by hand on two decks), edits videos, and plays
on a TV.
Native Android app (Kotlin, Jetpack Compose, Media3 / ExoPlayer). It lives in its own
folder and does not touch the No Excuse Here fitness app in the rest of this repo.

The app has been called Mashwave and then Ultimate Beats. Only the name and the logo people
see changed: the folder, the code package and the app's internal id are still `mashwave`, so
each new version installs over the old one and keeps playlists, likes, mashups, video projects
and TV sign-ins. Mashups and exported videos made before the latest rename stay in their
`Ultimate Beats` folders and still show in the library; new ones go into
`Ultimate Media Manager` folders.

## What it does

**Music**
- The **Music** tab is the music library. It finds every song on the phone and shows Songs,
  Albums, Artists, Folders and Playlists, with sorting and search
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
- Full-screen player: double-tap either side to skip 10 s (or use the -10 / +10 buttons),
  **hold a finger down to fast-forward** at double speed, **slide sideways to move through the
  video**, swipe up/down on the left for brightness and on the right for volume, fit / zoom /
  stretch, rotate, lock controls
- **Carries on where you stopped:** each video remembers its place (the Videos tab shows how
  far you got with a line under the thumbnail). Reopening it continues from there, with a
  "Start over" button for a few seconds. Videos under a minute, and ones watched to the end,
  start from the beginning. The place is shared between the phone and the TV
- Audio-track and subtitle selection, playback speed
- Keeps playing as audio when you leave the video

**TV** (TV tab, between Videos and Mashup)
- Plays **your own TV service** - the kind TiviMate or IPTV Smarters play. The app has no
  channels of its own; without a subscription from a provider the tab is empty
- Sign in with what the provider gives you: **DNS (URL), username and password** (an "Xtream
  Codes" login), or a **playlist link** (M3U). "Paste" reads a whole copied message or the long
  `get.php?username=...` link and fills the boxes in. Several services can be kept and switched
- If the first address can't be reached, the app tries it the other way round (`http://` when
  `https://` was typed, and the reverse - most services only have `http://`), then any other
  address in the pasted message (providers often give a spare). A failed sign-in says what
  happened: which server, and whether it refused, didn't answer, or was blocked on the phone
  or Wi-Fi
- Some services only answer players they recognise and turn everything else away. Sign-in
  first introduces the app under its own name, then under the two standard Android ones, and
  remembers which was accepted for the lists, the streams and casting. **More** in the sign-in
  form shows the choice (Automatic, Android, VLC, or a name you type) - ask your provider which
  players it allows before naming a different one
- **Live TV, Movies, Shows** as three sections, each with the provider's categories
- **Countries:** one button per country, worked out from how providers name their categories
  ("UK | Sports", "[US] News", "AU: Entertainment", flags ...), the phone's own country first
- **Search** across every channel, movie and show at once; each list also has its own filter
  box and can be sorted (provider's order, A to Z, newest first)
- **Favourites** (the star) and **Recently watched** sit at the top of each section
- Live channels: next / previous channel with the arrows or by sliding sideways, a channel list
  inside the player, and - when the provider has a guide - what is on now and next
- A dropped channel reconnects by itself; it never skips to a different channel on an error
- Movies and episodes use the normal video player: carry on where you stopped, -10 / +10,
  hold to fast-forward, audio and subtitle tracks. A show remembers which episode you were on
- **Multiview** (the grid button on the TV tab, or in the live player): up to four live
  channels on screen at once - one fills the screen, two sit side by side, three or four make
  a grid. Tap a picture to hear it, double-tap to make it big, and use the buttons on each
  picture to change or remove its channel. The channels are remembered for next time. Every
  picture is a stream of its own, so the service has to allow that many at once; a picture the
  service turns away says so. To see all four on a TV, **Show on TV** opens the phone's screen
  mirroring (Smart View / Cast screen): a TV can only be sent one stream by the app, but it
  can mirror the phone showing four
- **Casting** works here too. The stream passes through the phone on its way to the TV, so the
  TV never sees the login, and the remote's arrows change channel. See the notes under
  "Play on a TV"
- **Live TV buffering** (settings button): how much of a channel is kept in reserve - Quick,
  Smoother (about 5 seconds, the default) or Smoothest (about 10). A live stream arrives exactly
  as fast as it plays, so the reserve is what rides out a hiccup on the line; more reserve means
  channels take a little longer to start
- Channel lists are kept on the phone (the tab opens at once) and refreshed when older than
  half a day, or from the settings button

**Auto Mashup** (Mashup tab → Auto)
- Pick 2 to 6 songs, choose how long it should be (Auto, or 1 to 7 minutes) and press
  **Create mashup**; the app does the rest and saves one new track into `Music/Ultimate Media Manager`,
  where it shows up in the library like any other song
- It finds each song's tempo, beat grid, bars and notes, chooses a shared tempo (counting a song
  in half or double time when that needs less stretching) and a pitch shift of at most three
  semitones per song so their notes agree
- It then plans the mix the way a DJ plans a set:
  - **finds each song's hook** - the eight bars that keep coming back - and where the singing is
  - follows an **energy curve**: ease in, build to a first high point, take a breath, build to
    the biggest moment about four fifths of the way through, then land
  - each new song first **sings over the previous song's beat**, then takes over the rhythm
  - at the high points there is a **drop**: the old beat has its bass pulled out for a bar and
    echoes away, and the new song's chorus lands at full power on the first beat
  - under a voice it uses a stretch of the other song with strong drums and little singing, so
    two voices don't fight
  - short mashups of many songs switch to quick four-bar cuts; long ones bring songs back with
    a different part each time
- "See how it was mixed" lists what happens when; "Try another version" re-arranges the same songs
- Everything runs on the phone; nothing is uploaded

How it layers songs: it cannot pull a voice out of a recording, so instead it splits by
frequency the way a DJ uses the EQ - bass and drums from one song, the middle (voices,
melodies) from the other, with the bass swapped exactly on the bar line. Songs with a clear,
steady beat work best; live recordings, rubato and beatless music don't. Songs with very
different tempos have to be stretched a lot to meet, and that can be heard.

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
- **Save** makes an MP4 (480p, 720p or 1080p) in `Movies/Ultimate Media Manager`; it keeps going in
  the background and the notification shows progress

Not in the editor: transitions, stickers / GIFs, picture-in-picture, cut-out, auto captions,
reverse and freeze-frame.

**Photo editor** (inside the video editor: put the white line on a photo and tap **Edit photo**)
- **Crop** with free or fixed shapes (square, 4:5, 9:16, 16:9 ...), quarter turns, mirror and flip
- **Auto** tidy-up in one tap; **Adjust** brightness, contrast, colour, warmth, highlights,
  shadows, sharpen, fade and vignette; twelve **Looks** with a strength slider
- Paint-on brushes: **Smooth** skin (edges stay crisp), **Whiten**, **Details**, **Lighten** -
  each with brush size, strength and a rub-out
- **Heal** removes a spot by rebuilding it from the skin around it; **Reshape** nudges shapes
  with a finger; **Blur** softens everything outside a movable sharp circle; **Draw** freehand
- Two fingers zoom and move the picture, one finger works the tool; hold the compare button to
  see the photo as it was; every tool is kept with the tick or dropped with the cross, and
  there is undo / redo
- **Done** puts the edited photo back on the timeline. It is saved as a copy in the app's own
  storage - the photo in the gallery is never changed - and "Start over" brings the first
  version back

Not in the photo editor: tools that need face-recognition models (hairstyles, make-up, beards,
eye and eyebrow tools) and sky / backdrop / clothes swaps.

**Play on a TV** (cast button on Home, Videos and both players)
- **Chromecast / Google TV / "Chromecast built-in" TVs**, through Google Play services
- **Smart TVs and speakers that support DLNA / UPnP** (most Samsung, LG, Sony, Hisense ...)
- **Fire TV Stick:** it has no built-in receiver, so install a free receiver app on it (for
  example AirScreen); the stick then shows up in the list
- **Music and video are cast separately.** The cast button in the Videos and TV sections sends
  videos and TV to the TV; the one on the music screens sends music. So a show can run on the
  TV while music keeps playing on the phone or a Bluetooth speaker. The cast sheet has a
  switch for each ("Videos and TV", "Music") and the button only lights up in the sections
  whose kind is going to the TV
- While connected, what you start of a switched-on kind plays on the TV, the queue moves on by
  itself, and the phone is the remote (play / pause, back / forward 10 s, seek, next /
  previous, TV volume). Videos carry on from where they were stopped
- The TV fetches the file from the phone over your Wi-Fi; phone and TV must be on the same
  network. The TV has to be able to play the file's format itself - the phone does not convert
- The manual two-deck mashup is mixed live on the phone and cannot be cast; saved Auto
  Mashups are ordinary songs and can
- **TV-tab streams** (channels, movies, episodes) go from the service to the phone and on to
  the TV, so the phone has to stay on the Wi-Fi while they play. Smart TVs get the live channel
  as the broadcast stream it is. A Chromecast cannot play that form, so for a login-based
  service the app asks for the channel as a list of short pieces (HLS) instead - this only
  works when the service offers channels that way. Movies in MKV with surround sound play on
  most TVs but not on every Chromecast

**Google Drive, Google Photos and other apps**
- The music library has an "add from Google Drive or files" button, Videos has "From Google Photos" and
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
- "Open with Ultimate Media Manager" from any file manager, browser or chat app
- Open a file with the system picker, or paste a network stream address
- Formats: MP3, AAC/M4A, FLAC, OGG Vorbis, Opus, WAV, AMR, MIDI, MP4, MKV, WebM, MOV,
  AVI, TS, FLV, 3GP, plus HLS, DASH, SmoothStreaming and RTSP streams
- Bundled FFmpeg audio decoders add AC3, E-AC3, DTS, TrueHD and ALAC, which most phones
  can't decode by themselves (common in MKV movie files)

Video pictures are decoded by the phone's own hardware, so very old or unusual video
codecs (WMV, RealVideo, 10-bit H.264) may not play on every phone.

## Getting the APK

**From GitHub (nothing to install):** every push that changes this folder runs the
*Build Ultimate Media Manager APK* workflow. Open the run under the **Actions** tab and download
**ultimate-media-manager-apk** (unzip it to get `UltimateMediaManager.apk`). Pushes to `main` also publish it under
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
  automash/MixPlanner.kt                the automatic arrangement: tempo, pitch shifts, the DJ-style
                                        running order (hooks, energy curve, blends, drops), length
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
  photo/PhotoOps.kt                     the picture maths: adjust, looks, blur, smooth, heal, crop, reshape
  photo/PhotoTools.kt                   one class per photo tool (what it shows, what a finger does)
  photo/PhotoSession.kt                 the open photo: undo steps, reading it upright, saving the copy
  ui/screens/PhotoEditorScreen.kt       the photo editor's screen
  iptv/Xtream.kt, M3u.kt                reading a TV service's answers and playlist files
  iptv/TvClient.kt, TvNet.kt            asking the service; following forwarded addresses
  iptv/TvRepository.kt, TvStore.kt      services, lists kept on the phone, favourites, what plays
  iptv/TvRegions.kt, TvSearch.kt        country buttons and search
  cast/MediaServer.kt                   the small web server a TV fetches files from
  cast/StreamRelay.kt, Hls.kt           passes a TV-tab stream through the phone to the TV
  cast/Dlna.kt                          finding and controlling smart TVs (UPnP / DLNA)
  cast/Chromecast.kt                    Chromecast through Google Play services
  cast/CastController.kt                the queue that plays on the TV
  KeepAliveService.kt                   keeps a save or a cast session alive in the background
  ui/                                   Compose screens, sheets, theme and components
app/src/test/                           unit tests (tempo and beat detection, the auto-mixer, saved data,
                                        editing rules, the media server, TV control, TV-service
                                        answers and playlists, country detection, the pass-through)
```

User data (playlists, likes, history, mashups, video projects, sound settings) is stored as
JSON in the app's private storage. The app itself sends nothing to the internet; the network is
only used for streams you open yourself, for the TV service you signed in to, and, while
casting, to talk to the TV on your own Wi-Fi (the TV fetches the song or video straight from
the phone).

TV logins are kept in a folder of the app's private storage that Android leaves out of cloud
backups. They are sent to that TV service only - the same requests any IPTV player makes:
its list of channels, movies and shows, the guide, pictures and the streams themselves.

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
