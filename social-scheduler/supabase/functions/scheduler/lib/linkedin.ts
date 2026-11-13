// LinkedIn publishing through the versioned Posts API.
//
// Personal profiles need only the self-serve "Share on LinkedIn" product.
// Company Pages need LinkedIn's "Community Management API" product, which
// LinkedIn has to approve for the developer app first.

import { PublishError } from "./errors.ts";
import { asBody, readAll, readRange, sourceSize } from "./media.ts";
import type { ConnectedAccount, Credentials, FetchFn, PublishJob, Publisher, StepContext, StepResult, TokenSet } from "./types.ts";

const AUTH_URL = "https://www.linkedin.com/oauth/v2/authorization";
const TOKEN_URL = "https://www.linkedin.com/oauth/v2/accessToken";
const API = "https://api.linkedin.com";
/** LinkedIn API versions are monthly and each is supported for about a year. */
export const LINKEDIN_VERSION = "202610";

export const LINKEDIN_MEMBER_SCOPES = ["openid", "profile", "w_member_social"];
export const LINKEDIN_ORG_SCOPES = ["w_organization_social", "r_organization_social", "rw_organization_admin"];

const VIDEO_WAIT_LIMIT_MS = 20 * 60 * 1000;
const MAX_COMMENTARY = 3000;

/**
 * LinkedIn's "little text format" treats these characters as markup; they
 * must be backslash-escaped or the post text is cut off or rejected.
 * Hashtags (#word) and plain URLs are left alone so LinkedIn links them.
 */
