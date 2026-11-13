// YouTube publishing through the YouTube Data API v3 (resumable upload).
//
// The video is relayed from storage in chunks. The upload session survives an
// interrupted run: the next run asks YouTube how many bytes it has and resumes.
//
// YouTube stopped forcing API uploads from un-audited projects to private on
// 2 Nov 2026. If a video still comes back private when another visibility was
// asked for, the result carries a note saying so.

import { PublishError } from "./errors.ts";
import { asBody, MB, readRange, sourceSize } from "./media.ts";
import type { ConnectedAccount, Credentials, FetchFn, PublishJob, Publisher, StepContext, StepResult, TokenSet } from "./types.ts";

const AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const API = "https://www.googleapis.com/youtube/v3";
const UPLOAD_URL = "https://www.googleapis.com/upload/youtube/v3/videos";

export const YOUTUBE_SCOPES = [
  "https://www.googleapis.com/auth/youtube.upload",
  "https://www.googleapis.com/auth/youtube.readonly",
];

/** Must be a multiple of 256 KB. */
const CHUNK = 8 * MB;
const WAIT_LIMIT_MS = 15 * 60 * 1000;

const RECONNECT_REASONS = new Set([
  "authError",
  "expired",
  "required",
  "unauthorized",
  "insufficientPermissions",
  "accountDeleted",
  "accountDisabled",
]);
const TRANSIENT_REASONS = new Set(["rateLimitExceeded", "userRateLimitExceeded", "backendError", "internalError"]);
const FRIENDLY: Record<string, string> = {
  quotaExceeded:
    "Your Google Cloud project has used up today's YouTube upload quota (100 uploads a day by default). It resets at midnight Pacific time.",
  dailyLimitExceeded:
    "Your Google Cloud project has used up today's YouTube API quota. It resets at midnight Pacific time.",
  uploadLimitExceeded: "YouTube says this channel has reached its own daily upload limit. Try again tomorrow.",
  youtubeSignupRequired: "This Google account has no YouTube channel. Create one in YouTube, then reconnect.",
  accessNotConfigured:
    "The YouTube Data API v3 is not switched on in your Google Cloud project. Enable it in the Google Cloud console.",
  channelClosed: "This YouTube channel is closed.",
  channelSuspended: "This YouTube channel is suspended.",
  authenticatedUserAccountClosed: "This YouTube account is closed.",
  authenticatedUserAccountSuspended: "This YouTube account is suspended.",
  invalidTitle: "YouTube rejected the title. Titles must be 1-100 characters and cannot contain < or >.",
  invalidDescription: "YouTube rejected the description (maximum 5000 bytes, no < or >).",
  invalidTags: "YouTube rejected the tags (500 characters in total at most).",
  invalidCategoryId: "YouTube rejected the video category.",
  forbiddenPrivacySetting: "YouTube does not allow that visibility setting for this channel.",
  forbidden: "YouTube refused the request. Reconnect the channel and approve every permission.",
};

function toYouTubeError(status: number, json: any, raw: string): PublishError {
  const err = json?.error;
  const reason = String(err?.errors?.[0]?.reason ?? "");
  const message = String(err?.message ?? raw.slice(0, 200) ?? "");
  const friendly = FRIENDLY[reason];
  if (friendly) return new PublishError(friendly, { code: reason, status });
  if (RECONNECT_REASONS.has(reason) || (status === 401 && !reason)) {
    return new PublishError(`YouTube access has expired or was revoked - reconnect this channel. (${reason || status})`, {
      reconnect: true,
      code: reason,
      status,
    });
  }
  if (status >= 500 || status === 429 || TRANSIENT_REASONS.has(reason)) {
    return new PublishError(`YouTube is busy (${reason || status}). Will retry.`, { transient: true, code: reason, status });
  }
  return new PublishError(`YouTube error: ${message}${reason ? ` (${reason})` : ""}`, { code: reason, status });
}

async function parse(res: Response): Promise<{ json: unknown; text: string }> {
  const text = await res.text();
  try {
    return { json: text ? JSON.parse(text) : null, text };
  } catch {
    return { json: null, text };
  }
}

