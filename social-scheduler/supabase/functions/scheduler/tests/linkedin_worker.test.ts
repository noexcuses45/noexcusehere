import { assert, assertEquals, assertStringIncludes, assertThrows } from "@std/assert";
import { buildPrompt, parseCaptionJson } from "../lib/ai.ts";
import { escapeCommentary, LINKEDIN_VERSION, linkedinVersion } from "../lib/linkedin.ts";
import { PLATFORMS } from "../lib/platforms.ts";
import type { Publisher } from "../lib/types.ts";
import { purgeOldMedia, runWorker } from "../lib/worker.ts";
import { jsonResponse, pattern, scenario } from "./fakes.ts";

// ---------------------------------------------------------------- LinkedIn

function checkHeaders(c: { headers: Headers }) {
  assertEquals(c.headers.get("authorization"), "Bearer TOKEN");
  assertEquals(c.headers.get("linkedin-version"), LINKEDIN_VERSION);
  assertEquals(c.headers.get("x-restli-protocol-version"), "2.0.0");
}

Deno.test("linkedin: commentary escaping keeps hashtags and links, escapes markup", () => {
  assertEquals(escapeCommentary("Big news (finally)!"), "Big news \\(finally\\)!");
  assertEquals(escapeCommentary("50% off * today_only"), "50% off \\* today\\_only");
  assertEquals(escapeCommentary("Join us #Sydney #2026"), "Join us #Sydney #2026");
  assertEquals(escapeCommentary("We're # 1"), "We're \\# 1");
  assertEquals(escapeCommentary("email me@example.com"), "email me\\@example.com");
  assertEquals(escapeCommentary("https://example.com/a-b?x=1"), "https://example.com/a-b?x=1");
  // "#" inside a word or URL is not a hashtag and must be escaped.
  assertEquals(escapeCommentary("see https://example.com/page#top"), "see https://example.com/page\\#top");
  assertEquals(escapeCommentary("#first line\n#second"), "#first line\n#second");
  assertEquals(escapeCommentary("path C:\\dir"), "path C:\\\\dir");
});

Deno.test("linkedin: API version stays pinned while supported, then rolls forward", () => {
  assertEquals(linkedinVersion(Date.parse("2026-11-13T00:00:00Z")), "202610");
  assertEquals(linkedinVersion(Date.parse("2027-07-01T00:00:00Z")), "202610");
  // Past the pinned version's safe window: about six months behind "now".
  assertEquals(linkedinVersion(Date.parse("2027-11-20T00:00:00Z")), "202705");
  // Never lands on December, which has no release.
  assertEquals(linkedinVersion(Date.parse("2028-06-10T00:00:00Z")), "202711");
});

Deno.test("linkedin: text post as a member", async () => {
  const s = scenario({ platform: "linkedin", externalId: "urn:li:person:abc", caption: "We're hiring (2 roles) #jobs" });
  s.net.on("POST", /api\.linkedin\.com\/rest\/posts$/, (c) => {
    checkHeaders(c);
    assertEquals(c.json.author, "urn:li:person:abc");
    assertEquals(c.json.commentary, "We're hiring \\(2 roles\\) #jobs");
    assertEquals(c.json.visibility, "PUBLIC");
    assertEquals(c.json.lifecycleState, "PUBLISHED");
    assertEquals(c.json.distribution.feedDistribution, "MAIN_FEED");
    assertEquals(c.json.content, undefined);
    return new Response(null, { status: 201, headers: { "x-restli-id": "urn:li:share:7001" } });
  });
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "published");
  assertEquals(s.target.external_id, "urn:li:share:7001");
  assertEquals(s.target.permalink, "https://www.linkedin.com/feed/update/urn:li:share:7001/");
});

