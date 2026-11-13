// Route handlers for the scheduler function.

import type { SupabaseClient } from "@supabase/supabase-js";
import { generateCaptions } from "./ai.ts";
import { errorMessage, HttpError } from "./errors.ts";
import { json, randomState, readJson, requireMember, safeEqual } from "./http.ts";
import { isOAuthKey, OAUTH } from "./platforms.ts";
import { SupabaseStore, supabaseUrl } from "./store.ts";
import type { ConnectedAccount } from "./types.ts";
import { purgeOldMedia, runWorker } from "./worker.ts";

/** The app registers this scheme, so the browser hands control back to it. */
export const APP_SCHEME = "socialscheduler";
export const FUNCTION_NAME = "scheduler";

export function callbackUrl(): string {
  return `${supabaseUrl()}/functions/v1/${FUNCTION_NAME}/oauth/callback`;
}

function appRedirect(params: Record<string, string>): Response {
  const query = new URLSearchParams(params).toString();
  const ok = params.status === "ok";
  return new Response(
    ok
      ? "Connected. You can close this tab and go back to the Social Scheduler app."
      : `Could not connect: ${params.message ?? "unknown error"}. Go back to the Social Scheduler app and try again.`,
    {
      status: 302,
      headers: { Location: `${APP_SCHEME}://oauth?${query}`, "Content-Type": "text/plain; charset=utf-8" },
    },
  );
}

// --- POST /claim-owner -------------------------------------------------------
// Creates the first (owner) account. Refused once the workspace has an owner.
export async function claimOwner(req: Request, db: SupabaseClient): Promise<Response> {
  const body = await readJson(req);
  const email = String(body.email ?? "").trim().toLowerCase();
  const password = String(body.password ?? "");
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new HttpError(400, "Enter a valid email address.");
  if (password.length < 8) throw new HttpError(400, "Choose a password with at least 8 characters.");

  const { count, error: countError } = await db.from("members").select("user_id", { count: "exact", head: true });
  if (countError) throw new Error(`count members: ${countError.message}`);
  if ((count ?? 0) > 0) {
    throw new HttpError(403, "This app already has an owner. Sign in instead.");
  }

  const openWindow = async (until: string | null) => {
    const { error } = await db.from("app_config").update({ signup_window_until: until }).eq("id", true);
    if (error) throw new Error(`update config: ${error.message}`);
  };

  await openWindow(new Date(Date.now() + 30_000).toISOString());
  try {
    let userId: string;
    let created = true;
    const { data, error } = await db.auth.admin.createUser({ email, password, email_confirm: true });
    if (data?.user) {
      userId = data.user.id;
    } else {
      // This address may already have a login in this project from before the
      // scheduler was set up. While nobody owns the workspace yet, claiming it
      // takes over that login and sets its password.
      const { data: existing, error: findError } = await db.rpc("find_user_id_by_email", { p_email: email });
      if (findError || !existing) {
        throw new HttpError(400, error?.message ?? "Could not create the account.");
      }
      const { error: updateError } = await db.auth.admin.updateUserById(String(existing), {
        password,
        email_confirm: true,
      });
      if (updateError) throw new HttpError(400, updateError.message);
      userId = String(existing);
      created = false;
    }

    const { data: claimed, error: claimError } = await db.rpc("claim_owner", { p_user: userId, p_email: email });
    if (claimError || claimed !== true) {
      if (created) await db.auth.admin.deleteUser(userId);
      if (claimError) throw new Error(`claim owner: ${claimError.message}`);
      throw new HttpError(403, "This app already has an owner. Sign in instead.");
    }
  } finally {
    await openWindow(null);
  }
  return json({ ok: true });
}

// --- GET /status ---------------------------------------------------------------
// Tells the sign-in screen whether the workspace still needs an owner.
export async function status(db: SupabaseClient): Promise<Response> {
  const { count, error } = await db.from("members").select("user_id", { count: "exact", head: true });
  if (error) throw new Error(`count members: ${error.message}`);
  return json({ ok: true, has_owner: (count ?? 0) > 0, callback_url: callbackUrl() });
}

// --- POST /oauth/start ---------------------------------------------------------
export async function oauthStart(req: Request, db: SupabaseClient): Promise<Response> {
  const member = await requireMember(req, db);
  const body = await readJson(req);
  const platform = body.platform;
  if (!isOAuthKey(platform)) throw new HttpError(400, "Unknown platform.");

  const store = new SupabaseStore(db);
  const creds = await store.getCredentials(platform);
  if (!creds?.clientId) {
    throw new HttpError(400, `Add your ${OAUTH[platform].label} developer app details first.`);
  }

  const state = randomState();
  const { error } = await db.from("oauth_states").insert({ state, user_id: member.userId, platform });
  if (error) throw new Error(`save oauth state: ${error.message}`);

  return json({ url: OAUTH[platform].authorizeUrl(creds, callbackUrl(), state), callback_url: callbackUrl() });
}

