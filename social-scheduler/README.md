# Social Scheduler

An Android app for planning social media posts once and sending them to **Instagram, Facebook Pages, TikTok, YouTube and LinkedIn** at the time you choose. Posts are published by a small server, so they go out even when your phone is off.

This folder is a separate project from the fitness app in the rest of this repository.

## What it does

- **Calendar and queue** of everything scheduled, published, or needing attention.
- **Composer**: photos or video, one caption (or a different one per network), pick accounts, pick a time, or post now.
- **Checks before you schedule**: each network's rules (one video for a Reel, caption limits, video lengths) are checked while you write, so problems show up in the composer and not as a failed post.
- **Reminders**: for anything a network will not let apps post (trending sounds, stickers, TikTok public posts from an unreviewed app), choose "remind me". At the scheduled time the phone notifies you and hands the media and caption to the app you post from.
- **Posting times**: set your regular weekly times; "Next free posting time" fills the first open one.
- **Hashtag groups and saved captions** you can drop in with one tap.
- **AI caption ideas** (optional, needs your own Anthropic API key).
- **Clear failures**: if a post fails, the reason is shown in plain words with a Retry button.

## Installing

1. Download `social-scheduler.apk` (from the Actions run's artifacts, or from Releases once this is merged to `main`).
2. Open it on the phone and allow "install from unknown sources" when asked.
3. Open the app and create the owner account. The first account created owns the app; sign-ups close after that.

Updates install over the top and keep your login, as long as they are signed with the same key (see Signing key below).

## Connecting your accounts

Every network requires you to register a free "developer app" once. The app's **Accounts** tab has a step-by-step guide for each, including the redirect URL to paste in. In short:

| Network | Where | What to expect |
|---|---|---|
| Facebook Pages + Instagram | developers.facebook.com | Works for your own Pages without App Review. Instagram must be a Business or Creator account linked to a Page. Switch the Meta app to **Live**, or posts are visible only to you. |
| YouTube | console.cloud.google.com | Works for your own channel. Publish the OAuth app to **In production**, or access expires every 7 days. 100 uploads a day by default. |
| LinkedIn | linkedin.com/developers | Personal profile works straight away; reconnect every 60 days (the app warns you). Company Pages need LinkedIn's Community Management API approval. |
| TikTok | developers.tiktok.com | The strict one. An unreviewed developer app cannot post publicly, and TikTok says it does not approve tools used only for your own accounts. "Send to inbox" (arrives as a draft in TikTok) may work for your own account in sandbox mode; otherwise use **reminders**, which always work. |

### Limits worth knowing

- Instagram: photos are converted to JPEG automatically. Reels made through any app cannot use Instagram's music library; use a reminder for those.
- TikTok photo posts cannot be automated from this setup (TikTok requires media on a verified domain).
- Videos are uploaded to your Supabase storage first. Supabase's default upload limit is 50 MB per file; the app shrinks larger videos before upload (Tools > Shrink large videos). To allow bigger files, raise *Storage > Settings > Upload file size limit* in the Supabase dashboard.
- Uploaded media is deleted from storage 90 days after a post is published.

## How it is built

```
lib/                    Flutter app (screens, validation rules, media handling)
supabase/migrations/    Database schema with row-level security + the every-minute job
supabase/functions/     One Edge Function: sign-in redirects, publishing, AI captions
supabase/tests/         Schema tests (run against a plain local Postgres)
test/                   App tests (rules + full screen flows on sample data)
```

- The phone uploads media and saves the post. A database job runs every minute and calls the Edge Function only when something is due.
- The function publishes each destination step by step and records progress, so an interrupted run resumes an upload or stops safely. It never blindly re-sends something that may already have been posted.
- Access tokens and developer-app secrets live in tables only the server can read. The key shipped inside the app is Supabase's *publishable* key, which is safe to publish.

### Running the tests

```bash
flutter analyze && flutter test                              # app
cd supabase/functions/scheduler && deno test --allow-all tests/   # publisher, against simulated platform APIs
cd supabase && PGHOST=... PGUSER=postgres tests/run.sh        # database rules
```

The publisher tests use simulated APIs. Posting to the real networks can only be confirmed once you have connected your own accounts.

### Setting up the backend from scratch

Only needed if you move to a different Supabase project.

1. Run `supabase/migrations/0001_init.sql`, then `0002_cron.sql`, in the SQL editor.
2. Deploy `supabase/functions/scheduler` as a function named `scheduler` with JWT verification **off** (the function checks sign-in itself).
3. Point the job at it:
   ```sql
   update public.app_config
      set function_url = 'https://<project-ref>.supabase.co/functions/v1/scheduler';
   ```
4. Put the project URL and publishable key in `lib/config.dart` and rebuild.

### Signing key

Android only installs an update over the existing app when both are signed with the same key, so one private key is used for every build. It is **not** stored in this repository. To let the build workflow use it, add three repository secrets (GitHub > Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `SCHEDULER_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `SCHEDULER_KEYSTORE_PASSWORD` | its password |
| `SCHEDULER_KEY_ALIAS` | the key's alias |

Until those are set, the workflow still builds an APK (signed with a throwaway key) but does not publish a release, because that build could not update an installed copy. Keep a backup of the keystore: if it is lost, updating means uninstalling and reinstalling.

To build on your own computer, create `android/key.properties` (already ignored by git) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

### Maintenance notes

- Flutter is pinned to 3.50.0 in the workflow. The `video_compress` plugin still uses an older Android build setup that Flutter has announced it will stop supporting; when upgrading Flutter, check that plugin first.
- Platform API versions are pinned in `supabase/functions/scheduler/lib/` (Meta Graph API v27.0, LinkedIn 202610). Meta versions last about two years; the LinkedIn header rolls forward automatically.
- Forgot the password? In the Supabase dashboard open Authentication > Users and send a recovery email or set a new password.
