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

Not in this version: mashups, the photo and video editors, and Chromecast (smart TVs that take
"DLNA" are what works).

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
  streams, films and subtitles. That keeps the download small and leaves out the parts with
  stricter licences (a check in `DesktopTest` keeps them off the list).
- The engine sits in its own folder inside the app (`app/resources/vlc`) with its licence and a
  notice saying what it is and where its source code is ([`engine/notices`](engine/notices)).
  Settings has a line about it and a button that opens that folder.
- Where no engine is packed (Linux, or a build made without it), the app uses a VLC installed on
  the computer instead, and says so when there is none.

Before selling the app, have someone who knows licences look over this arrangement: the notices
are written to meet the LGPL's terms, but this is not legal advice. Video formats such as H.264
and HEVC also have patent licences of their own that can apply to whoever sells a player.

## Getting it

Every change is built by GitHub Actions (`.github/workflows/build-desktop.yml`):

- **UltimateMediaManager-Setup.msi** - the installer. Windows may warn that it comes from an
  unknown publisher, because it is not signed; choose "More info", then "Run anyway".
- **UltimateMediaManager-Windows.zip** - the same app without an installer: unzip it anywhere and
  run `Ultimate Media Manager.exe`.

The build also starts the app on a Windows machine that has no VLC installed and has it play a
file, a TV-style stream with Dolby sound, a film with subtitles and a stream that comes in
pieces. Snapshots of the window are kept, and a build that cannot do this never becomes a
download.

## Building it yourself

Needs a JDK 21. From this folder:

    ./gradlew run                    # start it
    ./gradlew test                   # the checks
    ./gradlew createDistributable    # the app, in build/compose/binaries/main/app
    ./gradlew packageMsi             # the Windows installer (on Windows)

The TV code (signing in, lists, countries, search) is not copied into this folder: the build takes
it straight from the phone app (`../mashwave/app/src/main/java/.../iptv`), so a fix made there
reaches both.

Where things are kept: `%APPDATA%\Ultimate Media Manager` on Windows,
`~/.local/share/Ultimate Media Manager` on Linux. Sign-ins stay on the computer.

### Trying it without a TV service

    "Ultimate Media Manager" --self-test <video file> <folder for results>

starts the app, plays the file (and the other clips in the same folder, fetched through a tiny
web server it runs for itself) and writes `result.txt` and snapshots into the folder. It uses
its own empty settings, so it does not touch a real sign-in.
