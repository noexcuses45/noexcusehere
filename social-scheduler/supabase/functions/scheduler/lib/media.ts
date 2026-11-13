// Helpers for relaying a stored file to a platform in byte ranges, so large
// videos never have to fit in memory at once.

import { PublishError } from "./errors.ts";
import type { FetchFn } from "./types.ts";

export const MB = 1024 * 1024;

/** Real size of the stored file (the app-reported size is only a fallback). */
export async function sourceSize(fetchFn: FetchFn, url: string, fallback: number): Promise<number> {
  try {
    const res = await fetchFn(url, { headers: { Range: "bytes=0-0" } });
    await res.arrayBuffer();
    const range = res.headers.get("content-range");
    const total = range ? Number(range.split("/")[1]) : NaN;
    if (Number.isFinite(total) && total > 0) return total;
    const len = Number(res.headers.get("content-length"));
    if (res.status === 200 && Number.isFinite(len) && len > 0) return len;
  } catch {
    // fall through
  }
  if (!fallback || fallback <= 0) {
    throw new PublishError("Could not read the media file from storage.", { transient: true });
  }
  return fallback;
}

/** Reads bytes [first, last] (inclusive) of the stored file. */
export async function readRange(
  fetchFn: FetchFn,
  url: string,
  first: number,
  last: number,
  total: number,
): Promise<Uint8Array> {
  let res: Response;
  try {
    res = await fetchFn(url, { headers: { Range: `bytes=${first}-${last}` } });
  } catch (e) {
    throw new PublishError(`Could not read the media file from storage (${e instanceof Error ? e.message : e}).`, {
      transient: true,
    });
  }
  if (!res.ok) {
    await res.arrayBuffer().catch(() => undefined);
    throw new PublishError(`Could not read the media file from storage (HTTP ${res.status}).`, {
      transient: res.status >= 500 || res.status === 429,
    });
  }
  let bytes = new Uint8Array(await res.arrayBuffer());
  if (res.status === 200 && bytes.byteLength === total && bytes.byteLength !== last - first + 1) {
    // Storage ignored the Range header and sent the whole file.
    bytes = bytes.subarray(first, last + 1);
  }
  if (bytes.byteLength !== last - first + 1) {
    throw new PublishError("The media file in storage is incomplete. Upload it again and retry.");
  }
  return bytes;
}

/** Reads a whole stored file (images and small videos). */
export async function readAll(fetchFn: FetchFn, url: string): Promise<Uint8Array> {
  let res: Response;
  try {
    res = await fetchFn(url);
  } catch (e) {
    throw new PublishError(`Could not read the media file from storage (${e instanceof Error ? e.message : e}).`, {
      transient: true,
    });
  }
  if (!res.ok) {
    await res.arrayBuffer().catch(() => undefined);
    throw new PublishError(`Could not read the media file from storage (HTTP ${res.status}).`, {
      transient: res.status >= 500 || res.status === 429,
    });
  }
  return new Uint8Array(await res.arrayBuffer());
}

/** Typed-array request body (TypeScript's fetch typings are stricter than the runtime). */
export function asBody(bytes: Uint8Array): BodyInit {
  return bytes as unknown as BodyInit;
}
