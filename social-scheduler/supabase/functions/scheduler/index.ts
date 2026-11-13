// Social Scheduler - single Edge Function with a small router.
//
//   GET  /status            is there an owner yet? (public)
//   POST /claim-owner       create the first account (public, one time)
//   POST /oauth/start       begin connecting a platform (member)
//   GET  /oauth/callback    platform redirects here after consent (public, state-checked)
//   POST /publish-due       run the publisher (cron secret, or member)
//   POST /ai/caption        AI caption ideas (member)
//
// Deployed with verify_jwt = false: every route authenticates in code, which
// is what Supabase's current API-key model expects.

import { errorMessage, HttpError } from "./lib/errors.ts";
import { CORS_HEADERS, json } from "./lib/http.ts";
import { aiCaption, claimOwner, FUNCTION_NAME, oauthCallback, oauthStart, publishDue, status } from "./lib/routes.ts";
import { adminClient } from "./lib/store.ts";

function routeOf(req: Request): string {
  const parts = new URL(req.url).pathname.split("/").filter(Boolean);
  const at = parts.lastIndexOf(FUNCTION_NAME);
  return "/" + parts.slice(at + 1).join("/");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS_HEADERS });

  const route = routeOf(req);
  try {
    const db = adminClient();
    if (req.method === "GET" && (route === "/status" || route === "/")) return await status(db);
    if (req.method === "POST" && route === "/claim-owner") return await claimOwner(req, db);
    if (req.method === "POST" && route === "/oauth/start") return await oauthStart(req, db);
    if (req.method === "GET" && route === "/oauth/callback") return await oauthCallback(req, db);
    if (req.method === "POST" && route === "/publish-due") return await publishDue(req, db);
    if (req.method === "POST" && route === "/ai/caption") return await aiCaption(req, db);
    return json({ error: "Not found." }, 404);
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status);
    console.error(`scheduler ${req.method} ${route} failed:`, errorMessage(e));
    return json({ error: "Something went wrong on the server. Try again." }, 500);
  }
});
