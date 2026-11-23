# Ultimate Media Manager for computers

Ultimate Media Manager as a program for Windows (it also runs on Linux, which is where it is
tried out while being built). Sign in with the details from your own TV service and watch its
channels, movies and shows - and play the music and videos kept on the computer. The app has no
channels, logins or playlists of its own.

## What it does

- **Sign in** by pasting the message your provider sent, typing a login, or giving a playlist link.
  More than one service can be kept; switch between them in Settings.
- **Live TV**: groups down the side, a country chooser, a box to find a channel, the channel playing
  in the corner with what is on now and next, and a double-click to fill the window.
- **Movies** and **Shows**: posters, a page for each, seasons and episodes, and "carry on from where
  you stopped".
- **Search** across channels, movies and shows at once; **Favourites** and recently watched.
- **Player**: channel up/down, next episode, skip 10 seconds, sound tracks, subtitles, picture shape,
  full screen. Keys: space (pause), F (full screen), arrows (skip, loudness), Page Up/Down or N
  (channel or episode), M (sound off), Esc (back).

- **Music** on this computer: every song, or by album or artist, with the cover pictures; a box
  to find one; shuffle and repeat; and a bar along the bottom with what is playing, whatever
  screen is open. The keyboard's own play, next and previous buttons work.
- **My videos**: the video files on this computer, folder by folder, picked up where they were
  stopped.
- **Any file**: "Open a file", or drop music or video files onto the window.
- The folders looked through (your Music and Videos folders to begin with) are chosen in Settings.
  Song details are read once and remembered; "Look again" finds what is new.

- **Play on a TV**: songs, videos and TV channels can be played on a smart TV on the home network
  instead of on the computer ("Play on a TV" in the side menu). The TV is found by itself, or added
  by its address. Whatever is started after that plays there, with a bar along the bottom to pause,
  skip, jump about, change channel and set the TV's loudness. What the computer was playing
  carries on on the TV from the same place. Files are fetched by the TV from the computer; a
  channel or film from a TV service reaches the TV through the computer, so the address that holds
  the login never leaves it. If something will not start, the app says why in plain words.
  The first time, Windows asks whether to let the app through its firewall: the TV cannot
  collect anything unless the answer is yes.

