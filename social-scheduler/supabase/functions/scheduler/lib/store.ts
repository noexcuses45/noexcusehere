// Supabase-backed implementation of the worker's Store, plus the admin client.

import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import type { CredentialKey, Credentials } from "./types.ts";
import type { AccountRow, MediaRow, PostRow, Store, TargetRow, TokenRow } from "./worker.ts";

export const MEDIA_BUCKET = "media";

/** The secret (service-role) key: new-style key dictionary first, legacy variable second. */
function secretKey(): string {
  const dict = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (dict) {
    try {
      const keys = JSON.parse(dict) as Record<string, string>;
      const key = keys.default ?? Object.values(keys)[0];
      if (key) return key;
    } catch {
      // fall through to the legacy variable
    }
  }
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!legacy) throw new Error("No Supabase secret key is available to this function.");
  return legacy;
}

export function supabaseUrl(): string {
  const url = Deno.env.get("SUPABASE_URL");
  if (!url) throw new Error("SUPABASE_URL is not set.");
  return url.replace(/\/+$/, "");
}

let cached: SupabaseClient | null = null;

export function adminClient(): SupabaseClient {
  if (!cached) {
    cached = createClient(supabaseUrl(), secretKey(), {
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }
  return cached;
}

function check<T>(res: { data: T; error: { message: string } | null }, what: string): T {
  if (res.error) throw new Error(`${what}: ${res.error.message}`);
  return res.data;
}

export class SupabaseStore implements Store {
  constructor(private db: SupabaseClient) {}

  async claimDueTargets(limit: number): Promise<TargetRow[]> {
    const data = check(await this.db.rpc("claim_due_targets", { p_limit: limit }), "claim_due_targets");
    return (data ?? []) as TargetRow[];
  }

  async getPost(id: string): Promise<PostRow | null> {
    return check(
      await this.db.from("posts").select("id, caption, link_url, status, scheduled_at").eq("id", id).maybeSingle(),
      "load post",
    ) as PostRow | null;
  }

  async getMedia(postId: string): Promise<MediaRow[]> {
    return (check(
      await this.db.from("post_media")
        .select("id, kind, storage_path, mime_type, size_bytes, width, height, position")
        .eq("post_id", postId).order("position"),
      "load media",
    ) ?? []) as MediaRow[];
  }

  async getAccount(id: string): Promise<AccountRow | null> {
    return check(
      await this.db.from("social_accounts")
        .select("id, platform, external_id, username, status, meta").eq("id", id).maybeSingle(),
      "load account",
    ) as AccountRow | null;
  }

  async getToken(accountId: string): Promise<TokenRow | null> {
    return check(
      await this.db.from("social_account_tokens")
        .select("access_token, refresh_token, expires_at, refresh_expires_at")
        .eq("account_id", accountId).maybeSingle(),
      "load token",
    ) as TokenRow | null;
  }

  async saveToken(accountId: string, token: TokenRow): Promise<void> {
    check(
      await this.db.from("social_account_tokens").upsert({
        account_id: accountId,
        ...token,
        updated_at: new Date().toISOString(),
      }),
      "save token",
    );
  }

  async updateTarget(id: string, patch: Record<string, unknown>): Promise<void> {
    check(await this.db.from("post_targets").update(patch).eq("id", id), "update target");
  }

  async setAccountStatus(id: string, status: "active" | "needs_reconnect", detail: string | null): Promise<void> {
    check(
      await this.db.from("social_accounts").update({ status, status_detail: detail }).eq("id", id),
      "update account",
    );
  }

  async signedUrl(path: string, expiresInSec: number): Promise<string> {
    const { data, error } = await this.db.storage.from(MEDIA_BUCKET).createSignedUrl(path, expiresInSec);
    if (error || !data?.signedUrl) throw new Error(`sign media link: ${error?.message ?? "no link returned"}`);
    return data.signedUrl;
  }

  async getMaxLateMinutes(): Promise<number> {
    const row = check(
      await this.db.from("app_config").select("max_late_minutes").eq("id", true).maybeSingle(),
      "load config",
    ) as { max_late_minutes: number } | null;
    return row?.max_late_minutes ?? 360;
  }

  async getCredentials(platform: CredentialKey): Promise<Credentials | null> {
    const row = check(
      await this.db.from("platform_credentials").select("client_id, client_secret, options")
        .eq("platform", platform).maybeSingle(),
      "load credentials",
    ) as { client_id: string | null; client_secret: string; options: Record<string, unknown> | null } | null;
    if (!row?.client_secret) return null;
    return { clientId: row.client_id ?? "", clientSecret: row.client_secret, options: row.options ?? {} };
  }

  async mediaToPurge(limit: number): Promise<{ media_id: string; storage_path: string }[]> {
    return (check(await this.db.rpc("media_to_purge", { p_limit: limit }), "media_to_purge") ?? []) as {
      media_id: string;
      storage_path: string;
    }[];
  }

  async removeMedia(paths: string[]): Promise<void> {
    if (paths.length === 0) return;
    const { error } = await this.db.storage.from(MEDIA_BUCKET).remove(paths);
    if (error) throw new Error(`remove media: ${error.message}`);
  }

  async clearMediaPaths(ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    check(await this.db.from("post_media").update({ storage_path: null }).in("id", ids), "clear media paths");
  }
}
