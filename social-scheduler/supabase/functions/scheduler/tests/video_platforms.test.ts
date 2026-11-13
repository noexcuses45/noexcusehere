import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { chunkPlan } from "../lib/tiktok.ts";
import { runWorker } from "../lib/worker.ts";
import { type Call, jsonResponse, pattern, scenario } from "./fakes.ts";

const MB = 1024 * 1024;

/** Rebuilds an upload from its chunks and checks the Content-Range headers line up. */
function assemble(calls: Call[], total: number): Uint8Array {
  const out = new Uint8Array(total);
  let expectedFirst = 0;
  for (const c of calls) {
    const m = /bytes (\d+)-(\d+)\/(\d+)/.exec(c.headers.get("content-range") ?? "");
    assert(m, "every chunk needs a Content-Range header");
    const [first, last, size] = [Number(m[1]), Number(m[2]), Number(m[3])];
    assertEquals(size, total);
    assertEquals(first, expectedFirst, "chunks are sequential");
    assertEquals(c.bytes!.byteLength, last - first + 1);
    assertEquals(Number(c.headers.get("content-length")), c.bytes!.byteLength);
    out.set(c.bytes!, first);
    expectedFirst = last + 1;
  }
  assertEquals(expectedFirst, total, "the whole file was sent");
  return out;
}

// ---------------------------------------------------------------- TikTok

Deno.test("tiktok: chunk plan follows TikTok's rules", () => {
  // Under 5 MB: one chunk equal to the file.
  assertEquals(chunkPlan(3 * MB), { chunkSize: 3 * MB, totalChunks: 1 });
  assertEquals(chunkPlan(20 * MB), { chunkSize: 20 * MB, totalChunks: 1 });
  // Larger: 10 MB chunks, count = floor(size / chunk), last chunk takes the remainder.
  assertEquals(chunkPlan(25 * MB), { chunkSize: 10 * MB, totalChunks: 2 });
  assertEquals(chunkPlan(99 * MB + 123), { chunkSize: 10 * MB, totalChunks: 9 });
  for (const size of [21 * MB, 64 * MB, 250 * MB + 7]) {
    const p = chunkPlan(size);
    assert(p.chunkSize >= 5 * MB && p.chunkSize <= 64 * MB);
    assertEquals(p.totalChunks, Math.floor(size / p.chunkSize));
    const lastChunk = size - (p.totalChunks - 1) * p.chunkSize;
    assert(lastChunk <= 128 * MB, "final chunk within TikTok's limit");
  }
});

Deno.test("tiktok: inbox draft - relays the video in valid chunks", async () => {
  const size = 25 * MB + 1234;
  const file = pattern(size);
  const s = scenario({ platform: "tiktok", postType: "inbox", media: [{ kind: "video", bytes: file }] });
  s.net.on("POST", /\/v2\/post\/publish\/inbox\/video\/init\/$/, (c) => {
    assertEquals(c.headers.get("authorization"), "Bearer TOKEN");
    assertEquals(c.json.source_info, {
      source: "FILE_UPLOAD",
      video_size: size,
      chunk_size: 10 * MB,
      total_chunk_count: 2,
    });
    assertEquals(c.json.post_info, undefined, "inbox uploads carry no post_info");
    return jsonResponse({ data: { publish_id: "pub1", upload_url: "https://upload.tiktok.test/u1" }, error: { code: "ok" } });
  });
  s.net.on("PUT", /upload\.tiktok\.test\/u1$/, (c) => {
    assertEquals(c.headers.get("content-type"), "video/mp4");
    const last = /-(\d+)\//.exec(c.headers.get("content-range")!)![1];
    return new Response(null, { status: Number(last) === size - 1 ? 201 : 206 });
  });
  s.net.on(
    "POST",
    /\/v2\/post\/publish\/status\/fetch\/$/,
    (c) => {
      assertEquals(c.json, { publish_id: "pub1" });
      return jsonResponse({ data: { status: "SEND_TO_USER_INBOX" }, error: { code: "ok" } });
    },
  );

  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertStringIncludes(s.target.note!, "TikTok inbox");
  const puts = s.net.find("PUT", /upload\.tiktok\.test/);
  assertEquals(puts.length, 2);
  assertEquals(assemble(puts, size), file, "TikTok received exactly the stored file");
});

