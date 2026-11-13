// Facebook Pages + Instagram publishing through the Meta Graph API.
//
// Every publisher is a small state machine: one call does as much as it can,
// then either reports "published" or hands back state to resume from on the
// next run. Calls that create a post are marked `commit`, so a dropped
// connection there is reported as ambiguous instead of being retried (which
// could post twice).

import { PublishError } from "./errors.ts";
import type { ConnectedAccount, FetchFn, PublishJob, Publisher, StepContext, StepResult } from "./types.ts";

export const GRAPH_VERSION = "v27.0";
const GRAPH = `https://graph.facebook.com/${GRAPH_VERSION}`;

export const META_SCOPES = [
  "pages_show_list",
  "pages_read_engagement",
  "pages_manage_posts",
  "instagram_basic",
  "instagram_content_publish",
  "business_management",
];

const TRANSIENT_CODES = new Set([1, 2, 4, 17, 32, 341, 613, 9007]);
const VIDEO_WAIT_LIMIT_MS = 30 * 60 * 1000;

type Params = Record<string, string | number | boolean | undefined | null>;

interface GraphOptions {
  /** This call creates something visible; never blindly retry it. */
  commit?: boolean;
  headers?: Record<string, string>;
  /** Use a full URL instead of GRAPH + path. */
  absoluteUrl?: string;
}

export async function graph(
  fetchFn: FetchFn,
  method: "GET" | "POST",
  path: string,
  params: Params,
  token: string,
  opts: GraphOptions = {},
): Promise<any> {
  const clean: Record<string, string> = {};
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null) clean[k] = String(v);
  }

  const url = new URL(opts.absoluteUrl ?? `${GRAPH}/${path}`);
  const init: RequestInit = { method, headers: { ...(opts.headers ?? {}) } };
  if (method === "GET") {
    for (const [k, v] of Object.entries(clean)) url.searchParams.set(k, v);
    if (token) url.searchParams.set("access_token", token);
  } else {
    const body = new URLSearchParams(clean);
    if (token && !opts.headers?.Authorization) body.set("access_token", token);
    init.body = body;
  }

  let res: Response;
  try {
    res = await fetchFn(url, init);
  } catch (e) {
    const msg = `Could not reach Facebook (${e instanceof Error ? e.message : e}).`;
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

  if (!res.ok || json?.error) {
    throw toMetaError(res.status, json, text);
  }
  return json ?? {};
}

export function toMetaError(status: number, json: any, raw: string): PublishError {
  const err = json?.error;
  if (!err) {
    const transient = status >= 500 || status === 429;
    return new PublishError(`Facebook returned an error (HTTP ${status}). ${raw.slice(0, 200)}`.trim(), {
      transient,
    });
  }
  const code = Number(err.code);
  const sub = Number(err.error_subcode);
  const base = String(err.error_user_msg || err.message || "Unknown error");
  const title = err.error_user_title ? `${err.error_user_title}: ` : "";
  const detail = `${title}${base} (code ${code}${sub ? `/${sub}` : ""})`;

  if (code === 190 || code === 102) {
    return new PublishError(`Access has expired or was revoked - reconnect this account. ${detail}`, {
      reconnect: true,
    });
  }
  if (code === 10 || (code >= 200 && code <= 299)) {
    return new PublishError(
      `Missing permission - reconnect and make sure every permission and Page is ticked. ${detail}`,
    );
  }
  if (sub === 2207042) {
    return new PublishError(`Instagram's daily posting limit was reached. ${detail}`);
  }
  if (sub === 2207027) {
    // Container not ready to publish yet.
    return new PublishError(detail, { transient: true });
  }
  const transient = err.is_transient === true || TRANSIENT_CODES.has(code) || status >= 500;
  return new PublishError(detail, { transient });
}

function wait(state: Record<string, unknown>, retryInSec: number): StepResult {
  return { kind: "wait", state, retryInSec };
}

function elapsedMs(state: Record<string, unknown>, ctx: StepContext): number {
  const started = typeof state.startedAt === "number" ? state.startedAt : ctx.now();
  return ctx.now() - started;
}

// ---------------------------------------------------------------------------
// Facebook Pages
// ---------------------------------------------------------------------------