// --- GET /oauth/callback -------------------------------------------------------
export async function oauthCallback(req: Request, db: SupabaseClient): Promise<Response> {
  const url = new URL(req.url);
  const state = url.searchParams.get("state") ?? "";
  const code = url.searchParams.get("code") ?? "";
  const providerError = url.searchParams.get("error_description") ?? url.searchParams.get("error") ?? "";

  if (!state) return appRedirect({ status: "error", message: "Missing sign-in state" });

  // One-time use: delete the handshake as it is read.
  const { data: row, error } = await db.from("oauth_states").delete().eq("state", state)
    .select("user_id, platform, expires_at").maybeSingle();
  if (error) return appRedirect({ status: "error", message: "Could not verify the sign-in" });
  if (!row || Date.parse(String(row.expires_at)) < Date.now()) {
    return appRedirect({ status: "error", message: "This sign-in link has expired - start again from the app" });
  }
  const platform = String(row.platform);
  if (!isOAuthKey(platform)) return appRedirect({ status: "error", message: "Unknown platform" });
  if (providerError || !code) {
    return appRedirect({ status: "error", platform, message: providerError || "Sign-in was cancelled" });
  }

  try {
    const store = new SupabaseStore(db);
    const creds = await store.getCredentials(platform);
    if (!creds) throw new HttpError(400, "The developer app details are missing");

    const accounts = await OAUTH[platform].connect(fetch, creds, code, callbackUrl(), Date.now());
    if (accounts.length === 0) {
      return appRedirect({
        status: "error",
        platform,
        message: platform === "meta"
          ? "No Facebook Pages were shared. On Facebook's screen, tick the Pages and Instagram accounts to use"
          : "No account was returned",
      });
    }
    for (const account of accounts) await saveAccount(db, account, String(row.user_id));
    return appRedirect({ status: "ok", platform, count: String(accounts.length) });
  } catch (e) {
    console.error("oauth callback failed", errorMessage(e));
    return appRedirect({ status: "error", platform, message: errorMessage(e).slice(0, 300) });
  }
}

async function saveAccount(db: SupabaseClient, account: ConnectedAccount, userId: string): Promise<void> {
  const { data, error } = await db.from("social_accounts").upsert({
    platform: account.platform,
    external_id: account.externalId,
    name: account.name,
    username: account.username ?? null,
    avatar_url: account.avatarUrl ?? null,
    status: "active",
    status_detail: null,
    meta: account.meta,
    // When the connection itself runs out (not the short-lived access token).
    token_expires_at: account.refreshToken ? (account.refreshExpiresAt ?? null) : (account.expiresAt ?? null),
    connected_by: userId,
    connected_at: new Date().toISOString(),
  }, { onConflict: "platform,external_id" }).select("id").single();
  if (error || !data) throw new Error(`save account: ${error?.message ?? "no row"}`);

  const { error: tokenError } = await db.from("social_account_tokens").upsert({
    account_id: data.id,
    access_token: account.accessToken,
    refresh_token: account.refreshToken ?? null,
    expires_at: account.expiresAt ?? null,
    refresh_expires_at: account.refreshExpiresAt ?? null,
    scope: account.scope ?? null,
    updated_at: new Date().toISOString(),
  });
  if (tokenError) throw new Error(`save token: ${tokenError.message}`);
}

// --- POST /publish-due ---------------------------------------------------------
// Called every minute by the database cron job (shared secret), and by the app
// right after "Post now" (signed-in member).
export async function publishDue(req: Request, db: SupabaseClient): Promise<Response> {
  const secret = req.headers.get("x-cron-secret");
  let fromCron = false;
  if (secret) {
    const { data, error } = await db.from("app_config").select("cron_secret").eq("id", true).maybeSingle();
    if (error) throw new Error(`load config: ${error.message}`);
    if (!data?.cron_secret || !safeEqual(secret, String(data.cron_secret))) {
      throw new HttpError(401, "Not allowed.");
    }
    fromCron = true;
  } else {
    await requireMember(req, db);
  }

  const store = new SupabaseStore(db);
  const summary = await runWorker(store, {
    fetch,
    now: () => Date.now(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  });

  let purged = 0;
  if (fromCron && new Date().getUTCMinutes() % 30 === 0) {
    try {
      purged = await purgeOldMedia(store);
    } catch (e) {
      console.error("media purge failed", errorMessage(e));
    }
  }
  return json({ ok: true, ...summary, purged });
}

// --- POST /ai/caption ----------------------------------------------------------
export async function aiCaption(req: Request, db: SupabaseClient): Promise<Response> {
  await requireMember(req, db);
  const body = await readJson(req);
  const store = new SupabaseStore(db);
  const creds = await store.getCredentials("anthropic");
  if (!creds) throw new HttpError(400, "Add your Anthropic API key under Tools > AI captions first.");

  const result = await generateCaptions(creds.clientSecret, {
    brief: String(body.brief ?? ""),
    draft: typeof body.draft === "string" ? body.draft : undefined,
    tone: typeof body.tone === "string" ? body.tone : undefined,
    business: typeof body.business === "string" ? body.business : undefined,
    platforms: Array.isArray(body.platforms) ? body.platforms.map(String) : [],
    hashtags: body.hashtags !== false,
    emoji: body.emoji !== false,
  });
  return json(result);
}