Deno.test("tiktok: direct post asks for creator info and honours the privacy choice", async () => {
  const s = scenario({
    platform: "tiktok",
    postType: "direct",
    caption: "Behind the scenes",
    options: { privacy_level: "PUBLIC_TO_EVERYONE", disable_comment: true },
    media: [{ kind: "video", bytes: pattern(2 * MB) }],
  });
  s.net.on("POST", /creator_info\/query\/$/, () =>
    jsonResponse({
      data: {
        creator_username: "mybiz",
        privacy_level_options: ["PUBLIC_TO_EVERYONE", "SELF_ONLY"],
        comment_disabled: false,
        duet_disabled: true,
        stitch_disabled: false,
      },
      error: { code: "ok" },
    }));
  s.net.on("POST", /\/v2\/post\/publish\/video\/init\/$/, (c) => {
    assertEquals(c.json.post_info, {
      title: "Behind the scenes",
      privacy_level: "PUBLIC_TO_EVERYONE",
      disable_duet: true,
      disable_comment: true,
      disable_stitch: false,
    });
    assertEquals(c.json.source_info.total_chunk_count, 1);
    assertEquals(c.json.source_info.chunk_size, 2 * MB);
    return jsonResponse({ data: { publish_id: "pub2", upload_url: "https://upload.tiktok.test/u2" }, error: { code: "ok" } });
  });
  s.net.on("PUT", /upload\.tiktok\.test\/u2$/, () => new Response(null, { status: 201 }));
  s.net.on("POST", /status\/fetch\/$/, () =>
    jsonResponse({
      data: { status: "PUBLISH_COMPLETE", publicaly_available_post_id: [7412345678901234567n.toString()] },
      error: { code: "ok" },
    }));

  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(s.target.permalink, "https://www.tiktok.com/@mybiz/video/7412345678901234567");
});

Deno.test("tiktok: refuses a privacy level the account does not allow", async () => {
  const s = scenario({
    platform: "tiktok",
    postType: "direct",
    options: { privacy_level: "PUBLIC_TO_EVERYONE" },
    media: [{ kind: "video" }],
  });
  s.net.on("POST", /creator_info\/query\/$/, () =>
    jsonResponse({ data: { privacy_level_options: ["SELF_ONLY"] }, error: { code: "ok" } }));
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "SELF_ONLY");
  assertEquals(s.net.count("POST", /video\/init/), 0);
});

Deno.test("tiktok: un-reviewed app error is explained in plain words", async () => {
  const s = scenario({ platform: "tiktok", postType: "direct", media: [{ kind: "video" }] });
  s.net.on("POST", /creator_info\/query\/$/, () =>
    jsonResponse({ data: { privacy_level_options: ["SELF_ONLY"] }, error: { code: "ok" } }));
  s.net.on("POST", /video\/init\/$/, () =>
    jsonResponse({
      data: {},
      error: { code: "unaudited_client_can_only_post_to_private_accounts", message: "..." },
    }, 403));
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "PRIVATE accounts");
});

Deno.test("tiktok: photos are refused with a pointer to reminders", async () => {
  const s = scenario({ platform: "tiktok", postType: "inbox", media: [{ kind: "image" }] });
  await runWorker(s.store, s.deps);
  assertStringIncludes(s.target.error!, "exactly one video");
});

Deno.test("tiktok: expiring token is refreshed and the rotated refresh token is saved", async () => {
  const s = scenario({
    platform: "tiktok",
    postType: "inbox",
    media: [{ kind: "video", bytes: pattern(1000) }],
    token: {
      access_token: "OLD",
      refresh_token: "REFRESH_1",
      expires_at: new Date(Date.parse("2026-11-13T09:00:00Z") + 60_000).toISOString(),
      refresh_expires_at: "2027-05-01T00:00:00Z",
    },
  });
  s.store.credentials.set("tiktok", { clientId: "ck", clientSecret: "cs", options: {} });
  s.net.on("POST", /open\.tiktokapis\.com\/v2\/oauth\/token\/$/, (c) => {
    assertEquals(c.form, { client_key: "ck", client_secret: "cs", grant_type: "refresh_token", refresh_token: "REFRESH_1" });
    return jsonResponse({
      access_token: "NEW",
      refresh_token: "REFRESH_2",
      expires_in: 7200,
      refresh_expires_in: 15000000,
      open_id: "o1",
      scope: "user.info.basic,video.upload",
      token_type: "Bearer",
    });
  });
  s.net.on("POST", /inbox\/video\/init\/$/, (c) => {
    assertEquals(c.headers.get("authorization"), "Bearer NEW");
    return jsonResponse({ data: { publish_id: "p", upload_url: "https://upload.tiktok.test/u" }, error: { code: "ok" } });
  });
  s.net.on("PUT", /upload\.tiktok\.test/, () => new Response(null, { status: 201 }));
  s.net.on("POST", /status\/fetch/, () => jsonResponse({ data: { status: "SEND_TO_USER_INBOX" }, error: { code: "ok" } }));

  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  const saved = s.store.tokens.get("acct1")!;
  assertEquals(saved.access_token, "NEW");
  assertEquals(saved.refresh_token, "REFRESH_2", "TikTok refresh tokens rotate");
});

