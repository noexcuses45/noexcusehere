import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { runWorker } from "../lib/worker.ts";
import { jsonResponse, scenario } from "./fakes.ts";

const G = /graph\.facebook\.com\/v27\.0/;

// ---------------------------------------------------------------- Facebook

Deno.test("facebook: text + link post", async () => {
  const s = scenario({ platform: "facebook", caption: "Open late tonight", linkUrl: "https://example.com/menu" });
  s.net.on("POST", /\/ext1\/feed$/, (c) => {
    assertEquals(c.form.message, "Open late tonight");
    assertEquals(c.form.link, "https://example.com/menu");
    assertEquals(c.form.access_token, "TOKEN");
    return jsonResponse({ id: "ext1_555" });
  });
  s.net.on("GET", /\/ext1_555\?/, () => jsonResponse({ permalink_url: "https://www.facebook.com/ext1/posts/555" }));

  const summary = await runWorker(s.store, s.deps);
  assertEquals(summary.published, 1);
  assertEquals(s.target.status, "published");
  assertEquals(s.target.external_id, "ext1_555");
  assertEquals(s.target.permalink, "https://www.facebook.com/ext1/posts/555");
  assert(G.test(s.net.calls.at(-1)!.url.toString()), "uses the pinned Graph API version");
});

Deno.test("facebook: single photo uses caption (not the deprecated message field)", async () => {
  const s = scenario({ platform: "facebook", caption: "New arrivals", media: [{ kind: "image" }] });
  s.net.on("POST", /\/ext1\/photos$/, (c) => {
    assertEquals(c.form.caption, "New arrivals");
    assertEquals(c.form.message, undefined);
    assertEquals(c.form.published, "true");
    assertStringIncludes(c.form.url, "https://storage.test/u/file0.jpg");
    return jsonResponse({ id: "ph1", post_id: "ext1_777" });
  });
  s.net.on("GET", /\/ext1_777\?/, () => jsonResponse({ permalink_url: "/ext1/posts/777" }));

  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "published");
  assertEquals(s.target.permalink, "https://www.facebook.com/ext1/posts/777");
});

Deno.test("facebook: several photos become one post", async () => {
  const s = scenario({
    platform: "facebook",
    caption: "Gallery",
    media: [{ kind: "image" }, { kind: "image" }, { kind: "image" }],
  });
  let n = 0;
  s.net.on("POST", /\/ext1\/photos$/, (c) => {
    assertEquals(c.form.published, "false");
    return jsonResponse({ id: `ph${++n}` });
  });
  s.net.on("POST", /\/ext1\/feed$/, (c) => {
    assertEquals(c.form.message, "Gallery");
    assertEquals(JSON.parse(c.form["attached_media[0]"]), { media_fbid: "ph1" });
    assertEquals(JSON.parse(c.form["attached_media[2]"]), { media_fbid: "ph3" });
    return jsonResponse({ id: "ext1_900" });
  });
  s.net.on("GET", /\/ext1_900\?/, () => jsonResponse({}));

  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "published");
  assertEquals(s.net.count("POST", /\/photos$/), 3);
  assertEquals(s.net.count("POST", /\/feed$/), 1);
});

Deno.test("facebook: video waits for processing, then reports the link", async () => {
  const s = scenario({ platform: "facebook", caption: "Watch this", media: [{ kind: "video" }] });
  s.net.on("POST", /\/ext1\/videos$/, (c) => {
    assertStringIncludes(c.form.file_url, "file0.mp4");
    assertEquals(c.form.description, "Watch this");
    assert(c.url.host === "graph.facebook.com", "graph-video host is deprecated");
    return jsonResponse({ id: "vid1" });
  });
  let polls = 0;
  s.net.on("GET", /\/vid1\?/, () => {
    polls++;
    return jsonResponse(
      polls < 3
        ? { status: { video_status: "processing" } }
        : { status: { video_status: "ready" }, permalink_url: "/watch/?v=vid1" },
    );
  });

  // First run: uploaded, still processing.
  let summary = await runWorker(s.store, s.deps);
  assertEquals(summary.waiting, 1);
  assertEquals(s.target.status, "processing");
  assertEquals((s.target.state as any).phase, "video_wait");

  // Later runs poll until ready, without uploading again.
  for (let i = 0; i < 5 && s.target.status !== "published"; i++) {
    s.clock.advance(60_000);
    summary = await runWorker(s.store, s.deps);
  }
  assertEquals(s.target.status, "published");
  assertEquals(s.target.permalink, "https://www.facebook.com/watch/?v=vid1");
  assertEquals(s.net.count("POST", /\/videos$/), 1);
});

