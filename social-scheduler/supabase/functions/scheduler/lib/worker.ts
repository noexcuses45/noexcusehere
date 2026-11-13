// The publishing worker: claims due targets one at a time and drives each
// platform's state machine. It talks to the database through `Store`, so the
// whole thing can be exercised in tests with an in-memory store and a fake
// fetch.

import { errorMessage, PublishError } from "./errors.ts";
import { PLATFORMS, type Refresher } from "./platforms.ts";
import type { CredentialKey, Credentials, FetchFn, MediaItem, Platform, Publisher, PublishJob } from "./types.ts";

export interface TargetRow {
  id: string;
  post_id: string;
  platform: string;
  account_id: string | null;
  mode: string;
  post_type: string;
  caption_override: string | null;
  options: Record<string, unknown> | null;
  status: string;
  attempts: number;
  state: Record<string, unknown> | null;
}

export interface PostRow {
  id: string;
  caption: string;
  link_url: string | null;
  status: string;
  scheduled_at: string | null;
}

export interface MediaRow {
  id: string;
  kind: "image" | "video";
  storage_path: string | null;
  mime_type: string;
  size_bytes: number;
  width: number | null;
  height: number | null;
  position: number;
}

export interface AccountRow {
  id: string;
  platform: string;
  external_id: string;
  username: string | null;
  status: string;
  meta: Record<string, unknown> | null;
}

export interface TokenRow {
  access_token: string;
  refresh_token: string | null;
  expires_at: string | null;
  refresh_expires_at: string | null;
}

export interface Store {
  claimDueTargets(limit: number): Promise<TargetRow[]>;
  getPost(id: string): Promise<PostRow | null>;
  getMedia(postId: string): Promise<MediaRow[]>;
  getAccount(id: string): Promise<AccountRow | null>;
  getToken(accountId: string): Promise<TokenRow | null>;
  saveToken(accountId: string, token: TokenRow): Promise<void>;
  updateTarget(id: string, patch: Record<string, unknown>): Promise<void>;
  setAccountStatus(id: string, status: "active" | "needs_reconnect", detail: string | null): Promise<void>;
  signedUrl(path: string, expiresInSec: number): Promise<string>;
  getMaxLateMinutes(): Promise<number>;
  getCredentials(platform: CredentialKey): Promise<Credentials | null>;
  mediaToPurge(limit: number): Promise<{ media_id: string; storage_path: string }[]>;
  removeMedia(paths: string[]): Promise<void>;
  clearMediaPaths(ids: string[]): Promise<void>;
}

export interface WorkerDeps {
  fetch: FetchFn;
  now: () => number;
  sleep: (ms: number) => Promise<void>;
  /** Wall-clock budget for one run. */
  budgetMs?: number;
  publishers?: Partial<Record<Platform, Publisher>>;
  refreshers?: Partial<Record<Platform, Refresher>>;
}

export interface RunSummary {
  processed: number;
  published: number;
  failed: number;
  waiting: number;
  retrying: number;
}

export const MAX_ATTEMPTS = 5;
const BACKOFF_SEC = [30, 120, 300, 900];
const DEFAULT_BUDGET_MS = 50_000;
/** How long one target may keep polling inside a single run. */
const INLINE_WAIT_MS = 25_000;
const SIGNED_URL_TTL_SEC = 3 * 60 * 60;

type Outcome = "published" | "failed" | "waiting" | "retrying";

export async function runWorker(store: Store, deps: WorkerDeps): Promise<RunSummary> {
  const summary: RunSummary = { processed: 0, published: 0, failed: 0, waiting: 0, retrying: 0 };
  const deadline = deps.now() + (deps.budgetMs ?? DEFAULT_BUDGET_MS);
  const maxLateMinutes = await store.getMaxLateMinutes();

  // Hard stop so a logic error can never spin forever.
  for (let i = 0; i < 200 && deps.now() < deadline; i++) {
    const claimed = await store.claimDueTargets(1);
    if (claimed.length === 0) break;
    const outcome = await processTarget(store, claimed[0], deps, deadline, maxLateMinutes);
    summary.processed++;
    summary[outcome]++;
  }
  return summary;
}