Deno.test("linkedin: photos are uploaded first, then attached", async () => {
  const s = scenario({
    platform: "linkedin",
    externalId: "urn:li:organization:42",
    media: [{ kind: "image" }, { kind: "image" }],
  });
  let n = 0;
  s.net.on("POST", /rest\/images\?action=initializeUpload$/, (c) => {
    checkHeaders(c);
    assertEquals(c.json, { initializeUploadRequest: { owner: "urn:li:organization:42" } });
    n++;
    return jsonResponse({ value: { uploadUrl: `https://upload.linkedin.test/img${n}`, image: `urn:li:image:${n}` } });
  });
  const uploaded: number[] = [];
  s.net.on("PUT", /upload\.linkedin\.test\/img\d$/, (c) => {
    assertEquals(c.headers.get("authorization"), "Bearer TOKEN", "image uploads need the token");
    uploaded.push(c.bytes!.byteLength);
    return new Response(null, { status: 201 });
  });
  s.net.on("POST", /rest\/posts$/, (c) => {
    assertEquals(c.json.content, { multiImage: { images: [{ id: "urn:li:image:1" }, { id: "urn:li:image:2" }] } });
    return new Response(null, { status: 201, headers: { "x-restli-id": "urn:li:ugcPost:9" } });
  });
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "published");
  assertEquals(uploaded, [1000, 1001]);
});

Deno.test("linkedin: video is uploaded in the parts LinkedIn asks for, then posted once ready", async () => {
  const size = 9_000_000;
  const file = pattern(size);
  const s = scenario({
    platform: "linkedin",
    externalId: "urn:li:person:abc",
    caption: "Our new showroom\nCome and see it.",
    media: [{ kind: "video", bytes: file }],
  });
  s.net.on("POST", /rest\/videos\?action=initializeUpload$/, (c) => {
    assertEquals(c.json.initializeUploadRequest.fileSizeBytes, size);
    assertEquals(c.json.initializeUploadRequest.owner, "urn:li:person:abc");
    return jsonResponse({
      value: {
        video: "urn:li:video:V1",
        uploadToken: "",
        uploadInstructions: [
          { uploadUrl: "https://upload.linkedin.test/v/p1", firstByte: 0, lastByte: 4_194_303 },
          { uploadUrl: "https://upload.linkedin.test/v/p2", firstByte: 4_194_304, lastByte: 8_388_607 },
          { uploadUrl: "https://upload.linkedin.test/v/p3", firstByte: 8_388_608, lastByte: size - 1 },
        ],
      },
    });
  });
  const got = new Uint8Array(size);
  s.net.on("PUT", /upload\.linkedin\.test\/v\/p(\d)$/, (c) => {
    const part = Number(c.url.pathname.at(-1));
    assertEquals(c.headers.get("authorization"), null, "video parts must not carry the token");
    got.set(c.bytes!, (part - 1) * 4_194_304);
    return new Response(null, { status: 200, headers: { etag: `"etag-${part}"` } });
  });
  s.net.on("POST", /rest\/videos\?action=finalizeUpload$/, (c) => {
    assertEquals(c.json, {
      finalizeUploadRequest: { video: "urn:li:video:V1", uploadToken: "", uploadedPartIds: ["etag-1", "etag-2", "etag-3"] },
    });
    return new Response(null, { status: 200 });
  });
  let polls = 0;
  s.net.on("GET", /rest\/videos\/urn%3Ali%3Avideo%3AV1$/, () =>
    jsonResponse({ status: ++polls < 2 ? "PROCESSING" : "AVAILABLE" }));
  let posts = 0;
  s.net.on("POST", /rest\/posts$/, (c) => {
    posts++;
    assertEquals(c.json.content, { media: { id: "urn:li:video:V1", title: "Our new showroom" } });
    return new Response(null, { status: 201, headers: { "x-restli-id": "urn:li:ugcPost:55" } });
  });

  for (let i = 0; i < 5 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(got, file);
  assertEquals(posts, 1);
  assert(
    s.store.stateLog.some((st) => st.phase === "post"),
    "the 'ready to post' step is saved before posting",
  );
});

Deno.test("linkedin: member-only apps that cannot read video status still post, retrying if too early", async () => {
  const s = scenario({ platform: "linkedin", externalId: "urn:li:person:abc", media: [{ kind: "video", bytes: pattern(5000) }] });
  s.net.on("POST", /rest\/videos\?action=initializeUpload$/, () =>
    jsonResponse({
      value: {
        video: "urn:li:video:V2",
        uploadToken: "",
        uploadInstructions: [{ uploadUrl: "https://upload.linkedin.test/v/p1", firstByte: 0, lastByte: 4999 }],
      },
    }));
  s.net.on("PUT", /upload\.linkedin\.test\/v\/p1$/, () => new Response(null, { status: 200, headers: { etag: "e1" } }));
  s.net.on("POST", /rest\/videos\?action=finalizeUpload$/, () => new Response(null, { status: 200 }));
  s.net.on("GET", /rest\/videos\//, () =>
    jsonResponse({ status: 403, code: "ACCESS_DENIED", message: "Accessing this video resource is forbidden" }, 403));
  let posts = 0;
  s.net.on("POST", /rest\/posts$/, () => {
    posts++;
    return posts === 1
      ? jsonResponse({ status: 400, code: "MEDIA_ASSET_PROCESSING", message: "Media asset is still processing" }, 400)
      : new Response(null, { status: 201, headers: { "x-restli-id": "urn:li:ugcPost:77" } });
  });
  for (let i = 0; i < 6 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(posts, 2);
});

Deno.test("linkedin: a run that died while posting a video is not repeated", async () => {
  const s = scenario({ platform: "linkedin", externalId: "urn:li:person:abc", media: [{ kind: "video" }] });
  s.target.status = "processing";
  s.target.state = { phase: "post", video: "urn:li:video:V1", inflight: true };
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "may or may not have gone out");
  assertEquals(s.net.count("POST", /rest\/posts/), 0);
});

Deno.test("linkedin: missing product/permission is explained", async () => {
  const s = scenario({ platform: "linkedin", externalId: "urn:li:organization:42" });
  s.net.on("POST", /rest\/posts$/, () =>
    jsonResponse({ status: 403, code: "ACCESS_DENIED", message: "Not enough permissions to access: posts.CREATE" }, 403));
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "missing a product or permission");
});