Deno.test("tiktok: a revoked refresh token flags the account for reconnecting", async () => {
  const s = scenario({
    platform: "tiktok",
    postType: "inbox",
    media: [{ kind: "video" }],
    token: { access_token: "OLD", refresh_token: "DEAD", expires_at: "2026-11-13T08:00:00Z" },
  });
  s.store.credentials.set("tiktok", { clientId: "ck", clientSecret: "cs", options: {} });
  s.net.on("POST", /oauth\/token\/$/, () =>
    jsonResponse({ error: "invalid_grant", error_description: "Refresh token is invalid or expired." }, 400));
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "failed");
  assertEquals(s.store.accounts.get("acct1")!.status, "needs_reconnect");
});

Deno.test("tiktok: an upload cut off mid-way starts again instead of failing or double-posting", async () => {
  const file = pattern(3 * MB);
  const s = scenario({ platform: "tiktok", postType: "inbox", media: [{ kind: "video", bytes: file }] });
  // Left behind by a run that died during the upload.
  s.target.status = "processing";
  s.target.state = { phase: "uploading", publishId: "dead", size: file.byteLength, inflight: true };
  let inits = 0;
  s.net.on("POST", /status\/fetch/, (c) =>
    jsonResponse({
      data: c.json.publish_id === "dead"
        ? { status: "PROCESSING_UPLOAD", uploaded_bytes: 1000 }
        : { status: "SEND_TO_USER_INBOX" },
      error: { code: "ok" },
    }));
  s.net.on("POST", /inbox\/video\/init\/$/, () => {
    inits++;
    return jsonResponse({ data: { publish_id: "fresh", upload_url: "https://upload.tiktok.test/f" }, error: { code: "ok" } });
  });
  s.net.on("PUT", /upload\.tiktok\.test/, () => new Response(null, { status: 201 }));

  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(inits, 1);
});

// ---------------------------------------------------------------- YouTube

function youtubeApi(s: ReturnType<typeof scenario>, size: number, opts: { privacy?: string } = {}) {
  let received = 0;
  const state = { metadata: null as any, completed: 0 };
  s.net.on("POST", /upload\/youtube\/v3\/videos\?uploadType=resumable&part=snippet,status$/, (c) => {
    assertEquals(c.headers.get("authorization"), "Bearer TOKEN");
    assertEquals(c.headers.get("x-upload-content-length"), String(size));
    assertEquals(c.headers.get("x-upload-content-type"), "video/mp4");
    state.metadata = c.json;
    return new Response(null, { status: 200, headers: { location: "https://upload.youtube.test/session/abc" } });
  });
  s.net.on("PUT", /upload\.youtube\.test\/session\/abc$/, (c) => {
    const range = c.headers.get("content-range")!;
    if (range.startsWith("bytes */")) {
      return received >= size
        ? jsonResponse({ id: "yt123" }, 200)
        : new Response(null, { status: 308, headers: received > 0 ? { range: `bytes=0-${received - 1}` } : {} });
    }
    const m = /bytes (\d+)-(\d+)\/(\d+)/.exec(range)!;
    const [first, last] = [Number(m[1]), Number(m[2])];
    assertEquals(first, received, "chunks must continue where YouTube left off");
    if (last !== size - 1) assertEquals((last - first + 1) % (256 * 1024), 0, "chunks are multiples of 256 KB");
    received = last + 1;
    if (received >= size) {
      state.completed++;
      return jsonResponse({ id: "yt123" }, 200);
    }
    return new Response(null, { status: 308, headers: { range: `bytes=0-${received - 1}` } });
  });
  s.net.on("GET", /youtube\/v3\/videos\?part=status&id=yt123$/, () =>
    jsonResponse({ items: [{ id: "yt123", status: { uploadStatus: "processed", privacyStatus: opts.privacy ?? "public" } }] }));
  return { state, setReceived: (n: number) => (received = n) };
}

