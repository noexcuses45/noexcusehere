# Firestick Remote

A phone remote for Amazon Fire TV / Firestick that works over your home Wi-Fi.
It has a D-pad (or a swipe pad), Back/Home/Menu, play controls, volume/mute, typing on the TV, and one-tap app shortcuts.

It runs as a small program on a computer (Windows, Mac, Linux) or on an Android phone (via Termux).
That program sends commands to the Firestick using ADB, Android's debug connection. You use the remote from your phone's browser.

---

## Fastest fix first (no setup)

You might not need this project at all:

- **Amazon Fire TV app.** It's free on the iPhone App Store and Google Play. Put your phone on the same Wi-Fi, open the app, choose your Firestick and type the code shown on the TV. It works straight away.
- **Your TV's own remote.** Most TVs support HDMI-CEC (Samsung *Anynet+*, LG *SimpLink*, Sony *Bravia Sync*, Hisense/TCL *CEC*). With it turned on, the TV remote's arrows, OK and Back control the Firestick.
- **A USB keyboard or mouse.** Plug it in through a micro-USB OTG adapter. This works with the stick's power cable connected through a powered OTG hub.

Use one of these to do the one-time setup below.

---

## One-time setup on the Firestick

1. **Turn on Developer Options.** Go to *Settings → My Fire TV → About*. Highlight your device name (e.g. "Fire TV Stick 4K") and press **Select 7 times** until it says "You are now a developer".
2. **Turn on ADB.** Go to *Settings → My Fire TV → Developer Options* and set **ADB debugging** to **ON**.
3. **Find the IP address.** Go to *Settings → My Fire TV → About → Network* and write down the IP address (e.g. `192.168.1.50`).

## Set up the computer

1. Install **Python 3** from python.org. On Windows, tick "Add to PATH".
2. Install **adb** in one of these ways:
   - Windows: download "SDK Platform-Tools" from https://developer.android.com/tools/releases/platform-tools and unzip it **into this folder**, so it's `firestick-remote/platform-tools/adb.exe`.
   - Mac: `brew install android-platform-tools`
   - Linux: `sudo apt install adb`
   - Android phone (Termux): `pkg install python android-tools`
3. In this folder, run:
   ```
   python3 server.py --tv 192.168.1.50
   ```
   On Windows the command is `python` instead of `python3`.
4. **The first time only**, the TV shows *"Allow USB debugging?"*. Tick **Always allow from this computer** and choose **OK**, using the Fire TV app or your TV remote.
5. Open the address it prints (e.g. `http://192.168.1.20:8080`) on your phone. Then use your browser's **Add to Home Screen** so the remote opens like an app.

The IP address is saved, so next time you only need `python3 server.py`.
You can also leave out `--tv` and use **Scan my network** on the remote's connect screen.

## Using it

| Button | Tap | Hold |
|---|---|---|
| Power | Sleep / wake TV | Power key |
| OK | Select | Long-press (context menu) |
| Arrows, ⏪ ⏩, Vol | Single step | Repeats |

- **Swipe pad:** tap *Switch to swipe pad*. Swipe to move, tap to select, and long-press for the menu.
- **Type on TV:** select a text box on the TV first, then type on your phone and press Send. An empty Send presses Enter.
- **Apps:** if an app isn't installed you'll get a message. To add or change shortcuts, edit the `APPS` list at the top of `server.py` (app name → package name).
- **On a laptop:** you can use the arrow keys, Enter (OK), Esc (Back), H (Home), M (Menu) and Space (play/pause).

## Troubleshooting

- **"Press Allow on TV" / unauthorized:** accept the prompt on the TV, then press Connect again.
- **Won't connect:** check that ADB debugging is still ON (some Fire OS updates turn it off). Also check the phone, computer and Firestick are on the same Wi-Fi. Giving the Firestick a fixed IP in your router stops the address changing.
- **Volume does nothing:** Fire TV volume works over HDMI-CEC. Turn on CEC in the TV settings, and under *Settings → Equipment Control* on the Firestick.
