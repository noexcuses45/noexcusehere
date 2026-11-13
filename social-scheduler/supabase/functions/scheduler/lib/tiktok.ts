// TikTok publishing through the Content Posting API.
//
// Two modes:
//   inbox  - the video is delivered to the creator's TikTok inbox as a draft;
//            they add sound/cover and tap Post in TikTok. (scope video.upload)
//   direct - the video is posted straight to the profile. (scope video.publish)
//            Until TikTok has audited the developer app, direct posts are
//            forced to private (SELF_ONLY) and the account must be private.
//
// Supabase storage links cannot be domain-verified with TikTok, so the video
// is relayed through this function in chunks (FILE_UPLOAD).

import { PublishError } from "./errors.ts";
import { asBody, MB, readRange, sourceSize } from "./media.ts";
import type { FetchFn, PublishJob, Publisher, StepContext, StepResult, TokenSet } from "./types.ts";

const API = "https://open.tiktokapis.com";
const CHUNK = 10 * MB;
const SINGLE_UPLOAD_MAX = 20 * MB;
const WAIT_LIMIT_MS = 30 * 60 * 1000;

export const TIKTOK_BASE_SCOPES = ["user.info.basic", "video.upload"];
export const TIKTOK_DIRECT_SCOPE = "video.publish";

const FAIL_REASONS: Record<string, string> = {
  file_format_check_failed: "TikTok does not support this video format. Use MP4 (H.264).",
  duration_check_failed: "The video is too long or too short for this TikTok account.",
  frame_rate_check_failed: "TikTok does not support this video's frame rate (use 23-60 fps).",
  picture_size_check_failed: "TikTok does not support this video's resolution.",
  internal: "TikTok had an internal error. Try again.",
  video_pull_failed: "TikTok could not download the video.",
  publish_cancelled: "The post was cancelled in TikTok.",
  auth_removed: "Access to this TikTok account was removed - reconnect it.",
  spam_risk_too_many_posts: "TikTok's daily posting limit for this account was reached.",
  spam_risk_user_banned_from_posting: "TikTok has blocked this account from posting.",
  spam_risk_text: "TikTok flagged the caption as spam.",
  spam_risk: "TikTok flagged the post as spam.",
};

const ERROR_CODES: Record<string, string> = {
  unaudited_client_can_only_post_to_private_accounts:
    "TikTok only lets un-reviewed developer apps post directly to PRIVATE accounts. Use \"Send to TikTok inbox\" or a reminder instead, or set the account to private.",
  spam_risk_too_many_pending_share:
    "TikTok allows only 5 unfinished inbox drafts per day. Post or discard the drafts waiting in TikTok first.",
  spam_risk_too_many_posts: "TikTok's daily posting limit for this account was reached.",
  spam_risk_user_banned_from_posting: "TikTok has blocked this account from posting.",
  reached_active_user_cap: "This TikTok developer app has reached its daily user limit.",
  privacy_level_option_mismatch: "That privacy setting is not available for this TikTok account.",
  scope_not_authorized:
    "The TikTok connection is missing a permission. Reconnect TikTok and approve every permission.",
  url_ownership_unverified: "TikTok rejected the media link.",
};

function toTikTokError(status: number, json: any, raw: string): PublishError {
  const code = String(json?.error?.code ?? json?.error ?? "");
  const message = String(json?.error?.message ?? json?.error_description ?? "");
  if (code === "access_token_invalid" || code === "invalid_grant" || status === 401) {
    return new PublishError(`TikTok access has expired or was revoked - reconnect this account. (${code || status})`, {
      reconnect: true,
    });
  }
  if (code === "rate_limit_exceeded" || status === 429 || status >= 500) {
    return new PublishError(`TikTok is busy (${code || status}). Will retry.`, { transient: true });
  }
  const friendly = ERROR_CODES[code];
  if (friendly) return new PublishError(friendly, { reconnect: code === "scope_not_authorized" });
  return new PublishError(
    `TikTok error: ${message || code || raw.slice(0, 200) || `HTTP ${status}`}${code && message ? ` (${code})` : ""}`,
  );
}