async function api(fetchFn: FetchFn, url: string, token: string): Promise<any> {
  let res: Response;
  try {
    res = await fetchFn(url, { headers: { Authorization: `Bearer ${token}` } });
  } catch (e) {
    throw new PublishError(`Could not reach YouTube (${e instanceof Error ? e.message : e}).`, { transient: true });
  }
  const { json, text } = await parse(res);
  if (!res.ok) throw toYouTubeError(res.status, json, text);
  return json ?? {};
}

function cleanText(value: string, max: number): string {
  return value.replace(/[<>]/g, "").slice(0, max);
}

/** YouTube limits descriptions by bytes, not characters. */
function limitBytes(value: string, maxBytes: number): string {
  const enc = new TextEncoder();
  let out = value;
  while (enc.encode(out).byteLength > maxBytes) out = out.slice(0, -Math.max(1, Math.ceil(out.length / 50)));
  return out;
}

export function youtubeMetadata(job: PublishJob): Record<string, unknown> {
  const caption = job.caption.trim();
  const explicitTitle = typeof job.options.title === "string" ? job.options.title.trim() : "";
  const firstLine = caption.split("\n").find((l) => l.trim().length > 0)?.trim() ?? "";
  const title = cleanText(explicitTitle || firstLine || "New video", 100) || "New video";
  const privacy = String(job.options.privacy_status ?? "public");
  const tags = Array.isArray(job.options.tags)
    ? job.options.tags.map((t) => cleanText(String(t).replace(/^#/, "").trim(), 60)).filter((t) => t.length > 0)
    : [];
  return {
    snippet: {
      title,
      description: limitBytes(cleanText(caption, 5000), 4900),
      categoryId: String(job.options.category_id ?? "22"),
      ...(tags.length > 0 ? { tags } : {}),
    },
    status: {
      privacyStatus: ["public", "unlisted", "private"].includes(privacy) ? privacy : "public",
      selfDeclaredMadeForKids: job.options.made_for_kids === true,
    },
  };
}

type Progress =
  | { done: true; videoId: string }
  | { done: false; next: number };

/** Parses YouTube's answer to a chunk (or to a "how far are you?" probe). */
async function uploadProgress(res: Response): Promise<Progress> {
  if (res.status === 200 || res.status === 201) {
    const { json } = await parse(res);
    const id = String((json as any)?.id ?? "");
    if (!id) throw new PublishError("YouTube finished the upload but returned no video id.");
    return { done: true, videoId: id };
  }
  if (res.status === 308) {
    await res.arrayBuffer().catch(() => undefined);
    const range = res.headers.get("range");
    const last = range ? Number(range.split("-")[1]) : -1;
    return { done: false, next: Number.isFinite(last) ? last + 1 : 0 };
  }
  const { json, text } = await parse(res);
  if (res.status === 404 || res.status === 410) {
    throw new PublishError("YouTube's upload session expired before the video finished uploading.", {
      transient: true,
    });
  }
  throw toYouTubeError(res.status, json, text);
}

async function relay(
  job: PublishJob,
  ctx: StepContext,
  sessionUrl: string,
  size: number,
  from: number,
): Promise<string> {
  const video = job.media[0];
  let offset = from;
  // 4 GB / 8 MB is 512 chunks; the cap only guards against a protocol loop.
  for (let i = 0; i < 2000; i++) {
    const last = Math.min(offset + CHUNK, size) - 1;
    const bytes = await readRange(ctx.fetch, video.url, offset, last, size);
    let res: Response;
    try {
      res = await ctx.fetch(sessionUrl, {
        method: "PUT",
        headers: {
          "Content-Length": String(bytes.byteLength),
          "Content-Range": `bytes ${offset}-${last}/${size}`,
        },
        body: asBody(bytes),
      });
    } catch (e) {
      throw new PublishError(`The upload to YouTube was interrupted (${e instanceof Error ? e.message : e}).`, {
        transient: true,
      });
    }
    const progress = await uploadProgress(res);
    if (progress.done) return progress.videoId;
    if (progress.next <= offset) {
      throw new PublishError("YouTube did not accept the uploaded data.", { transient: true });
    }
    offset = progress.next;
  }
  throw new PublishError("The upload to YouTube did not complete.", { transient: true });
}

export const youtubeStep: Publisher = async (job, ctx) => {
  const token = job.accessToken;
  const state = job.state;

  if (state.phase === "wait") return await youtubeWait(job, ctx);

  if (job.media.length !== 1 || job.media[0].kind !== "video") {
    throw new PublishError("A YouTube post needs exactly one video.");
  }
  const video = job.media[0];

  if (state.phase === "uploading" && typeof state.sessionUrl === "string") {
    // Resume an interrupted upload from wherever YouTube got to.
    const size = Number(state.size);
    let res: Response;
    try {
      res = await ctx.fetch(state.sessionUrl, {
        method: "PUT",
        headers: { "Content-Length": "0", "Content-Range": `bytes */${size}` },
      });
    } catch (e) {
      throw new PublishError(`Could not reach YouTube (${e instanceof Error ? e.message : e}).`, { transient: true });
    }
    let progress: Progress | null = null;
    try {
      progress = await uploadProgress(res);
    } catch (e) {
      // Session gone: nothing was published, so a fresh upload is safe.
      if (!(e instanceof PublishError) || !e.transient) throw e;
    }
    if (progress) {
      const videoId = progress.done
        ? progress.videoId
        : await relay(job, ctx, state.sessionUrl, size, progress.next);
      return { kind: "wait", state: { phase: "wait", videoId, startedAt: ctx.now() }, retryInSec: 10 };
    }
  }

  const size = await sourceSize(ctx.fetch, video.url, video.sizeBytes);
  let init: Response;
  try {
    init = await ctx.fetch(`${UPLOAD_URL}?uploadType=resumable&part=snippet,status`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json; charset=UTF-8",
        "X-Upload-Content-Length": String(size),
        "X-Upload-Content-Type": video.mimeType.startsWith("video/") ? video.mimeType : "video/*",
      },
      body: JSON.stringify(youtubeMetadata(job)),
    });
  } catch (e) {
    throw new PublishError(`Could not reach YouTube (${e instanceof Error ? e.message : e}).`, { transient: true });
  }
  if (!init.ok) {
    const { json, text } = await parse(init);
    throw toYouTubeError(init.status, json, text);
  }
  await init.arrayBuffer().catch(() => undefined);
  const sessionUrl = init.headers.get("location");
  if (!sessionUrl) throw new PublishError("YouTube did not open an upload session.", { transient: true });

  await ctx.checkpoint({ phase: "uploading", sessionUrl, size, startedAt: ctx.now() });
  const videoId = await relay(job, ctx, sessionUrl, size, 0);
  return { kind: "wait", state: { phase: "wait", videoId, startedAt: ctx.now() }, retryInSec: 10 };
};

async function youtubeWait(job: PublishJob, ctx: StepContext): Promise<StepResult> {
  const state = job.state;
  const videoId = String(state.videoId);
  const permalink = `https://youtu.be/${videoId}`;
  const data = await api(ctx.fetch, `${API}/videos?part=status&id=${encodeURIComponent(videoId)}`, job.accessToken);
  const item = data.items?.[0];
  if (!item) {
    throw new PublishError("YouTube no longer has this video - it may have been removed straight after upload.");
  }
  const status = item.status ?? {};
  const upload = String(status.uploadStatus ?? "");

  if (upload === "failed") {
    throw new PublishError(`YouTube could not process the video (${status.failureReason ?? "unknown reason"}).`);
  }
  if (upload === "rejected") {
    throw new PublishError(`YouTube rejected the video (${status.rejectionReason ?? "unknown reason"}).`);
  }
  if (upload === "deleted") {
    throw new PublishError("The video was deleted on YouTube.");
  }

  const wanted = String(job.options.privacy_status ?? "public");
  const actual = String(status.privacyStatus ?? "");
  const lockedNote = wanted !== "private" && actual === "private"
    ? "YouTube set this video to PRIVATE (a channel upload default, or a restriction on your Google Cloud project). Change the visibility in YouTube Studio."
    : undefined;

  if (upload === "processed") {
    return { kind: "published", externalId: videoId, permalink, note: lockedNote };
  }
  const started = typeof state.startedAt === "number" ? state.startedAt : ctx.now();
  if (ctx.now() - started > WAIT_LIMIT_MS) {
    return {
      kind: "published",
      externalId: videoId,
      permalink,
      note: lockedNote ?? "Uploaded. YouTube is still processing the video.",
    };
  }
  return { kind: "wait", state, retryInSec: 20 };
}

// ---------------------------------------------------------------------------
// OAuth (Google)
// ---------------------------------------------------------------------------

export function googleAuthorizeUrl(clientId: string, redirectUri: string, state: string): string {
  const url = new URL(AUTH_URL);
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", YOUTUBE_SCOPES.join(" "));
  url.searchParams.set("state", state);
  // offline + consent makes Google return a refresh token every time.
  url.searchParams.set("access_type", "offline");
  url.searchParams.set("prompt", "consent");
  return url.toString();
}

async function tokenRequest(fetchFn: FetchFn, form: Record<string, string>, now: number): Promise<TokenSet> {
  let res: Response;
  try {
    res = await fetchFn(TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(form),
    });
  } catch (e) {
    throw new PublishError(`Could not reach Google (${e instanceof Error ? e.message : e}).`, { transient: true });
  }
  const { json, text } = await parse(res);
  const body = json as any;
  if (!res.ok || !body?.access_token) {
    if (res.status >= 500 || res.status === 429) {
      throw new PublishError(`Google is busy (${res.status}).`, { transient: true });
    }
    const code = String(body?.error ?? "");
    const desc = String(body?.error_description ?? text.slice(0, 200));
    throw new PublishError(
      code === "invalid_grant"
        ? "Google access has expired or was revoked - reconnect this channel."
        : `Google sign-in failed: ${desc}${code ? ` (${code})` : ""}`,
      { reconnect: true },
    );
  }
  return {
    accessToken: String(body.access_token),
    refreshToken: body.refresh_token ? String(body.refresh_token) : null,
    expiresAt: new Date(now + Number(body.expires_in ?? 3600) * 1000).toISOString(),
    refreshExpiresAt: body.refresh_token_expires_in
      ? new Date(now + Number(body.refresh_token_expires_in) * 1000).toISOString()
      : null,
    scope: body.scope ? String(body.scope) : null,
  };
}

