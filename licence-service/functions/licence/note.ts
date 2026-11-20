// The signed note the code service hands to an unlocked device, and the key it is signed with.
// Nothing here touches the network or the database, so it runs the same in the service and in tests.

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function toBase64(bytes: Uint8Array): string {
  let text = "";
  for (const b of bytes) text += String.fromCharCode(b);
  return btoa(text);
}

export function toBase64Url(bytes: Uint8Array): string {
  return toBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromBase64Url(text: string): Uint8Array {
  const plain = text.replace(/-/g, "+").replace(/_/g, "/");
  const padded = plain + "=".repeat((4 - (plain.length % 4)) % 4);
  const raw = atob(padded);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

const ALGORITHM = { name: "ECDSA", namedCurve: "P-256" };
const SIGNING = { name: "ECDSA", hash: "SHA-256" };

/** A new key pair: the private half to keep, and the public half in the form the apps hold. */
export async function makeKeys(): Promise<{ privateJwk: JsonWebKey; publicSpki: string }> {
  const pair = await crypto.subtle.generateKey(ALGORITHM, true, ["sign", "verify"]);
  const privateJwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
  const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
  return { privateJwk, publicSpki: toBase64(spki) };
}

export function importPrivate(jwk: JsonWebKey): Promise<CryptoKey> {
  return crypto.subtle.importKey("jwk", jwk, ALGORITHM, false, ["sign"]);
}

export function importPublic(spki: string): Promise<CryptoKey> {
  const plain = atob(spki);
  const bytes = new Uint8Array(plain.length);
  for (let i = 0; i < plain.length; i++) bytes[i] = plain.charCodeAt(i);
  return crypto.subtle.importKey("spki", bytes, ALGORITHM, false, ["verify"]);
}

export interface NoteBody {
  /** Version of this layout. */
  v: number;
  /** Product. */
  p: string;
  /** The code's id in the list (not the code itself). */
  c: string;
  /** The last characters of the code, for the app to show. */
  h: string;
  /** The device's fingerprint. */
  d: string;
  /** When it was written, in seconds. */
  t: number;
}

/** Writes and signs a note: "this code is in use on this device". */
export async function signNote(key: CryptoKey, product: string, codeId: string, hint: string, device: string): Promise<string> {
  const body: NoteBody = { v: 1, p: product, c: codeId, h: hint, d: device, t: Math.floor(Date.now() / 1000) };
  const signed = toBase64Url(encoder.encode(JSON.stringify(body)));
  const signature = new Uint8Array(await crypto.subtle.sign(SIGNING, key, encoder.encode(signed)));
  return signed + "." + toBase64Url(signature);
}

/** What a note says, or null when it is not one of ours. */
export async function readNote(key: CryptoKey, note: string): Promise<NoteBody | null> {
  try {
    const parts = note.split(".");
    if (parts.length !== 2 || !parts[0] || !parts[1]) return null;
    const good = await crypto.subtle.verify(SIGNING, key, fromBase64Url(parts[1]), encoder.encode(parts[0]));
    if (!good) return null;
    const body = JSON.parse(decoder.decode(fromBase64Url(parts[0]))) as NoteBody;
    if (body.v !== 1 || typeof body.c !== "string" || typeof body.d !== "string") return null;
    return body;
  } catch {
    return null;
  }
}

export const CODE_SHAPE = /^[A-HJKMNP-Z2-9]{15}$/;
export const DEVICE_SHAPE = /^[0-9a-f]{32}$/;
export const PLATFORMS = ["android", "androidtv", "windows", "linux", "mac"];