async function processTarget(
  store: Store,
  target: TargetRow,
  deps: WorkerDeps,
  deadline: number,
  maxLateMinutes: number,
): Promise<Outcome> {
  let state: Record<string, unknown> = { ...(target.state ?? {}) };
  const wasInFlight = state.inflight === true;
  delete state.inflight;
  const phase = typeof state.phase === "string" ? state.phase : null;
  const started = phase !== null;

  const fail = async (error: string): Promise<Outcome> => {
    await store.updateTarget(target.id, {
      status: "failed",
      error,
      locked_until: null,
      next_attempt_at: null,
      state: {},
    });
    return "failed";
  };

  let account: AccountRow | null = null;
  try {
    const resumable = phase !== null &&
      (PLATFORMS[target.platform as Platform]?.resumablePhases.includes(phase) ?? false);
    if (wasInFlight && !resumable) {
      return await fail(
        "The server was interrupted while sending this post, so it may or may not have gone out. " +
          "Check the account, then retry if it is missing.",
      );
    }

    const post = await store.getPost(target.post_id);
    if (!post) return await fail("The post no longer exists.");

    if (!started && target.attempts === 0 && post.scheduled_at) {
      const lateMin = (deps.now() - Date.parse(post.scheduled_at)) / 60_000;
      if (lateMin > maxLateMinutes) {
        return await fail(
          `Missed its scheduled time by ${Math.round(lateMin / 60)} hours, so it was not sent. Retry to post it now.`,
        );
      }
    }

    const publisher = deps.publishers?.[target.platform as Platform] ??
      PLATFORMS[target.platform as Platform]?.publish;
    if (!publisher) return await fail(`Automatic posting to "${target.platform}" is not supported.`);

    if (!target.account_id) return await fail("The account this post was meant for has been disconnected.");
    account = await store.getAccount(target.account_id);
    if (!account) return await fail("The account this post was meant for has been disconnected.");

    const accessToken = await freshAccessToken(store, account, deps);

    const mediaRows = await store.getMedia(target.post_id);
    const media: MediaItem[] = [];
    for (const row of mediaRows.sort((a, b) => a.position - b.position)) {
      if (!row.storage_path) {
        return await fail("This post's media has been deleted from storage. Create the post again.");
      }
      media.push({
        kind: row.kind,
        url: await store.signedUrl(row.storage_path, SIGNED_URL_TTL_SEC),
        mimeType: row.mime_type,
        sizeBytes: Number(row.size_bytes ?? 0),
        width: row.width,
        height: row.height,
      });
    }

    const job: PublishJob = {
      targetId: target.id,
      platform: target.platform as Platform,
      postType: target.post_type,
      caption: target.caption_override ?? post.caption ?? "",
      linkUrl: post.link_url,
      options: target.options ?? {},
      state,
      media,
      account: {
        id: account.id,
        externalId: account.external_id,
        username: account.username,
        meta: account.meta ?? {},
      },
      accessToken,
    };

    const ctx = {
      fetch: deps.fetch,
      now: deps.now,
      checkpoint: async (next: Record<string, unknown>) => {
        state = next;
        await store.updateTarget(target.id, { state: { ...next, inflight: true } });
      },
    };

    const inlineUntil = Math.min(deadline, deps.now() + INLINE_WAIT_MS);
    for (;;) {
      // Mark the attempt before anything is sent. If this run dies mid-step,
      // the next one sees the flag and will not blindly send a second time.
      await store.updateTarget(target.id, { state: { ...state, inflight: true } });
      const result = await publisher({ ...job, state }, ctx);

      if (result.kind === "published") {
        await store.updateTarget(target.id, {
          status: "published",
          external_id: result.externalId ?? null,
          permalink: result.permalink ?? null,
          note: result.note ?? null,
          error: null,
          published_at: new Date(deps.now()).toISOString(),
          locked_until: null,
          next_attempt_at: null,
          state: {},
        });
        if (account.status !== "active") await store.setAccountStatus(account.id, "active", null);
        return "published";
      }

      state = result.state;
      const waitMs = Math.max(1, result.retryInSec) * 1000;
      if (deps.now() + waitMs < inlineUntil) {
        // Record progress, then carry on within this run.
        await store.updateTarget(target.id, { state });
        await deps.sleep(waitMs);
        continue;
      }
      await store.updateTarget(target.id, {
        state,
        error: null,
        locked_until: null,
        next_attempt_at: new Date(deps.now() + waitMs).toISOString(),
      });
      return "waiting";
    }
  } catch (e) {
    const message = errorMessage(e);
    const known = e instanceof PublishError;

    if (known && e.reconnect && account) {
      await store.setAccountStatus(account.id, "needs_reconnect", message);
    }
    if (known && !e.transient) {
      return await fail(message);
    }

    // Transient (or unexpected) failure: back off and try again, a few times.
    const attempts = target.attempts + 1;
    if (attempts >= MAX_ATTEMPTS) {
      return await fail(`${message} (gave up after ${attempts} attempts)`);
    }
    const delay = BACKOFF_SEC[Math.min(attempts - 1, BACKOFF_SEC.length - 1)];
    await store.updateTarget(target.id, {
      attempts,
      error: message,
      // Keep progress only if a platform-side object already exists.
      state: typeof state.phase === "string" ? state : {},
      locked_until: null,
      next_attempt_at: new Date(deps.now() + delay * 1000).toISOString(),
    });
    return "retrying";
  }
}