Deno.test("facebook: reel uses the three-step upload", async () => {
  const s = scenario({ platform: "facebook", postType: "reel", caption: "Reel!", media: [{ kind: "video" }] });
  const phases: string[] = [];
  s.net.on("POST", /\/ext1\/video_reels$/, (c) => {
    phases.push(c.form.upload_phase);
    if (c.form.upload_phase === "start") {
      return jsonResponse({ video_id: "r1", upload_url: "https://rupload.facebook.com/video-upload/v27.0/r1" });
    }
    assertEquals(c.form.video_id, "r1");
    assertEquals(c.form.video_state, "PUBLISHED");
    assertEquals(c.form.description, "Reel!");
    return jsonResponse({ success: true });
  });
  s.net.on("POST", /rupload\.facebook\.com\/video-upload\/v27\.0\/r1$/, (c) => {
    assertEquals(c.headers.get("authorization"), "OAuth TOKEN");
    assertStringIncludes(c.headers.get("file_url")!, "file0.mp4");
    return jsonResponse({ success: true });
  });
  s.net.on(
    "GET",
    /\/r1\?/,
    () => jsonResponse({ status: { video_status: "ready", publishing_phase: { publish_status: "published" } } }),
  );

  await runWorker(s.store, s.deps);
  s.clock.advance(60_000);
  await runWorker(s.store, s.deps);
  assertEquals(phases, ["start", "finish"]);
  assertEquals(s.target.status, "published");
});

Deno.test("facebook: an expired token fails the post and flags the account", async () => {
  const s = scenario({ platform: "facebook" });
  s.net.on(
    "POST",
    /\/ext1\/feed$/,
    () => jsonResponse({ error: { message: "Error validating access token", type: "OAuthException", code: 190 } }, 400),
  );
  const summary = await runWorker(s.store, s.deps);
  assertEquals(summary.failed, 1);
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "reconnect");
  assertEquals(s.store.accounts.get("acct1")!.status, "needs_reconnect");
});

Deno.test("facebook: a rate limit is retried later and then succeeds", async () => {
  const s = scenario({ platform: "facebook" });
  let attempt = 0;
  s.net.on("POST", /\/ext1\/feed$/, () => {
    attempt++;
    return attempt === 1
      ? jsonResponse({ error: { message: "Application request limit reached", code: 4 } }, 400)
      : jsonResponse({ id: "ext1_1" });
  });
  s.net.on("GET", /\/ext1_1\?/, () => jsonResponse({}));

  let summary = await runWorker(s.store, s.deps);
  assertEquals(summary.retrying, 1);
  assertEquals(s.target.status, "processing");
  assertEquals(s.target.attempts, 1);
  assert(Date.parse(s.target.next_attempt_at!) > s.clock.now(), "backs off");

  // Not due yet: nothing happens.
  summary = await runWorker(s.store, s.deps);
  assertEquals(summary.processed, 0);

  s.clock.advance(60_000);
  summary = await runWorker(s.store, s.deps);
  assertEquals(summary.published, 1);
  assertEquals(attempt, 2);
});

Deno.test("facebook: gives up after repeated transient failures", async () => {
  const s = scenario({ platform: "facebook" });
  s.net.on("POST", /\/ext1\/feed$/, () => new Response("upstream down", { status: 503 }));
  for (let i = 0; i < 8 && s.target.status !== "failed"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(20 * 60_000);
    // Keep the post inside the "not too late" window for this test.
    s.store.posts.get("post1")!.scheduled_at = new Date(s.clock.now() - 1000).toISOString();
  }
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "gave up after 5 attempts");
  assertEquals(s.net.count("POST", /\/feed$/), 5);
});

Deno.test("facebook: a dropped connection while posting is never retried blindly", async () => {
  const s = scenario({ platform: "facebook" });
  s.net.on("POST", /\/ext1\/feed$/, () => {
    throw new TypeError("connection reset");
  });
  const summary = await runWorker(s.store, s.deps);
  assertEquals(summary.failed, 1);
  assertStringIncludes(s.target.error!, "may or may not have gone out");
  s.clock.advance(3_600_000);
  await runWorker(s.store, s.deps);
  assertEquals(s.net.count("POST", /\/feed$/), 1, "must not post a second time");
});

// ---------------------------------------------------------------- Instagram

