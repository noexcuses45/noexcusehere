# Ultimate Media Manager for computers

The TV side of Ultimate Media Manager as a program for Windows (it also runs on Linux, which is
where it is tried out while being built). Sign in with the details from your own TV service and
watch its channels, movies and shows. The app has no channels, logins or playlists of its own.

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

Not in this version: music, mashups, the photo and video editors, and sending to a TV.

## VLC is needed

The picture and sound are played by [VLC](https://www.videolan.org/vlc/), which has to be
installed on the computer (the free 64-bit version 3). The app finds it by itself and says so,
with a button to get it, when it is missing. VLC is not included in the download.

## Getting it

Every change is built by GitHub Actions (`.github/workflows/build-desktop.yml`):

- **UltimateMediaManager-Setup.msi** - the installer. Windows may warn that it comes from an
  unknown publisher, because it is not signed; choose "More info", then "Run anyway".
- **UltimateMediaManager-Windows.zip** - the same app without an installer: unzip it anywhere and
  run `Ultimate Media Manager.exe`.

The build also starts the app on a Windows machine, has it find VLC and play a short clip, and
keeps snapshots of the window, so a build that cannot start never gets as far as a download.

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

starts the app, plays the file and writes `result.txt` and snapshots into the folder. It uses its
own empty settings, so it does not touch a real sign-in.