export function escapeCommentary(text: string): string {
  return text
    .replace(/[\\|{}@\[\]()<>*_~]/g, (ch) => `\\${ch}`)
    // A lone # that does not start a hashtag would be read as markup.
    .replace(/#(?![\p{L}\p{N}_])/gu, "\\#");
}

function toLinkedInError(status: number, json: any, raw: string): PublishError {
  const message = String(json?.message ?? json?.error_description ?? raw.slice(0, 300) ?? "");
  const code = String(json?.code ?? json?.serviceErrorCode ?? "");
  if (status === 401) {
    return new PublishError("LinkedIn access has expired or was revoked - reconnect this account.", {
      reconnect: true,
    });
  }
  if (status === 403) {
    return new PublishError(
      `LinkedIn refused the request - the developer app is missing a product or permission for this account. ${message}`
        .trim(),
    );
  }
  if (status === 429 || status >= 500) {
    return new PublishError(`LinkedIn is busy (${status}). Will retry.`, { transient: true });
  }
  return new PublishError(`LinkedIn error: ${message || `HTTP ${status}`}${code ? ` (${code})` : ""}`);
}

interface CallOptions {
  method?: "GET" | "POST";
  body?: unknown;
  commit?: boolean;
}

async function rest(
  fetchFn: FetchFn,
  path: string,
  token: string,
  opts: CallOptions = {},
): Promise<{ status: number; headers: Headers; json: any }> {
  let res: Response;
  try {
    res = await fetchFn(`${API}${path}`, {
      method: opts.method ?? "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        "LinkedIn-Version": LINKEDIN_VERSION,
        "X-Restli-Protocol-Version": "2.0.0",
        ...(opts.body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });
  } catch (e) {
    const msg = `Could not reach LinkedIn (${e instanceof Error ? e.message : e}).`;
    if (opts.commit) {
      throw new PublishError(
        `${msg} The post may or may not have gone out - check the account before retrying.`,
        { ambiguous: true },
      );
    }
    throw new PublishError(msg, { transient: true });
  }
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (!res.ok) throw toLinkedInError(res.status, json, text);
  return { status: res.status, headers: res.headers, json: json ?? {} };
}

async function putBytes(
  fetchFn: FetchFn,
  url: string,
  bytes: Uint8Array,
  token: string,
): Promise<Response> {
  let res: Response;
  try {
    res = await fetchFn(url, {
      method: "PUT",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/octet-stream" },
      body: asBody(bytes),
    });
  } catch (e) {
    throw new PublishError(`The upload to LinkedIn was interrupted (${e instanceof Error ? e.message : e}).`, {
      transient: true,
    });
  }
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new PublishError(`LinkedIn refused the upload (HTTP ${res.status}). ${text.slice(0, 200)}`.trim(), {
      transient: res.status >= 500 || res.status === 429,
    });
  }
  return res;
}

async function uploadImage(job: PublishJob, ctx: StepContext, url: string): Promise<string> {
  const init = await rest(ctx.fetch, "/rest/images?action=initializeUpload", job.accessToken, {
    method: "POST",
    body: { initializeUploadRequest: { owner: job.account.externalId } },
  });
  const uploadUrl = String(init.json?.value?.uploadUrl ?? "");
  const image = String(init.json?.value?.image ?? "");
  if (!uploadUrl || !image) throw new PublishError("LinkedIn did not start the image upload.", { transient: true });
  const bytes = await readAll(ctx.fetch, url);
  const res = await putBytes(ctx.fetch, uploadUrl, bytes, job.accessToken);
  await res.arrayBuffer().catch(() => undefined);
  return image;
}

async function uploadVideo(job: PublishJob, ctx: StepContext): Promise<string> {
  const video = job.media[0];
  const size = await sourceSize(ctx.fetch, video.url, video.sizeBytes);
  const init = await rest(ctx.fetch, "/rest/videos?action=initializeUpload", job.accessToken, {
    method: "POST",
    body: {
      initializeUploadRequest: {
        owner: job.account.externalId,
        fileSizeBytes: size,
        uploadCaptions: false,
        uploadThumbnail: false,
      },
    },
  });
  const value = init.json?.value ?? {};
  const videoUrn = String(value.video ?? "");
  const instructions: { uploadUrl: string; firstByte: number; lastByte: number }[] = Array.isArray(
      value.uploadInstructions,
    )
    ? value.uploadInstructions
    : [];
  if (!videoUrn || instructions.length === 0) {
    throw new PublishError("LinkedIn did not start the video upload.", { transient: true });
  }

  const partIds: string[] = [];
  for (const part of instructions) {
    const bytes = await readRange(ctx.fetch, video.url, Number(part.firstByte), Number(part.lastByte), size);
    const res = await putBytes(ctx.fetch, String(part.uploadUrl), bytes, job.accessToken);
    await res.arrayBuffer().catch(() => undefined);
    const etag = res.headers.get("etag");
    if (!etag) throw new PublishError("LinkedIn did not confirm an uploaded video part.", { transient: true });
    partIds.push(etag.replace(/^"|"$/g, ""));
  }

  await rest(ctx.fetch, "/rest/videos?action=finalizeUpload", job.accessToken, {
    method: "POST",
    body: {
      finalizeUploadRequest: {
        video: videoUrn,
        uploadToken: String(value.uploadToken ?? ""),
        uploadedPartIds: partIds,
      },
    },
  });
  return videoUrn;
}

function firstLine(text: string, max: number): string {
  const line = text.split("\n").find((l) => l.trim().length > 0)?.trim() ?? "";
  return line.slice(0, max);
}

async function createPost(
  job: PublishJob,
  ctx: StepContext,
  content: Record<string, unknown> | undefined,
): Promise<StepResult> {
  const commentary = escapeCommentary(job.caption.trim()).slice(0, MAX_COMMENTARY);
  const body: Record<string, unknown> = {
    author: job.account.externalId,
    commentary,
    visibility: "PUBLIC",
    distribution: { feedDistribution: "MAIN_FEED", targetEntities: [], thirdPartyDistributionChannels: [] },
    lifecycleState: "PUBLISHED",
    isReshareDisabledByAuthor: false,
  };
  if (content) body.content = content;

  const res = await rest(ctx.fetch, "/rest/posts", job.accessToken, { method: "POST", body, commit: true });
  const urn = res.headers.get("x-restli-id") ?? String(res.json?.id ?? "");
  return {
    kind: "published",
    externalId: urn || undefined,
    permalink: urn ? `https://www.linkedin.com/feed/update/${urn}/` : undefined,
  };
}

export const linkedinStep: Publisher = async (job, ctx) => {
  const state = job.state;
  const caption = job.caption.trim();

  if (state.phase === "video_wait") {
    const videoUrn = String(state.video);
    const info = await rest(ctx.fetch, `/rest/videos/${encodeURIComponent(videoUrn)}`, job.accessToken);
    const status = String(info.json?.status ?? "");
    if (status === "PROCESSING_FAILED") {
      throw new PublishError("LinkedIn could not process the video. Use an MP4 between 3 seconds and 30 minutes.");
    }
    if (status !== "AVAILABLE") {
      const started = typeof state.startedAt === "number" ? state.startedAt : ctx.now();
      if (ctx.now() - started > VIDEO_WAIT_LIMIT_MS) {
        throw new PublishError("LinkedIn took too long to process the video.");
      }
      return { kind: "wait", state, retryInSec: 15 };
    }
    // Save "ready to post" before posting, so an interrupted run cannot post twice.
    return { kind: "wait", state: { ...state, phase: "post" }, retryInSec: 1 };
  }

  if (state.phase === "post") {
    return await createPost(job, ctx, {
      media: { id: String(state.video), title: firstLine(caption, 100) || "Video" },
    });
  }

  const images = job.media.filter((m) => m.kind === "image");
  const videos = job.media.filter((m) => m.kind === "video");

  if (videos.length > 0) {
    if (job.media.length > 1) {
      throw new PublishError("A LinkedIn video post can only contain one video (no mixed photos).");
    }
    const videoUrn = await uploadVideo(job, ctx);
    return { kind: "wait", state: { phase: "video_wait", video: videoUrn, startedAt: ctx.now() }, retryInSec: 10 };
  }

  if (images.length === 0) {
    if (!caption && !job.linkUrl) throw new PublishError("A LinkedIn post needs text, a link, a photo or a video.");
    if (job.linkUrl) {
      return await createPost(job, ctx, {
        article: { source: job.linkUrl, title: firstLine(caption, 200) || job.linkUrl },
      });
    }
    return await createPost(job, ctx, undefined);
  }

  if (images.length > 20) throw new PublishError("LinkedIn posts can hold at most 20 photos.");
  const ids: string[] = [];
  for (const image of images) ids.push(await uploadImage(job, ctx, image.url));

  if (ids.length === 1) {
    return await createPost(job, ctx, { media: { id: ids[0] } });
  }
  return await createPost(job, ctx, { multiImage: { images: ids.map((id) => ({ id })) } });
};

// ---------------------------------------------------------------------------
// OAuth
// ---------------------------------------------------------------------------

export function linkedinAuthorizeUrl(
  clientId: string,
  redirectUri: string,
  state: string,
  includePages: boolean,
): string {
  const scopes = [...LINKEDIN_MEMBER_SCOPES];
  if (includePages) scopes.push(...LINKEDIN_ORG_SCOPES);
  const url = new URL(AUTH_URL);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("state", state);
  url.searchParams.set("scope", scopes.join(" "));
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
    throw new PublishError(`Could not reach LinkedIn (${e instanceof Error ? e.message : e}).`, { transient: true });
  }
  const text = await res.text();
  let body: any = null;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }
  if (!res.ok || !body?.access_token) {
    if (res.status >= 500 || res.status === 429) {
      throw new PublishError(`LinkedIn is busy (${res.status}).`, { transient: true });
    }
    const desc = String(body?.error_description ?? text.slice(0, 200));
    throw new PublishError(`LinkedIn sign-in failed: ${desc}`, { reconnect: true });
  }
  return {
    accessToken: String(body.access_token),
    refreshToken: body.refresh_token ? String(body.refresh_token) : null,
    expiresAt: new Date(now + Number(body.expires_in ?? 5184000) * 1000).toISOString(),
    refreshExpiresAt: body.refresh_token_expires_in
      ? new Date(now + Number(body.refresh_token_expires_in) * 1000).toISOString()
      : null,
    scope: body.scope ? String(body.scope) : null,
  };
}

