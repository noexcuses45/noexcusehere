// The code service for Ultimate Media Manager, as it runs on Supabase.
//
// What it does with a question is in service.ts; this file only connects that to the database
// and to the web. It keeps nothing about who is asking: no addresses, no names. A device is
// only ever a fingerprint the app made up.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { importPrivate, importPublic, makeKeys } from "./note.ts";
import { handle, type Keys, type Outcome, type Store } from "./service.ts";

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const store: Store = {
  async unlock(code, device, platform, version) {
    const result = await db.rpc("licence_unlock", { p_code: code, p_device: device, p_platform: platform, p_version: version });
    if (result.error) throw new Error(result.error.message);
    return result.data as Outcome;
  },
  async check(codeId, device) {
    const result = await db.rpc("licence_check", { p_code_id: codeId, p_device: device });
    if (result.error) throw new Error(result.error.message);
    return String(result.data);
  },
};

/** The signing key: read from the database, or made and stored the very first time. */
async function loadKeys(): Promise<Keys> {
  let found = await db.from("licence_secret").select("private_jwk, public_spki").eq("id", 1).maybeSingle();
  if (found.error) throw new Error("cannot read the key: " + found.error.message);
  if (!found.data) {
    const made = await makeKeys();
    // If two copies of the service start at once, only one row is kept; both then read that one.
    await db.from("licence_secret").upsert(
      { id: 1, private_jwk: made.privateJwk, public_spki: made.publicSpki },
      { onConflict: "id", ignoreDuplicates: true },
    );
    found = await db.from("licence_secret").select("private_jwk, public_spki").eq("id", 1).maybeSingle();
    if (found.error || !found.data) throw new Error("cannot store the key");
  }
  return {
    signer: await importPrivate(found.data.private_jwk),
    checker: await importPublic(found.data.public_spki),
    publicSpki: found.data.public_spki,
  };
}

let loading: Promise<Keys> | null = null;

function keys(): Promise<Keys> {
  if (!loading) {
    loading = loadKeys().catch((problem) => {
      loading = null;
      throw problem;
    });
  }
  return loading;
}

Deno.serve(async (request) => {
  const text = request.method === "POST" ? await request.text() : "";
  const reply = await handle(request.method, text, store, keys);
  return new Response(JSON.stringify(reply.body), {
    status: reply.status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
});
