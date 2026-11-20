// What the code service does with a question, kept apart from where the codes are stored and
// from the web server around it - so exactly this code can be run in tests on any computer.
//
// The apps send one of two questions:
//   { "action": "unlock", "code": "...", "device": "...", "platform": "...", "version": "..." }
//   { "action": "check",  "note": "..." }
// and get { "ok": true, "note": "..." } or { "ok": false, "reason": "..." } back.
// A plain GET returns the public key that is built into the apps.

import { CODE_SHAPE, DEVICE_SHAPE, PLATFORMS, readNote, signNote } from "./note.ts";

/** Where the codes are kept. */
export interface Store {
  /** Puts a code to use on a device; see licence_unlock in schema.sql. */
  unlock(code: string, device: string, platform: string, version: string): Promise<Outcome>;
  /** "ok", "off" or "gone"; see licence_check in schema.sql. */
  check(codeId: string, device: string): Promise<string>;
}

export interface Outcome {
  status: string;
  code_id?: string;
  hint?: string;
  product?: string;
  devices?: number;
}

export interface Keys {
  signer: CryptoKey;
  checker: CryptoKey;
  publicSpki: string;
}

export interface Reply {
  status: number;
  body: Record<string, unknown>;
}

const ID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export async function handle(method: string, text: string, store: Store, keys: () => Promise<Keys>): Promise<Reply> {
  try {
    if (method === "GET") return { status: 200, body: { key: (await keys()).publicSpki } };
    if (method !== "POST") return { status: 405, body: { ok: false, reason: "bad" } };
    if (text.length > 4096) return { status: 413, body: { ok: false, reason: "bad" } };

    let ask: Record<string, unknown>;
    try {
      ask = JSON.parse(text);
    } catch {
      return { status: 400, body: { ok: false, reason: "bad" } };
    }
    if (ask === null || typeof ask !== "object") return { status: 400, body: { ok: false, reason: "bad" } };

    if (ask.action === "unlock") {
      const code = typeof ask.code === "string" ? ask.code : "";
      const device = typeof ask.device === "string" ? ask.device : "";
      const platform = typeof ask.platform === "string" && PLATFORMS.includes(ask.platform) ? ask.platform : "";
      const version = typeof ask.version === "string" ? ask.version.replace(/[^0-9A-Za-z.+-]/g, "").slice(0, 24) : "";
      // Something that cannot be a code is simply not a known code.
      if (!CODE_SHAPE.test(code)) return { status: 200, body: { ok: false, reason: "unknown" } };
      if (!DEVICE_SHAPE.test(device) || !platform) return { status: 400, body: { ok: false, reason: "bad" } };

      const outcome = await store.unlock(code, device, platform, version);
      if (outcome.status !== "ok" || !outcome.code_id) {
        const reason = ["unknown", "off", "full"].includes(outcome.status) ? outcome.status : "trouble";
        return { status: 200, body: { ok: false, reason, devices: outcome.devices ?? 0 } };
      }
      const note = await signNote((await keys()).signer, outcome.product ?? "umm", outcome.code_id, outcome.hint ?? "", device);
      return { status: 200, body: { ok: true, note } };
    }

    if (ask.action === "check") {
      const note = typeof ask.note === "string" && ask.note.length < 2048 ? ask.note : "";
      const body = note ? await readNote((await keys()).checker, note) : null;
      // A note this service never wrote is no proof of anything.
      if (!body || !ID_SHAPE.test(body.c) || !DEVICE_SHAPE.test(body.d)) return { status: 200, body: { ok: false, reason: "gone" } };
      const state = await store.check(body.c, body.d);
      if (state === "ok") return { status: 200, body: { ok: true } };
      return { status: 200, body: { ok: false, reason: state === "off" ? "off" : "gone" } };
    }

    return { status: 400, body: { ok: false, reason: "bad" } };
  } catch (problem) {
    console.error("licence:", problem instanceof Error ? problem.message : problem);
    return { status: 500, body: { ok: false, reason: "trouble" } };
  }
}