export function linkedinRefresh(
  fetchFn: FetchFn,
  creds: Credentials,
  refreshToken: string,
  now: number,
): Promise<TokenSet> {
  return tokenRequest(fetchFn, {
    grant_type: "refresh_token",
    refresh_token: refreshToken,
    client_id: creds.clientId,
    client_secret: creds.clientSecret,
  }, now);
}

export async function linkedinConnect(
  fetchFn: FetchFn,
  creds: Credentials,
  code: string,
  redirectUri: string,
  now: number,
): Promise<ConnectedAccount[]> {
  const tokens = await tokenRequest(fetchFn, {
    grant_type: "authorization_code",
    code,
    client_id: creds.clientId,
    client_secret: creds.clientSecret,
    redirect_uri: redirectUri,
  }, now);

  const out: ConnectedAccount[] = [];

  let res: Response;
  try {
    res = await fetchFn(`${API}/v2/userinfo`, { headers: { Authorization: `Bearer ${tokens.accessToken}` } });
  } catch (e) {
    throw new PublishError(`Could not reach LinkedIn (${e instanceof Error ? e.message : e}).`, { transient: true });
  }
  const profileText = await res.text();
  let profile: any = null;
  try {
    profile = JSON.parse(profileText);
  } catch {
    profile = null;
  }
  if (!res.ok || !profile?.sub) throw toLinkedInError(res.status, profile, profileText);
  out.push({
    platform: "linkedin",
    externalId: `urn:li:person:${profile.sub}`,
    name: String(profile.name ?? "LinkedIn profile"),
    avatarUrl: profile.picture ? String(profile.picture) : undefined,
    meta: { kind: "person" },
    ...tokens,
  });

  // Company Pages, when the developer app has been approved for them.
  if (creds.options.pages === true) {
    try {
      const acls = await rest(
        fetchFn,
        "/rest/organizationAcls?q=roleAssignee&role=ADMINISTRATOR&state=APPROVED&count=50",
        tokens.accessToken,
      );
      for (const el of acls.json?.elements ?? []) {
        const urn = String(el.organization ?? "");
        const id = urn.split(":").pop();
        if (!urn || !id) continue;
        let name = "LinkedIn Page";
        try {
          const org = await rest(fetchFn, `/rest/organizations/${id}`, tokens.accessToken);
          name = String(org.json?.localizedName ?? name);
        } catch {
          // Keep the generic name.
        }
        out.push({
          platform: "linkedin",
          externalId: urn,
          name,
          meta: { kind: "organization" },
          ...tokens,
        });
      }
    } catch {
      // No Page access on this app: the personal profile still works.
    }
  }
  return out;
}