Deno.test("linkedin: an expired 60-day token asks for a reconnect", async () => {
  const s = scenario({
    platform: "linkedin",
    externalId: "urn:li:person:abc",
    token: { access_token: "OLD", refresh_token: null, expires_at: "2026-11-01T00:00:00Z" },
  });
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "LinkedIn access has expired");
  assertEquals(s.store.accounts.get("acct1")!.status, "needs_reconnect");
});

// ---------------------------------------------------------------- Worker rules

Deno.test("worker: every platform has a publisher and declares its resumable phases", () => {
  for (const key of ["facebook", "instagram", "tiktok", "youtube", "linkedin"] as const) {
    assert(typeof PLATFORMS[key].publish === "function");
    assert(Array.isArray(PLATFORMS[key].resumablePhases));
  }
});

Deno.test("worker: a run that died mid-send is failed safely, never re-sent", async () => {
  const s = scenario({ platform: "facebook" });
  s.target.status = "processing";
  s.target.state = { inflight: true };
  const summary = await runWorker(s.store, s.deps);
  assertEquals(summary.failed, 1);
  assertStringIncludes(s.target.error!, "may or may not have gone out");
  assertEquals(s.net.calls.length, 0, "nothing is sent");
});

Deno.test("worker: marks the attempt in-flight before calling the platform", async () => {
  const s = scenario({ platform: "facebook" });
  let seen: unknown = null;
  const publisher: Publisher = () => {
    seen = structuredClone(s.store.targets.get("t1")!.state);
    return Promise.resolve({ kind: "published" });
  };
  await runWorker(s.store, { ...s.deps, publishers: { facebook: publisher } });
  assertEquals(seen, { inflight: true });
  assertEquals(s.target.state, {}, "flag is cleared once the result is recorded");
});

Deno.test("worker: a post that is hours late is not sent", async () => {
  const s = scenario({ platform: "facebook" });
  s.store.posts.get("post1")!.scheduled_at = new Date(s.clock.now() - 7 * 3_600_000).toISOString();
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "Missed its scheduled time");
  assertEquals(s.net.calls.length, 0);
});