Deno.test("youtube: resumable upload in chunks, then a link", async () => {
  const size = 20 * MB + 777;
  const file = pattern(size);
  const s = scenario({
    platform: "youtube",
    postType: "video",
    caption: "How we make it\nA look inside the workshop. <b>Enjoy</b>",
    options: { privacy_status: "unlisted", tags: ["#workshop", "howto"] },
    media: [{ kind: "video", bytes: file }],
  });
  const api = youtubeApi(s, size, { privacy: "unlisted" });

  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(s.target.permalink, "https://youtu.be/yt123");
  assertEquals(s.target.note, null);
  assertEquals(api.state.metadata.snippet.title, "How we make it");
  assert(!api.state.metadata.snippet.description.includes("<"), "angle brackets are not allowed by YouTube");
  assertEquals(api.state.metadata.snippet.tags, ["workshop", "howto"]);
  assertEquals(api.state.metadata.status, { privacyStatus: "unlisted", selfDeclaredMadeForKids: false });
  const puts = s.net.find("PUT", /upload\.youtube\.test/);
  assertEquals(puts.length, 3);
  assertEquals(assemble(puts, size), file);
});

Deno.test("youtube: explains when YouTube forces the video to private", async () => {
  const size = 1000;
  const s = scenario({
    platform: "youtube",
    postType: "video",
    options: { privacy_status: "public" },
    media: [{ kind: "video", bytes: pattern(size) }],
  });
  youtubeApi(s, size, { privacy: "private" });
  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertStringIncludes(s.target.note!, "PRIVATE");
});

Deno.test("youtube: an interrupted upload resumes from where YouTube got to", async () => {
  const size = 20 * MB;
  const file = pattern(size);
  const s = scenario({ platform: "youtube", postType: "video", media: [{ kind: "video", bytes: file }] });
  const api = youtubeApi(s, size);
  // A previous run opened the session and sent the first 8 MB, then died.
  api.setReceived(8 * MB);
  s.target.status = "processing";
  s.target.state = {
    phase: "uploading",
    sessionUrl: "https://upload.youtube.test/session/abc",
    size,
    inflight: true,
  };

  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(s.net.count("POST", /uploadType=resumable/), 0, "no second upload session");
  const data = s.net.find("PUT", /upload\.youtube\.test/).filter((c) => c.bytes);
  assertEquals(data[0].headers.get("content-range"), `bytes ${8 * MB}-${16 * MB - 1}/${size}`);
  assertEquals(api.state.completed, 1);
});

Deno.test("youtube: quota exhaustion is explained and not retried", async () => {
  const s = scenario({ platform: "youtube", postType: "video", media: [{ kind: "video" }] });
  s.net.on("POST", /uploadType=resumable/, () =>
    jsonResponse({
      error: { code: 403, message: "The request cannot be completed because you have exceeded your quota.", errors: [{ reason: "quotaExceeded" }] },
    }, 403));
  const summary = await runWorker(s.store, s.deps);
  assertEquals(summary.failed, 1);
  assertStringIncludes(s.target.error!, "quota");
});

Deno.test("youtube: expired access token is refreshed with the stored refresh token", async () => {
  const size = 500;
  const s = scenario({
    platform: "youtube",
    postType: "video",
    media: [{ kind: "video", bytes: pattern(size) }],
    token: { access_token: "STALE", refresh_token: "GREFRESH", expires_at: "2026-11-13T08:59:00Z" },
  });
  s.store.credentials.set("google", { clientId: "gid", clientSecret: "gsec", options: {} });
  s.net.on("POST", /oauth2\.googleapis\.com\/token$/, (c) => {
    assertEquals(c.form, { client_id: "gid", client_secret: "gsec", refresh_token: "GREFRESH", grant_type: "refresh_token" });
    return jsonResponse({ access_token: "TOKEN", expires_in: 3599, scope: "x", token_type: "Bearer" });
  });
  youtubeApi(s, size);
  for (let i = 0; i < 3 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  const saved = s.store.tokens.get("acct1")!;
  assertEquals(saved.access_token, "TOKEN");
  assertEquals(saved.refresh_token, "GREFRESH", "Google keeps the same refresh token");
});
