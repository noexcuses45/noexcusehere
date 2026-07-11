# No Excuse Here

Fitness app for Android: exercise diary (reps + kg), step tracking with watch sync via Health Connect, weekly leaderboard, challenges and badges, daily motivation. Backend runs on Supabase (accounts, data, leaderboard).

## Get your free APK — two options

### Option A: GitHub builds it for you (nothing to install)

1. Create a free account at github.com if you don't have one.
2. Create a new repository (e.g. `noexcusehere`), upload this whole folder.
3. GitHub automatically runs the included build workflow (Actions tab).
4. When it finishes (~5–10 min), open the run and download **no-excuse-here-apk** under Artifacts. Unzip it — that's your APK.

Every time you push a change, a fresh APK is built. Free.

### Option B: Build on your own PC

1. Install [Flutter](https://docs.flutter.dev/get-started/install) and Android Studio.
2. In this folder run:
   ```
   flutter create --platforms android --org com.noexcusehere .
   python scripts/patch_android.py
   flutter pub get
   flutter build apk --release
   ```
3. APK appears at `build/app/outputs/flutter-apk/app-release.apk`.

## Installing the APK on a phone

Copy the APK to the phone (email, USB, Google Drive), tap it, and allow "install from unknown sources" when prompted. This is normal for apps outside the Play Store.

## Watch / step syncing

The app reads steps from **Health Connect** (built into modern Android). The user's watch app (Samsung Health, Garmin Connect, Fitbit, etc.) writes steps into Health Connect; No Excuse Here reads them when you tap **Sync watch** on the home screen. If Health Connect isn't set up, members can add steps manually — the leaderboard works either way.

## Backend

Supabase project: `no-excuse-here` (`zmspizvogocvebmoctbo`, Sydney region) — a dedicated project just for this app. Connection settings are the two constants at the top of `lib/main.dart`.

Email confirmation: by default Supabase requires new users to confirm their email. To let people sign in instantly, turn off "Confirm email" under Authentication → Providers → Email in the Supabase dashboard.

## What's in the code

- `lib/main.dart` — app entry, sign-in gate, bottom navigation
- `lib/screens/` — home (motivation, steps ring, streak), diary, leaderboard, challenges, auth
- `lib/services/steps_service.dart` — Health Connect + saving steps
- `lib/quotes.dart` — motivational quotes (add your own!)
- `lib/theme.dart` — colours and styling
- `scripts/patch_android.py` — adds Health Connect permissions after `flutter create`
- `.github/workflows/build-apk.yml` — the free automatic APK builder