export const facebookStep: Publisher = async (job, ctx) => {
  const page = job.account.externalId;
  const token = job.accessToken;
  const state = job.state;
  const caption = job.caption.trim();

  if (state.phase === "video_wait") {
    return await facebookVideoWait(job, ctx);
  }

  const images = job.media.filter((m) => m.kind === "image");
  const videos = job.media.filter((m) => m.kind === "video");

  if (videos.length > 0) {
    if (job.media.length > 1) {
      throw new PublishError("A Facebook video post can only contain one video (no mixed photos).");
    }
    const video = videos[0];
    if (job.postType === "reel") {
      const start = await graph(ctx.fetch, "POST", `${page}/video_reels`, { upload_phase: "start" }, token);
      const videoId = String(start.video_id ?? "");
      if (!videoId) throw new PublishError("Facebook did not start the Reel upload.", { transient: true });
      const uploadUrl = String(
        start.upload_url ?? `https://rupload.facebook.com/video-upload/${GRAPH_VERSION}/${videoId}`,
      );
      await graph(ctx.fetch, "POST", "", {}, token, {
        absoluteUrl: uploadUrl,
        headers: { Authorization: `OAuth ${token}`, file_url: video.url },
      });
      await graph(
        ctx.fetch,
        "POST",
        `${page}/video_reels`,
        { video_id: videoId, upload_phase: "finish", video_state: "PUBLISHED", description: caption },
        token,
        { commit: true },
      );
      return wait({ phase: "video_wait", videoId, reel: true, startedAt: ctx.now() }, 20);
    }

    const res = await graph(
      ctx.fetch,
      "POST",
      `${page}/videos`,
      { file_url: video.url, description: caption },
      token,
      { commit: true },
    );
    const videoId = String(res.id ?? "");
    if (!videoId) throw new PublishError("Facebook did not return a video id.");
    return wait({ phase: "video_wait", videoId, startedAt: ctx.now() }, 20);
  }

  if (images.length === 0) {
    if (!caption && !job.linkUrl) {
      throw new PublishError("A Facebook post needs text, a link, a photo or a video.");
    }
    const res = await graph(
      ctx.fetch,
      "POST",
      `${page}/feed`,
      { message: caption || undefined, link: job.linkUrl || undefined },
      token,
      { commit: true },
    );
    return await facebookPublished(String(res.id), token, ctx);
  }

  if (images.length === 1) {
    const res = await graph(
      ctx.fetch,
      "POST",
      `${page}/photos`,
      { url: images[0].url, caption: caption || undefined, published: true },
      token,
      { commit: true },
    );
    return await facebookPublished(String(res.post_id ?? res.id), token, ctx);
  }

  // Several photos: upload each unpublished, then attach them to one post.
  const params: Params = { message: caption || undefined };
  for (let i = 0; i < images.length; i++) {
    const up = await graph(
      ctx.fetch,
      "POST",
      `${page}/photos`,
      { url: images[i].url, published: false },
      token,
    );
    params[`attached_media[${i}]`] = JSON.stringify({ media_fbid: String(up.id) });
  }
  const res = await graph(ctx.fetch, "POST", `${page}/feed`, params, token, { commit: true });
  return await facebookPublished(String(res.id), token, ctx);
};

async function facebookPublished(postId: string, token: string, ctx: StepContext): Promise<StepResult> {
  let permalink: string | undefined;
  try {
    const info = await graph(ctx.fetch, "GET", postId, { fields: "permalink_url" }, token);
    if (info.permalink_url) permalink = absoluteFacebookUrl(String(info.permalink_url));
  } catch {
    // The post is out; a missing link is not worth failing over.
  }
  return { kind: "published", externalId: postId, permalink: permalink ?? `https://www.facebook.com/${postId}` };
}

function absoluteFacebookUrl(url: string): string {
  return url.startsWith("http") ? url : `https://www.facebook.com${url.startsWith("/") ? "" : "/"}${url}`;
}