function instagramApi(s: ReturnType<typeof scenario>, opts: { statuses?: string[] } = {}) {
  const statuses = [...(opts.statuses ?? ["FINISHED"])];
  const created: Record<string, string>[] = [];
  let publishes = 0;
  s.net.on("POST", /\/ext1\/media$/, (c) => {
    created.push(c.form);
    return jsonResponse({ id: `c${created.length}` });
  });
  s.net.on("GET", /\/c\d+\?/, (c) => {
    assertEquals(c.url.searchParams.get("fields"), "status_code,status");
    const code = statuses.length > 1 ? statuses.shift()! : statuses[0];
    return jsonResponse({ status_code: code, status: code === "ERROR" ? "2207026: unsupported video format" : "" });
  });
  s.net.on("POST", /\/ext1\/media_publish$/, (c) => {
    publishes++;
    assert(c.form.creation_id, "creation_id is required");
    return jsonResponse({ id: "igmedia1" });
  });
  s.net.on("GET", /\/igmedia1\?/, () => jsonResponse({ permalink: "https://www.instagram.com/p/ABC/" }));
  return { created, publishes: () => publishes };
}

Deno.test("instagram: single photo publishes within one run", async () => {
  const s = scenario({ platform: "instagram", caption: "Sunday roast #food", media: [{ kind: "image" }] });
  const api = instagramApi(s);

  const summary = await runWorker(s.store, s.deps);
  assertEquals(summary.published, 1);
  assertEquals(api.created.length, 1);
  assertStringIncludes(api.created[0].image_url, "file0.jpg");
  assertEquals(api.created[0].caption, "Sunday roast #food");
  assertEquals(api.publishes(), 1);
  assertEquals(s.target.permalink, "https://www.instagram.com/p/ABC/");
});

Deno.test("instagram: a video is sent as a Reel and waits for processing", async () => {
  const s = scenario({ platform: "instagram", postType: "reel", media: [{ kind: "video" }] });
  const api = instagramApi(s, { statuses: ["IN_PROGRESS", "IN_PROGRESS", "FINISHED"] });

  for (let i = 0; i < 6 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(api.created.length, 1, "the container is created once");
  assertEquals(api.created[0].media_type, "REELS");
  assertStringIncludes(api.created[0].video_url, "file0.mp4");
  assertEquals(api.created[0].share_to_feed, "true");
  assertEquals(api.publishes(), 1);
});

Deno.test("instagram: carousel creates children, then a parent, then publishes once", async () => {
  const s = scenario({
    platform: "instagram",
    caption: "Swipe",
    media: [{ kind: "image" }, { kind: "video" }, { kind: "image" }],
  });
  const api = instagramApi(s);

  for (let i = 0; i < 6 && s.target.status !== "published"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "published");
  assertEquals(api.created.length, 4);
  assertEquals(api.created[0].is_carousel_item, "true");
  assertEquals(api.created[0].caption, undefined, "children carry no caption");
  assertEquals(api.created[1].media_type, "VIDEO");
  assertEquals(api.created[3].media_type, "CAROUSEL");
  assertEquals(api.created[3].children, "c1,c2,c3");
  assertEquals(api.created[3].caption, "Swipe");
  assertEquals(api.publishes(), 1);
});

Deno.test("instagram: story", async () => {
  const s = scenario({ platform: "instagram", postType: "story", media: [{ kind: "image" }] });
  const api = instagramApi(s);
  await runWorker(s.store, s.deps);
  assertEquals(s.target.status, "published");
  assertEquals(api.created[0].media_type, "STORIES");
  assertEquals(api.created[0].caption, undefined);
});

Deno.test("instagram: processing error is reported with Instagram's reason", async () => {
  const s = scenario({ platform: "instagram", postType: "reel", media: [{ kind: "video" }] });
  instagramApi(s, { statuses: ["ERROR"] });
  for (let i = 0; i < 3 && s.target.status !== "failed"; i++) {
    await runWorker(s.store, s.deps);
    s.clock.advance(60_000);
  }
  assertEquals(s.target.status, "failed");
  assertStringIncludes(s.target.error!, "unsupported video format");
});

Deno.test("instagram: an interrupted run does not publish twice", async () => {
  const s = scenario({ platform: "instagram", media: [{ kind: "image" }] });
  const api = instagramApi(s, { statuses: ["PUBLISHED"] });
  // The previous run created the container and published it, but died before
  // recording that: the saved state still says "wait" and is flagged in-flight.
  s.target.status = "processing";
  s.target.state = { phase: "wait", container: "c9", startedAt: s.clock.now(), inflight: true };

  const summary = await runWorker(s.store, s.deps);
  assertEquals(summary.published, 1);
  assertEquals(api.publishes(), 0, "already published - must not publish again");
  assertEquals(api.created.length, 0);
});

Deno.test("instagram: validation messages are clear", async () => {
  const none = scenario({ platform: "instagram" });
  await runWorker(none.store, none.deps);
  assertStringIncludes(none.target.error!, "need a photo or a video");

  const reel = scenario({ platform: "instagram", postType: "reel", media: [{ kind: "image" }] });
  await runWorker(reel.store, reel.deps);
  assertStringIncludes(reel.target.error!, "A Reel needs a video");
});