async function tiktok(
  fetchFn: FetchFn,
  path: string,
  token: string,
  body: unknown,
  opts: { commit?: boolean } = {},
): Promise<any> {
  let res: Response;
  try {
    res = await fetchFn(`${API}${path}`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json; charset=UTF-8",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    const msg = `Could not reach TikTok (${e instanceof Error ? e.message : e}).`;
    if (opts.commit) throw new PublishError(msg, { ambiguous: true });
    throw new PublishError(msg, { transient: true });
  }
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  const code = json?.error?.code;
  if (!res.ok || (code && code !== "ok")) {
    throw toTikTokError(res.status, json, text);
  }
  return json?.data ?? {};
}

/** How a file of `size` bytes must be split for TikTok's FILE_UPLOAD. */
export function chunkPlan(size: number): { chunkSize: number; totalChunks: number } {
  if (size <= SINGLE_UPLOAD_MAX) return { chunkSize: size, totalChunks: 1 };
  // The final chunk absorbs the remainder (it may be up to 2x CHUNK - 1).
  return { chunkSize: CHUNK, totalChunks: Math.floor(size / CHUNK) };
}

async function relayUpload(
  fetchFn: FetchFn,
  sourceUrl: string,
  uploadUrl: string,
  size: number,
  mimeType: string,
): Promise<void> {
  const { chunkSize, totalChunks } = chunkPlan(size);
  for (let i = 0; i < totalChunks; i++) {
    const first = i * chunkSize;
    const last = i === totalChunks - 1 ? size - 1 : first + chunkSize - 1;
    const bytes = await readRange(fetchFn, sourceUrl, first, last, size);

    let put: Response;
    try {
      put = await fetchFn(uploadUrl, {
        method: "PUT",
        headers: {
          "Content-Type": mimeType,
          "Content-Length": String(bytes.byteLength),
          "Content-Range": `bytes ${first}-${last}/${size}`,
        },
        body: asBody(bytes),
      });
    } catch (e) {
      throw new PublishError(`The upload to TikTok was interrupted (${e instanceof Error ? e.message : e}).`, {
        transient: true,
      });
    }
    await put.arrayBuffer().catch(() => undefined);
    if (put.status !== 201 && put.status !== 206 && put.status !== 200) {
      throw new PublishError(`TikTok refused the video upload (HTTP ${put.status}).`, {
        transient: put.status >= 500,
      });
    }
  }
}

function tiktokMime(mimeType: string): string {
  if (mimeType === "video/quicktime" || mimeType === "video/webm") return mimeType;
  return "video/mp4";
}

export const tiktokStep: Publisher = async (job, ctx) => {
  const token = job.accessToken;
  const state = job.state;

  if (state.phase === "wait") {
    return await tiktokWait(job, ctx);
  }
  if (state.phase === "uploading") {
    // An earlier run was cut off mid-upload. If TikTok already has the whole
    // file, carry on waiting; otherwise that attempt is dead and we start over.
    try {
      const data = await tiktok(ctx.fetch, "/v2/post/publish/status/fetch/", token, {
        publish_id: String(state.publishId),
      });
      const status = String(data.status ?? "");
      const size = Number(state.size ?? 0);
      const received = Number(data.uploaded_bytes ?? data.uploaded_size ?? 0);
      if (status !== "PROCESSING_UPLOAD" || (size > 0 && received >= size)) {
        return await tiktokWait({ ...job, state: { ...state, phase: "wait" } }, ctx);
      }
    } catch (e) {
      if (e instanceof PublishError && (e.transient || e.reconnect)) throw e;
      // Unknown to TikTok: nothing was published, safe to start again.
    }
  }

  if (job.media.length !== 1 || job.media[0].kind !== "video") {
    throw new PublishError(
      "TikTok auto-posting needs exactly one video. For photo posts, use a reminder and post from the TikTok app.",
    );
  }
  const video = job.media[0];
  const size = await sourceSize(ctx.fetch, video.url, video.sizeBytes);
  const plan = chunkPlan(size);
  const sourceInfo = {
    source: "FILE_UPLOAD",
    video_size: size,
    chunk_size: plan.chunkSize,
    total_chunk_count: plan.totalChunks,
  };

  let init: any;
  let username = job.account.username ?? undefined;
  const direct = job.postType === "direct";
  if (direct) {
    const creator = await tiktok(ctx.fetch, "/v2/post/publish/creator_info/query/", token, undefined);
    if (creator.creator_username) username = String(creator.creator_username);
    const allowed: string[] = Array.isArray(creator.privacy_level_options) ? creator.privacy_level_options : [];
    const wanted = String(job.options.privacy_level ?? "");
    let privacy = wanted;
    if (!wanted) {
      privacy = allowed.includes("SELF_ONLY") ? "SELF_ONLY" : (allowed[0] ?? "SELF_ONLY");
    } else if (allowed.length > 0 && !allowed.includes(wanted)) {
      throw new PublishError(
        `TikTok does not allow "${wanted}" for this account right now. Allowed: ${allowed.join(", ")}.`,
      );
    }
    init = await tiktok(ctx.fetch, "/v2/post/publish/video/init/", token, {
      post_info: {
        title: job.caption.slice(0, 2200),
        privacy_level: privacy,
        disable_duet: job.options.disable_duet === true || creator.duet_disabled === true,
        disable_comment: job.options.disable_comment === true || creator.comment_disabled === true,
        disable_stitch: job.options.disable_stitch === true || creator.stitch_disabled === true,
      },
      source_info: sourceInfo,
    });
  } else {
    init = await tiktok(ctx.fetch, "/v2/post/publish/inbox/video/init/", token, { source_info: sourceInfo });
  }

  const publishId = String(init.publish_id ?? "");
  const uploadUrl = String(init.upload_url ?? "");
  if (!publishId || !uploadUrl) throw new PublishError("TikTok did not start the upload.", { transient: true });

  const startedAt = ctx.now();
  await ctx.checkpoint({ phase: "uploading", publishId, direct, username, size, startedAt });
  await relayUpload(ctx.fetch, video.url, uploadUrl, size, tiktokMime(video.mimeType));
  return {
    kind: "wait",
    state: { phase: "wait", publishId, direct, username, startedAt },
    retryInSec: 10,
  };
};

async function tiktokWait(job: PublishJob, ctx: StepContext): Promise<StepResult> {
  const state = job.state;
  const data = await tiktok(ctx.fetch, "/v2/post/publish/status/fetch/", job.accessToken, {
    publish_id: String(state.publishId),
  });
  const status = String(data.status ?? "");

  if (status === "FAILED") {
    const reason = String(data.fail_reason ?? "");
    throw new PublishError(FAIL_REASONS[reason] ?? `TikTok could not publish the video (${reason || "unknown"}).`, {
      reconnect: reason === "auth_removed",
    });
  }
  if (status === "SEND_TO_USER_INBOX") {
    return {
      kind: "published",
      externalId: String(state.publishId),
      note: "Sent to your TikTok inbox. Open TikTok, tap the notification and press Post to finish.",
    };
  }
  if (status === "PUBLISH_COMPLETE") {
    const ids = data.publicaly_available_post_id ?? data.publicly_available_post_id ?? [];
    const postId = Array.isArray(ids) && ids.length > 0 ? String(ids[0]) : undefined;
    const username = typeof state.username === "string" ? state.username : job.account.username;
    return {
      kind: "published",
      externalId: postId ?? String(state.publishId),
      permalink: postId && username ? `https://www.tiktok.com/@${username}/video/${postId}` : undefined,
      note: postId ? undefined : "Posted. It may be private or still in TikTok's review.",
    };
  }
  const started = typeof state.startedAt === "number" ? state.startedAt : ctx.now();
  if (ctx.now() - started > WAIT_LIMIT_MS) {
    throw new PublishError("TikTok took too long to process the video. Check TikTok before retrying.");
  }
  return { kind: "wait", state, retryInSec: 15 };
}

// ---------------------------------------------------------------------------
// OAuth
// ---------------------------------------------------------------------------

export function tiktokAuthorizeUrl(
  clientKey: string,
  redirectUri: string,
  state: string,
  directPost: boolean,
): string {
  const scopes = [...TIKTOK_BASE_SCOPES];
  if (directPost) scopes.push(TIKTOK_DIRECT_SCOPE);
  const url = new URL("https://www.tiktok.com/v2/auth/authorize/");
  url.searchParams.set("client_key", clientKey);
  url.searchParams.set("scope", scopes.join(","));
  url.searchParams.set("response_type", "code");
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("state", state);
  return url.toString();
}

export interface TikTokTokens extends TokenSet {
  accessToken: string;
  refreshToken: string;
  expiresAt: string;
  refreshExpiresAt: string | null;
  scope: string;
  openId: string;
}

async function tokenRequest(fetchFn: FetchFn, form: Record<string, string>, now: number): Promise<TikTokTokens> {
  let res: Response;
  try {
    res = await fetchFn(`${API}/v2/oauth/token/`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(form),
    });
  } catch (e) {
    throw new PublishError(`Could not reach TikTok (${e instanceof Error ? e.message : e}).`, { transient: true });
  }
  const text = await res.text();
  let json: any = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  if (!res.ok || !json?.access_token) {
    const code = String(json?.error ?? "");
    const desc = String(json?.error_description ?? text.slice(0, 200));
    if (res.status >= 500 || res.status === 429) {
      throw new PublishError(`TikTok is busy (${res.status}).`, { transient: true });
    }
    throw new PublishError(`TikTok sign-in failed: ${desc}${code ? ` (${code})` : ""}`, { reconnect: true });
  }
  const expiresIn = Number(json.expires_in ?? 7200);
  const refreshIn = Number(json.refresh_expires_in ?? 0);
  return {
    accessToken: String(json.access_token),
    refreshToken: String(json.refresh_token ?? ""),
    expiresAt: new Date(now + expiresIn * 1000).toISOString(),
    refreshExpiresAt: refreshIn > 0 ? new Date(now + refreshIn * 1000).toISOString() : null,
    scope: String(json.scope ?? ""),
    openId: String(json.open_id ?? ""),
  };
}