async function facebookVideoWait(job: PublishJob, ctx: StepContext): Promise<StepResult> {
  const state = job.state;
  const videoId = String(state.videoId);
  const info = await graph(ctx.fetch, "GET", videoId, { fields: "status,permalink_url" }, job.accessToken);
  const status = info.status ?? {};
  const videoStatus = String(status.video_status ?? "");
  const publishStatus = String(status.publishing_phase?.publish_status ?? "");
  const permalink = info.permalink_url
    ? absoluteFacebookUrl(String(info.permalink_url))
    : `https://www.facebook.com/${videoId}`;

  if (videoStatus === "error" || videoStatus === "upload_failed" || videoStatus === "expired" || publishStatus === "error") {
    const why = status.processing_phase?.errors?.[0]?.message ??
      status.uploading_phase?.errors?.[0]?.message ??
      status.publishing_phase?.errors?.[0]?.message ??
      "Facebook could not process the video.";
    throw new PublishError(String(why));
  }
  if (videoStatus === "ready" || publishStatus === "published") {
    return { kind: "published", externalId: videoId, permalink };
  }
  if (elapsedMs(state, ctx) > VIDEO_WAIT_LIMIT_MS) {
    return {
      kind: "published",
      externalId: videoId,
      permalink,
      note: "Facebook accepted the video but is still processing it. It will appear on the Page when ready.",
    };
  }
  return wait(state, 20);
}

// ---------------------------------------------------------------------------
// Instagram
// ---------------------------------------------------------------------------

export const INSTAGRAM_MAX_CAROUSEL = 20;

export const instagramStep: Publisher = async (job, ctx) => {
  const ig = job.account.externalId;
  const token = job.accessToken;
  const state = job.state;
  const caption = job.caption.trim();

  if (state.phase === "children") {
    const children = (state.children as string[]) ?? [];
    for (const id of children) {
      const st = await containerStatus(ctx.fetch, id, token);
      if (st.code === "ERROR" || st.code === "EXPIRED") {
        throw new PublishError(`Instagram could not process one of the items. ${st.detail}`.trim());
      }
      if (st.code !== "FINISHED") {
        if (elapsedMs(state, ctx) > VIDEO_WAIT_LIMIT_MS) {
          throw new PublishError("Instagram took too long to process the media.");
        }
        return wait(state, 15);
      }
    }
    const parent = await graph(
      ctx.fetch,
      "POST",
      `${ig}/media`,
      { media_type: "CAROUSEL", children: children.join(","), caption: caption || undefined },
      token,
    );
    // Hand the container id back first so it is saved before anything is published.
    return wait({ ...state, phase: "wait", container: String(parent.id) }, 2);
  }

  if (state.phase === "wait") {
    return await instagramFinish(job, ctx, state);
  }

  // First run: create the container(s).
  if (job.media.length === 0) {
    throw new PublishError("Instagram posts need a photo or a video.");
  }
  const first = job.media[0];
  const startedAt = ctx.now();

  if (job.postType === "story") {
    if (job.media.length > 1) {
      throw new PublishError("An Instagram story can only contain one photo or video.");
    }
    const created = await graph(
      ctx.fetch,
      "POST",
      `${ig}/media`,
      first.kind === "video"
        ? { media_type: "STORIES", video_url: first.url }
        : { media_type: "STORIES", image_url: first.url },
      token,
    );
    return wait({ phase: "wait", container: String(created.id), startedAt }, first.kind === "video" ? 15 : 2);
  }

  if (job.media.length === 1) {
    if (first.kind === "video") {
      // Every single-video post on Instagram is a Reel.
      const created = await graph(
        ctx.fetch,
        "POST",
        `${ig}/media`,
        {
          media_type: "REELS",
          video_url: first.url,
          caption: caption || undefined,
          share_to_feed: job.options.share_to_feed === false ? false : true,
        },
        token,
      );
      return wait({ phase: "wait", container: String(created.id), startedAt }, 15);
    }
    if (job.postType === "reel") {
      throw new PublishError("A Reel needs a video.");
    }
    const created = await graph(
      ctx.fetch,
      "POST",
      `${ig}/media`,
      { image_url: first.url, caption: caption || undefined },
      token,
    );
    return wait({ phase: "wait", container: String(created.id), startedAt }, 2);
  }

  if (job.postType === "reel") {
    throw new PublishError("A Reel can only contain one video.");
  }
  if (job.media.length > INSTAGRAM_MAX_CAROUSEL) {
    throw new PublishError(`Instagram carousels can hold at most ${INSTAGRAM_MAX_CAROUSEL} items.`);
  }
  const children: string[] = [];
  for (const m of job.media) {
    const child = await graph(
      ctx.fetch,
      "POST",
      `${ig}/media`,
      m.kind === "video"
        ? { media_type: "VIDEO", video_url: m.url, is_carousel_item: true }
        : { image_url: m.url, is_carousel_item: true },
      token,
    );
    children.push(String(child.id));
  }
  return wait({ phase: "children", children, startedAt }, job.media.some((m) => m.kind === "video") ? 15 : 3);
};