export function googleRefresh(
  fetchFn: FetchFn,
  creds: Credentials,
  refreshToken: string,
  now: number,
): Promise<TokenSet> {
  return tokenRequest(fetchFn, {
    client_id: creds.clientId,
    client_secret: creds.clientSecret,
    refresh_token: refreshToken,
    grant_type: "refresh_token",
  }, now);
}

export async function youtubeConnect(
  fetchFn: FetchFn,
  creds: Credentials,
  code: string,
  redirectUri: string,
  now: number,
): Promise<ConnectedAccount[]> {
  const tokens = await tokenRequest(fetchFn, {
    code,
    client_id: creds.clientId,
    client_secret: creds.clientSecret,
    redirect_uri: redirectUri,
    grant_type: "authorization_code",
  }, now);
  if (!tokens.refreshToken) {
    throw new PublishError(
      "Google did not grant long-term access. Remove this app at myaccount.google.com/connections, then connect again.",
    );
  }
  const data = await api(fetchFn, `${API}/channels?part=snippet&mine=true`, tokens.accessToken);
  const channels = Array.isArray(data.items) ? data.items : [];
  if (channels.length === 0) {
    throw new PublishError("This Google account has no YouTube channel. Create one in YouTube, then connect again.");
  }
  return channels.map((c: any): ConnectedAccount => {
    const handle = c.snippet?.customUrl ? String(c.snippet.customUrl).replace(/^@/, "") : undefined;
    return {
      platform: "youtube",
      externalId: String(c.id),
      name: String(c.snippet?.title ?? "YouTube channel"),
      username: handle,
      avatarUrl: c.snippet?.thumbnails?.default?.url ? String(c.snippet.thumbnails.default.url) : undefined,
      meta: {},
      ...tokens,
    };
  });
}