/**
 * Returns a usable access token, refreshing it first when the platform issues
 * short-lived ones (TikTok ~2 h, YouTube ~1 h, LinkedIn 60 days).
 */
async function freshAccessToken(store: Store, account: AccountRow, deps: WorkerDeps): Promise<string> {
  const token = await store.getToken(account.id);
  if (!token?.access_token) {
    throw new PublishError("No access token is stored for this account - reconnect it.", { reconnect: true });
  }
  const platform = PLATFORMS[account.platform as Platform];
  const refresh = deps.refreshers?.[account.platform as Platform] ?? platform?.refresh;
  if (!platform || !token.expires_at) return token.access_token;

  const expiresAt = Date.parse(token.expires_at);
  if (expiresAt - deps.now() > 5 * 60_000) return token.access_token;

  if (!refresh || !token.refresh_token) {
    if (expiresAt > deps.now()) return token.access_token;
    throw new PublishError(`${platform.label} access has expired - reconnect this account.`, { reconnect: true });
  }
  const creds = await store.getCredentials(platform.credentials);
  if (!creds) {
    throw new PublishError(
      `The ${platform.label} developer app details are missing. Add them again under Accounts.`,
    );
  }

  try {
    const fresh = await refresh(deps.fetch, creds, token.refresh_token, deps.now());
    await store.saveToken(account.id, {
      access_token: fresh.accessToken,
      // Some platforms rotate the refresh token on every use; others keep it.
      refresh_token: fresh.refreshToken || token.refresh_token,
      expires_at: fresh.expiresAt ?? null,
      refresh_expires_at: fresh.refreshExpiresAt ?? token.refresh_expires_at,
    });
    return fresh.accessToken;
  } catch (e) {
    // Another run may have refreshed a moment ago; use its token.
    const again = await store.getToken(account.id);
    if (
      again?.access_token && again.access_token !== token.access_token && again.expires_at &&
      Date.parse(again.expires_at) - deps.now() > 60_000
    ) {
      return again.access_token;
    }
    throw e;
  }
}

/** Deletes uploaded media once a post has been published for long enough. */
export async function purgeOldMedia(store: Store, limit = 50): Promise<number> {
  const rows = await store.mediaToPurge(limit);
  if (rows.length === 0) return 0;
  await store.removeMedia(rows.map((r) => r.storage_path));
  await store.clearMediaPaths(rows.map((r) => r.media_id));
  return rows.length;
}