Deno.test("worker: disconnected account and purged media fail with clear reasons", async () => {
  const gone = scenario({ platform: "facebook" });
  gone.target.account_id = null;
  await runWorker(gone.store, gone.deps);
  assertStringIncludes(gone.target.error!, "disconnected");

  const purged = scenario({ platform: "instagram", media: [{ kind: "image" }] });
  purged.store.media.get("post1")![0].storage_path = null;
  await runWorker(purged.store, purged.deps);
  assertStringIncludes(purged.target.error!, "media has been deleted");
});

Deno.test("worker: drafts, reminders and future posts are left alone", async () => {
  const draft = scenario({ platform: "facebook" });
  draft.store.posts.get("post1")!.status = "draft";
  assertEquals((await runWorker(draft.store, draft.deps)).processed, 0);

  const reminder = scenario({ platform: "facebook" });
  reminder.target.mode = "reminder";
  assertEquals((await runWorker(reminder.store, reminder.deps)).processed, 0);

  const future = scenario({ platform: "facebook" });
  future.store.posts.get("post1")!.scheduled_at = new Date(future.clock.now() + 60_000).toISOString();
  assertEquals((await runWorker(future.store, future.deps)).processed, 0);
});

Deno.test("worker: caption override is used for that platform only", async () => {
  const s = scenario({ platform: "facebook", caption: "Main caption" });
  s.target.caption_override = "Facebook-only wording";
  s.net.on("POST", /\/ext1\/feed$/, (c) => {
    assertEquals(c.form.message, "Facebook-only wording");
    return jsonResponse({ id: "ext1_1" });
  });
  s.net.on("GET", /\/ext1_1\?/, () => jsonResponse({}));
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "published");
});

Deno.test("worker: a successful post clears a stale reconnect flag", async () => {
  const s = scenario({ platform: "facebook" });
  s.store.accounts.get("acct1")!.status = "needs_reconnect";
  s.net.on("POST", /\/ext1\/feed$/, () => jsonResponse({ id: "ext1_1" }));
  s.net.on("GET", /\/ext1_1\?/, () => jsonResponse({}));
  await runWorker(s.store, s.deps);
  assertEquals(s.store.accounts.get("acct1")!.status, "active");
});

Deno.test("worker: old media is removed from storage and unlinked", async () => {
  const s = scenario({ platform: "facebook" });
  s.store.purgeable = [{ media_id: "m1", storage_path: "u/a.jpg" }, { media_id: "m2", storage_path: "u/b.mp4" }];
  assertEquals(await purgeOldMedia(s.store), 2);
  assertEquals(s.store.removed, ["u/a.jpg", "u/b.mp4"]);
  assertEquals(s.store.purgeable.length, 0);
});

// ---------------------------------------------------------------- AI captions

Deno.test("ai: prompt carries the brief, platforms and preferences", () => {
  const prompt = buildPrompt({
    brief: "New winter menu starts Monday",
    platforms: ["instagram", "facebook"],
    tone: "playful",
    hashtags: false,
    emoji: false,
    draft: "winter menu is here",
  });
  assertStringIncludes(prompt, "New winter menu starts Monday");
  assertStringIncludes(prompt, "instagram, facebook");
  assertStringIncludes(prompt, "playful");
  assertStringIncludes(prompt, "do not use emoji");
  assertStringIncludes(prompt, "empty hashtags list");
  assertStringIncludes(prompt, "winter menu is here");
});

Deno.test("ai: response parsing normalises hashtags and rejects junk", () => {
  const ok = parseCaptionJson(JSON.stringify({ captions: [" One ", "", "Two"], hashtags: ["food", "#Food", "#food", "# two words"] }));
  assertEquals(ok.captions, ["One", "Two"]);
  assertEquals(ok.hashtags, ["#food", "#Food", "#twowords"]);
  assertThrows(() => parseCaptionJson("not json"));
  assertThrows(() => parseCaptionJson(JSON.stringify({ captions: [], hashtags: [] })));
});