export function tiktokExchangeCode(
  fetchFn: FetchFn,
  creds: { clientId: string; clientSecret: string },
  code: string,
  redirectUri: string,
  now: number,
): Promise<TikTokTokens> {
  return tokenRequest(fetchFn, {
    client_key: creds.clientId,
    client_secret: creds.clientSecret,
    code,
    grant_type: "authorization_code",
    redirect_uri: redirectUri,
  }, now);
}

/** Refresh tokens rotate: always store the returned refresh token. */
export function tiktokRefresh(
  fetchFn: FetchFn,
  creds: { clientId: string; clientSecret: string },
  refreshToken: string,
  now: number,
): Promise<TikTokTokens> {
  return tokenRequest(fetchFn, {
    client_key: creds.clientId,
    client_secret: creds.clientSecret,
    grant_type: "refresh_token",
    refresh_token: refreshToken,
  }, now);
}

export async function tiktokUserInfo(
  fetchFn: FetchFn,
  token: string,
): Promise<{ openId: string; displayName: string; avatarUrl?: string }> {
  const res = await fetchFn(`${API}/v2/user/info/?fields=open_id,display_name,avatar_url`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const text = await res.text();
  let json: any = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  const code = json?.error?.code;
  if (!res.ok || (code && code !== "ok")) throw toTikTokError(res.status, json, text);
  const user = json?.data?.user ?? {};
  return {
    openId: String(user.open_id ?? ""),
    displayName: String(user.display_name ?? "TikTok"),
    avatarUrl: user.avatar_url ? String(user.avatar_url) : undefined,
  };
}
