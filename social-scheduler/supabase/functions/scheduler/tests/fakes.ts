// In-memory stand-ins used by the tests: a Store that mimics the database
// rules, and a tiny HTTP router that plays the part of the platform APIs.

import type { CredentialKey, Credentials, FetchFn } from "../lib/types.ts";
import type { AccountRow, MediaRow, PostRow, Store, TargetRow, TokenRow, WorkerDeps } from "../lib/worker.ts";

export interface FakeTarget extends TargetRow {
  next_attempt_at: string | null;
  locked_until: string | null;
  error: string | null;
  note: string | null;
  external_id: string | null;
  permalink: string | null;
  published_at: string | null;
}

export class Clock {
  t = Date.parse("2026-11-13T09:00:00Z");
  now = () => this.t;
  sleep = (ms: number) => {
    this.t += ms;
    return Promise.resolve();
  };
  advance(ms: number) {
    this.t += ms;
  }
}

export class FakeStore implements Store {
  posts = new Map<string, PostRow>();
  targets = new Map<string, FakeTarget>();
  media = new Map<string, MediaRow[]>();
  accounts = new Map<string, AccountRow & { status_detail?: string | null }>();
  tokens = new Map<string, TokenRow>();
  credentials = new Map<CredentialKey, Credentials>();
  maxLateMinutes = 360;
  removed: string[] = [];
  purgeable: { media_id: string; storage_path: string }[] = [];
  /** Every state written, in order - lets tests see checkpoints. */
  stateLog: Record<string, unknown>[] = [];

  constructor(private clock: Clock) {}

  claimDueTargets(limit: number): Promise<TargetRow[]> {
    const now = this.clock.now();
    const due = [...this.targets.values()].filter((t) => {
      const post = this.posts.get(t.post_id);
      return t.mode === "auto" &&
        (t.status === "pending" || t.status === "processing") &&
        post && (post.status === "scheduled" || post.status === "publishing") &&
        post.scheduled_at !== null && Date.parse(post.scheduled_at) <= now &&
        (t.next_attempt_at === null || Date.parse(t.next_attempt_at) <= now) &&
        (t.locked_until === null || Date.parse(t.locked_until) <= now);
    }).slice(0, limit);
    for (const t of due) {
      t.status = "processing";
      t.locked_until = new Date(now + 4 * 60_000).toISOString();
      const post = this.posts.get(t.post_id)!;
      post.status = "publishing";
    }
    return Promise.resolve(due.map((t) => structuredClone(t)));
  }
  getPost(id: string) {
    return Promise.resolve(this.posts.get(id) ?? null);
  }
  getMedia(postId: string) {
    return Promise.resolve(this.media.get(postId) ?? []);
  }
  getAccount(id: string) {
    return Promise.resolve(this.accounts.get(id) ?? null);
  }
  getToken(accountId: string) {
    const t = this.tokens.get(accountId);
    return Promise.resolve(t ? { ...t } : null);
  }
  saveToken(accountId: string, token: TokenRow) {
    this.tokens.set(accountId, { ...token });
    return Promise.resolve();
  }
  updateTarget(id: string, patch: Record<string, unknown>) {
    const t = this.targets.get(id);
    if (!t) throw new Error("no such target");
    Object.assign(t, structuredClone(patch));
    if (patch.state) this.stateLog.push(structuredClone(patch.state as Record<string, unknown>));
    return Promise.resolve();
  }
  setAccountStatus(id: string, status: "active" | "needs_reconnect", detail: string | null) {
    const a = this.accounts.get(id);
    if (a) {
      a.status = status;
      a.status_detail = detail;
    }
    return Promise.resolve();
  }
  signedUrl(path: string, _expiresInSec: number) {
    return Promise.resolve(`https://storage.test/${path}?token=signed`);
  }
  getMaxLateMinutes() {
    return Promise.resolve(this.maxLateMinutes);
  }
  getCredentials(platform: CredentialKey) {
    return Promise.resolve(this.credentials.get(platform) ?? null);
  }
  mediaToPurge(limit: number) {
    return Promise.resolve(this.purgeable.slice(0, limit));
  }
  removeMedia(paths: string[]) {
    this.removed.push(...paths);
    return Promise.resolve();
  }
  clearMediaPaths(ids: string[]) {
    this.purgeable = this.purgeable.filter((p) => !ids.includes(p.media_id));
    return Promise.resolve();
  }
}

export interface Call {
  method: string;
  url: URL;
  headers: Headers;
  /** Form or JSON fields, when the body was one of those. */
  form: Record<string, string>;
  json: any;
  bytes: Uint8Array | null;
}

type Handler = (call: Call) => Response | Promise<Response>;

/** Routes fetch() calls to handlers; anything unrouted fails the test. */
export class FakeNet {
  private routes: { method: string; pattern: RegExp; handler: Handler }[] = [];
  calls: Call[] = [];
  files = new Map<string, Uint8Array>();