- **Mashups** (Music, then "Mashups"): pick two to six songs and the app makes one new song out of
  them. It finds each song's beat and key, brings them to one tempo, plans the mix the way a DJ
  would (one song comes in over the other's beat, drops, a fade at the end), and saves the result
  as a music file in an "Ultimate Media Manager" folder inside the Music folder, where it shows up
  with the other songs and starts playing. The length can be left to the app or set from one to
  seven minutes; "Try another version" arranges the same songs differently; "See how it was
  mixed" gives the running order. Songs with a clear, steady beat work best. The working-out is
  the same code as the phone app's; reading the songs and packing the result is done by the
  player engine, so nothing else needs installing.

- **Photo editor** ("Photo editor" in the side menu): the photos on this computer in one grid, or
  any picture opened from a file or dropped on the window. The tools are the phone app's: crop
  (with turn, mirror and set shapes), auto, adjust (brightness, contrast, colour, warmth,
  highlights, shadows, sharpen, fade, vignette), looks, smooth, heal (spot remover), whiten,
  details, lighten, reshape, background blur and draw. The mouse wheel zooms, the right button
  moves the photo, "Before" shows it as it was while held, and every step can be undone
  (Ctrl+Z) and redone (Ctrl+Y). "Save a copy" writes a new JPEG into Pictures > Ultimate Media
  Manager; the original file is never changed. Photos up to 4096 dots on the longer side are
  worked on whole (a phone camera's fit); bigger ones are brought down to that. JPEG, PNG, WebP,
  BMP and GIF open; an iPhone's HEIC files do not.

- **Video editor** ("Video editor" in the side menu): start a video from any videos and photos
  (more can be added later or dropped on the window), and they line up along the bottom, each as
  wide as it is long. A clip can be cut in two at the line, trimmed at either end, moved, copied,
  taken out, sped up or slowed down (its sound keeps its pitch), made louder or quieter, turned,
  mirrored, given a look (Vivid, Warm, Cool, Fade, B&W, Noir, Sepia) and adjusted (brightness,
  contrast, colour). The video's shape can be as the first clip, wide, tall, square or portrait,
  with black or a soft copy of the clip beside clips of another shape. Words (smileys too) can be
  laid over any stretch of it, anywhere on the picture, in any size and colour, and a song can
  run under the whole thing. The big picture shows exactly how the video looks under the line;
  Play makes a quick small copy and plays it. Every change is saved as it is made and can be
  undone. "Save video" writes an MP4 (H.264 with AAC sound) in Full HD, HD or small into
  Videos > Ultimate Media Manager.

- **Two decks** (Music, then "Two decks"): two songs playing at once, mixed by hand. Each deck
  has its own speed (the pitch stays put), pitch, loudness and starting point, shows the song's
  beats a minute (found by the app, or tapped in), can be nudged earlier or later, and can be
  brought onto the other deck's beat with one button. A slider between them fades from one to
  the other, with both at full in the middle. A mix (the two songs and every setting) can be
  kept under a name and opened again.

The phone app has all of the above too. Still only on the phone: Chromecast (on a computer,
smart TVs that take "DLNA" are what works).

## Unlock codes

Like the phone app, this one can be given to anyone and is unlocked with a code: asked for
on first opening, checked over the internet once, and the computer stays unlocked after
that. The check sends the code, the app's version and an anonymous fingerprint of the
computer - nothing else. It is the same code (`licence/Licence.kt`, shared with the phone
app) and the same service; see `../licence-service/README.md`. Until the service's address
is filled in there is no lock.

## The player inside it

The picture and sound are played by the VLC engine (libVLC, from VideoLAN). On Windows it is
packed inside the app, so there is nothing else to install: one download, one program.

- The build fetches VideoLAN's own package for use inside other programs
  (`VideoLAN.LibVLC.Windows`, under the LGPL), checks it against a known fingerprint, and takes
  only the plug-ins listed in [`engine/plugins.txt`](engine/plugins.txt) - what is needed to play
  streams, films and subtitles, and to read songs in and write a mashup out. That keeps the download small and leaves out the parts with
  stricter licences (a check in `DesktopTest` keeps them off the list).
- The engine sits in its own folder inside the app (`app/resources/vlc`) with its licence and a
  notice saying what it is and where its source code is ([`engine/notices`](engine/notices)).
  Settings has a line about it and a button that opens that folder.
- Where no engine is packed (Linux, or a build made without it), the app uses a VLC installed on
  the computer instead, and says so when there is none.

### The video editor's engine

Cutting, joining and writing video is done by FFmpeg, started as a separate program.

- For Windows and Mac the build fetches a ready-made FFmpeg from Maven Central
  (`org.bytedeco:ffmpeg`, a build without FFmpeg's "GPL" and "non-free" parts, so under the
  LGPL version 3), checks it against a known fingerprint, and puts the programs and their own
  libraries in `app/resources/ffmpeg` with the licence texts and a notice
  ([`engine/ffmpeg-notices`](engine/ffmpeg-notices)). It adds about 25 MB to the download.
- Finished videos are packed as H.264 by the Mac's own encoder on a Mac and by OpenH264 (Cisco's,
  BSD licence) on Windows, with an older kind (MPEG-4) as the last resort; previews use whatever
  is quickest.
- On Linux, or in a build without it, the FFmpeg installed on the computer is used, and the
  Video editor page says so when there is none.

Before selling the app, have someone who knows licences look over this arrangement: the notices
are written to meet the LGPL's terms, but this is not legal advice. Video formats such as H.264
and HEVC also have patent licences of their own that can apply to whoever sells a player.

## Getting it

Every change is built by GitHub Actions (`.github/workflows/build-desktop.yml`):

- **UltimateMediaManager-Setup.msi** - the installer. Windows may warn that it comes from an
  unknown publisher, because it is not signed; choose "More info", then "Run anyway".
- **UltimateMediaManager-Windows.zip** - the same app without an installer: unzip it anywhere and
  run `Ultimate Media Manager.exe`.

- **UltimateMediaManager-Mac.dmg** (from `build-desktop-mac.yml`) - the Mac app, for Macs with an
  Apple chip. It uses the VLC app installed on the Mac (free from videolan.org), and because it
  is not signed with an Apple developer account, the Mac asks for it to be allowed the first time
  (right-click the app, "Open").

The build also starts the app on a Windows machine that has no VLC installed (and on a Mac) and
has it play a file, a TV-style stream with Dolby sound, a film with subtitles, a stream that
comes in pieces and songs of every common kind, make a mashup out of two test songs and play
it, open a test photo in the photo editor, use every tool on it and save a copy, and make a
short film in the video editor (cut, speed, look, words, music), play its preview and save it;
and it plays two songs at once on the two decks and brings one onto the other's beat. Snapshots of the window are kept, and a build that cannot do this never becomes a
download.

## Building it yourself

Needs a JDK 21. From this folder:

    ./gradlew run                    # start it
    ./gradlew test                   # the checks
    ./gradlew createDistributable    # the app, in build/compose/binaries/main/app
    ./gradlew packageMsi             # the Windows installer (on Windows)

The TV code (signing in, lists, countries, search) is not copied into this folder: the build takes
it straight from the phone app (`../mashwave/app/src/main/java/.../iptv`), so a fix made there
reaches both. The same goes for playing on a TV, the mashup maker's working-out, the photo
editor's tools and what a video project is made of (the list is in `build.gradle.kts`, under
`copyShared`).

The photo tools were written for Android's drawing classes (`android.graphics.Bitmap`, `Canvas`,
`Paint`...). So that the very same file runs here,
[`src/main/kotlin/android/graphics/Graphics.kt`](src/main/kotlin/android/graphics/Graphics.kt)
provides stand-ins for the few of those classes the tools use, as thin wrappers over the engine
this app already draws its windows with (Skia, which is also what Android draws with).
`PhotoToolsTest` puts every tool through them and checks the dots that come out.

Where things are kept: `%APPDATA%\Ultimate Media Manager` on Windows,
`~/.local/share/Ultimate Media Manager` on Linux. Sign-ins stay on the computer.

### Trying it without a TV service

    "Ultimate Media Manager" --self-test <video file> <folder for results>

starts the app, plays the file (and the other clips in the same folder, fetched through a tiny
web server it runs for itself) and writes `result.txt` and snapshots into the folder. It uses
its own empty settings, so it does not touch a real sign-in.
