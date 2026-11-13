// Small HTTP helpers shared by the routes.

import type { SupabaseClient } from "@supabase/supabase-js";
import { HttpError } from "./errors.ts";

export const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-cron-secret",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
  });
}

export async function readJson(req: Request): Promise<Record<string, unknown>> {
  try {
    const body = await req.json();
    return body && typeof body === "object" ? (body as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

export interface Member {
  userId: string;
  email: string | null;
  role: string;
}

/** Validates the caller's session token and checks they belong to this workspace. */
export async function requireMember(req: Request, db: SupabaseClient): Promise<Member> {
  const header = req.headers.get("authorization") ?? "";
  const token = header.toLowerCase().startsWith("bearer ") ? header.slice(7).trim() : "";
  if (!token) throw new HttpError(401, "Sign in first.");

  const { data, error } = await db.auth.getUser(token);
  if (error || !data?.user) throw new HttpError(401, "Your session has expired. Sign in again.");

  const { data: member, error: memberError } = await db.from("members")
    .select("role").eq("user_id", data.user.id).maybeSingle();
  if (memberError) throw new Error(`load member: ${memberError.message}`);
  if (!member) throw new HttpError(403, "This account does not have access to this workspace.");

  return { userId: data.user.id, email: data.user.email ?? null, role: String(member.role) };
}

export function randomState(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time string comparison for shared secrets. */
export function safeEqual(a: string, b: string): boolean {
  const ea = new TextEncoder().encode(a);
  const eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let diff = 0;
  for (let i = 0; i < ea.length; i++) diff |= ea[i] ^ eb[i];
  return diff === 0;
}