async function containerStatus(
  fetchFn: FetchFn,
  containerId: string,
  token: string,
): Promise<{ code: string; detail: string }> {
  const res = await graph(fetchFn, "GET", containerId, { fields: "status_code,status" }, token);
  return { code: String(res.status_code ?? ""), detail: String(res.status ?? "") };
}

async function instagramFinish(
  job: PublishJob,
  ctx: StepContext,
  state: Record<string, unknown>,
): Promise<StepResult> {
  const ig = job.account.externalId;
  const token = job.accessToken;
  const container = String(state.container);

  const st = await containerStatus(ctx.fetch, container, token);
  if (st.code === "ERROR") {
    throw new PublishError(`Instagram rejected the media. ${st.detail}`.trim());
  }
  if (st.code === "EXPIRED") {
    throw new PublishError("Instagram's upload expired before it could be published. Retry the post.");
  }
  if (st.code === "PUBLISHED") {
    // An earlier run published it but was cut off before recording that.
    return { kind: "published", note: "Published (link unavailable)." };
  }
  if (st.code !== "FINISHED") {
    if (elapsedMs(state, ctx) > VIDEO_WAIT_LIMIT_MS) {
      throw new PublishError("Instagram took too long to process the media.");
    }
    return wait(state, job.media.some((m) => m.kind === "video") ? 15 : 3);
  }

  const published = await graph(ctx.fetch, "POST", `${ig}/media_publish`, { creation_id: container }, token, {
    commit: true,
  });
  const mediaId = String(published.id ?? "");
  let permalink: string | undefined;
  try {
    const info = await graph(ctx.fetch, "GET", mediaId, { fields: "permalink" }, token);
    if (info.permalink) permalink = String(info.permalink);
  } catch {
    // Published; the link is a nicety.
  }
  return { kind: "published", externalId: mediaId, permalink };
}

// ---------------------------------------------------------------------------
// OAuth (Facebook Login) and account discovery
// ---------------------------------------------------------------------------

export function metaAuthorizeUrl(clientId: string, redirectUri: string, state: string): string {
  const url = new URL(`https://www.facebook.com/${GRAPH_VERSION}/dialog/oauth`);
  url.searchParams.set("client_id", clientId);
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("state", state);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", META_SCOPES.join(","));
  return url.toString();
}

/** Trades the login code for long-lived Page tokens and lists Pages + linked Instagram accounts. */
export async function metaConnect(
  fetchFn: FetchFn,
  creds: { clientId: string; clientSecret: string },
  code: string,
  redirectUri: string,
): Promise<ConnectedAccount[]> {
  const short = await graph(fetchFn, "GET", "oauth/access_token", {
    client_id: creds.clientId,
    client_secret: creds.clientSecret,
    redirect_uri: redirectUri,
    code,
  }, "");
  const long = await graph(fetchFn, "GET", "oauth/access_token", {
    grant_type: "fb_exchange_token",
    client_id: creds.clientId,
    client_secret: creds.clientSecret,
    fb_exchange_token: String(short.access_token),
  }, "");
  const userToken = String(long.access_token);

  const out: ConnectedAccount[] = [];
  let next: string | undefined;
  let page = await graph(fetchFn, "GET", "me/accounts", {
    fields:
      "id,name,access_token,picture{url},instagram_business_account{id,username,name,profile_picture_url}",
    limit: 100,
  }, userToken);

  for (let guard = 0; guard < 20; guard++) {
    for (const p of page.data ?? []) {
      if (!p.access_token) continue;
      out.push({
        platform: "facebook",
        externalId: String(p.id),
        name: String(p.name ?? "Facebook Page"),
        avatarUrl: p.picture?.data?.url ? String(p.picture.data.url) : undefined,
        meta: {},
        accessToken: String(p.access_token),
      });
      const ig = p.instagram_business_account;
      if (ig?.id) {
        out.push({
          platform: "instagram",
          externalId: String(ig.id),
          name: String(ig.name || ig.username || "Instagram"),
          username: ig.username ? String(ig.username) : undefined,
          avatarUrl: ig.profile_picture_url ? String(ig.profile_picture_url) : undefined,
          meta: { page_id: String(p.id), page_name: String(p.name ?? "") },
          accessToken: String(p.access_token),
        });
      }
    }
    next = page.paging?.next;
    if (!next) break;
    page = await graph(fetchFn, "GET", "", {}, "", { absoluteUrl: next });
  }
  return out;
}
