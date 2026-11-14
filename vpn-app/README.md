# Kestrel VPN

A small Android VPN app. It connects your phone to a **WireGuard** server and sends your
traffic through it. The VPN engine is the official WireGuard implementation for Android
(`com.wireguard.android:tunnel`); this app is the part you see and tap.

It lives in this repository next to the fitness app but is completely separate from it.

## What you need before it does anything

A VPN app is one half of a VPN. The other half is a **server** for it to connect to, and
this app does not come with one. You need a WireGuard configuration from either:

- **a VPN provider that supports WireGuard** — most do (Mullvad, Proton VPN, IVPN,
  Windscribe, Surfshark and others). Look for "WireGuard configuration" or "manual setup"
  in your account page and download the `.conf` file, or
- **your own server** — a home router with WireGuard built in, or a small cloud server set
  up with a tool such as PiVPN or wg-easy. These give you a `.conf` file or a QR code.

A configuration looks like this:

```
[Interface]
PrivateKey = (your private key)
Address = 10.0.0.2/32
DNS = 1.1.1.1

[Peer]
PublicKey = (the server's public key)
Endpoint = vpn.example.com:51820
AllowedIPs = 0.0.0.0/0, ::/0
```

## Installing on your phone

1. Get `app-release.apk` onto the phone (download it, or copy it over).
2. Tap the file. Android asks to allow installing from this source — allow it. This is
   normal for any app that doesn't come from the Play Store.
3. Open **Kestrel VPN**.

Needs Android 8.0 or newer.

## Using it

1. **Add a server** — import the `.conf` file, scan the QR code, or paste the text.
2. **Tap the power button.** The first time, Android shows its own "Connection request"
   dialog; accept it.
3. The button turns teal and says **Connected** once the server has actually answered.
   Until then it says *Connecting…* — if it stays there, the server details are wrong or
   the server can't be reached.

You can save several servers and tap one to switch.

### Always-on and kill switch

Android has these built in for any VPN app. In the app, tap **Open VPN settings**, then the
gear next to Kestrel VPN, and turn on **Always-on VPN** (reconnects by itself, including
after a restart) and **Block connections without VPN** (nothing leaves the phone unless the
VPN is up).

## Privacy

- Your servers and their private keys are stored only in the app's private storage on the
  phone, and are excluded from Android backups.
- The app has no accounts, analytics or ads, and talks to nothing except the VPN server
  you add.
- The camera permission is used only when you choose "Scan QR code".

## Building it

### On GitHub (nothing to install)

Any push that changes `vpn-app/` runs the **Build VPN APK** workflow. Open the run in the
Actions tab and download **kestrel-vpn-apk** under Artifacts; unzip it to get the APK.

The VPN app is not published as a GitHub Release on purpose: the fitness app's updater
installs the APK from this repository's latest release.

### On a computer

With JDK 17+ and the Android SDK installed:

```
cd vpn-app
./gradlew assembleRelease
```

The APK appears at `app/build/outputs/apk/release/app-release.apk`.

### Signing, and why it matters for updates

Android only installs a new version over an old one if both are signed with the same key.
Otherwise the old version has to be uninstalled first, which also removes its saved
servers.

To sign every build with your own key, create one once:

```
keytool -genkeypair -keystore kestrel-release.jks -alias kestrel -keyalg RSA -keysize 3072 -validity 10950
```

Keep that file and its password somewhere safe and never commit them. Then either:

- **locally**: create `vpn-app/keystore.properties` (already git-ignored) with
  `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, or
- **on GitHub**: add repository secrets `VPN_KEYSTORE_BASE64` (output of
  `base64 -w0 kestrel-release.jks`), `VPN_KEYSTORE_PASSWORD`, `VPN_KEY_ALIAS` and
  `VPN_KEY_PASSWORD`.

Without a key configured, the build still produces an installable APK signed with a
throwaway key.

## Tests

```
./gradlew lintDebug testDebugUnitTest
```

The tests cover configuration checking, saved-server storage, the connect/disconnect
logic, and the screens themselves — including a run of the whole app (add a server,
connect, disconnect) with only the WireGuard engine replaced by a stand-in, because the
engine needs a real device. `./gradlew recordRoborazziDebug` also writes a picture of
every screen state to `app/build/screenshots/`.

## Where things are

- `app/src/main/java/com/noexcusehere/vpn/vpn/` — the connection: `VpnController` decides
  what state the tunnel is in; `TunnelBackend` wraps the WireGuard library.
- `.../data/` — saved servers (`ProfileStore`) and configuration checking (`WgConfigParser`).
- `.../ui/` — the screens.
- `app/src/main/res/values/strings.xml` — every piece of text, including the app name.

WireGuard is a registered trademark of Jason A. Donenfeld. This app is not affiliated with
or endorsed by the WireGuard project.