  on(method: string, pattern: RegExp, handler: Handler) {
    this.routes.unshift({ method, pattern, handler });
    return this;
  }

  /** Serves a stored file with Range support, like Supabase Storage. */
  addFile(path: string, bytes: Uint8Array) {
    this.files.set(path, bytes);
  }

  count(method: string, pattern: RegExp): number {
    return this.calls.filter((c) => c.method === method && pattern.test(c.url.toString())).length;
  }

  find(method: string, pattern: RegExp): Call[] {
    return this.calls.filter((c) => c.method === method && pattern.test(c.url.toString()));
  }

  fetch: FetchFn = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : input.toString());
    const method = (init?.method ?? "GET").toUpperCase();
    const headers = new Headers(init?.headers);
    const call: Call = { method, url, headers, form: {}, json: null, bytes: null };

    const body = init?.body;
    if (body instanceof URLSearchParams) {
      call.form = Object.fromEntries(body.entries());
    } else if (typeof body === "string") {
      try {
        call.json = JSON.parse(body);
      } catch {
        call.json = null;
      }
    } else if (body instanceof Uint8Array) {
      call.bytes = body;
    }
    this.calls.push(call);

    if (url.host === "storage.test") {
      const bytes = this.files.get(url.pathname.slice(1));
      if (!bytes) return new Response("not found", { status: 404 });
      const range = headers.get("range");
      if (range) {
        const m = /bytes=(\d+)-(\d+)/.exec(range)!;
        const first = Number(m[1]);
        const last = Math.min(Number(m[2]), bytes.byteLength - 1);
        return new Response(bytes.slice(first, last + 1), {
          status: 206,
          headers: { "content-range": `bytes ${first}-${last}/${bytes.byteLength}` },
        });
      }
      return new Response(bytes.slice(), { status: 200, headers: { "content-length": String(bytes.byteLength) } });
    }

    for (const r of this.routes) {
      if (r.method === method && r.pattern.test(url.toString())) return await r.handler(call);
    }
    throw new Error(`unexpected request in test: ${method} ${url}`);
  };
}

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

export function pattern(n: number): Uint8Array {
  const bytes = new Uint8Array(n);
  for (let i = 0; i < n; i++) bytes[i] = (i * 31 + 7) & 0xff;
  return bytes;
}

export interface Scenario {
  clock: Clock;
  store: FakeStore;
  net: FakeNet;
  deps: WorkerDeps;
  target: FakeTarget;
}

/** One due post with one target, ready for runWorker. */
export function scenario(opts: {
  platform: string;
  postType?: string;
  caption?: string;
  linkUrl?: string | null;
  options?: Record<string, unknown>;
  media?: { kind: "image" | "video"; bytes?: Uint8Array; mime?: string }[];
  token?: Partial<TokenRow>;
  username?: string | null;
  externalId?: string;
}): Scenario {
  const clock = new Clock();
  const store = new FakeStore(clock);
  const net = new FakeNet();

  store.posts.set("post1", {
    id: "post1",
    caption: opts.caption ?? "Hello world",
    link_url: opts.linkUrl ?? null,
    status: "scheduled",
    scheduled_at: new Date(clock.now() - 30_000).toISOString(),
  });
  store.accounts.set("acct1", {
    id: "acct1",
    platform: opts.platform,
    external_id: opts.externalId ?? "ext1",
    username: opts.username ?? null,
    status: "active",
    meta: {},
  });
  store.tokens.set("acct1", {
    access_token: "TOKEN",
    refresh_token: null,
    expires_at: null,
    refresh_expires_at: null,
    ...(opts.token ?? {}),
  });
  const rows: MediaRow[] = (opts.media ?? []).map((m, i) => {
    const path = `u/file${i}.${m.kind === "image" ? "jpg" : "mp4"}`;
    const bytes = m.bytes ?? pattern(1000 + i);
    net.addFile(path, bytes);
    return {
      id: `m${i}`,
      kind: m.kind,
      storage_path: path,
      mime_type: m.mime ?? (m.kind === "image" ? "image/jpeg" : "video/mp4"),
      size_bytes: bytes.byteLength,
      width: 1080,
      height: 1350,
      position: i,
    };
  });
  store.media.set("post1", rows);

  const target: FakeTarget = {
    id: "t1",
    post_id: "post1",
    platform: opts.platform,
    account_id: "acct1",
    mode: "auto",
    post_type: opts.postType ?? "feed",
    caption_override: null,
    options: opts.options ?? {},
    status: "pending",
    attempts: 0,
    state: {},
    next_attempt_at: null,
    locked_until: null,
    error: null,
    note: null,
    external_id: null,
    permalink: null,
    published_at: null,
  };
  store.targets.set("t1", target);

  const deps: WorkerDeps = { fetch: net.fetch, now: clock.now, sleep: clock.sleep, budgetMs: 50_000 };
  return { clock, store, net, deps, target };
}
