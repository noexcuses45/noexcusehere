// Shared types for the scheduler Edge Function.

export type Platform = "facebook" | "instagram" | "tiktok" | "youtube" | "linkedin";

/** Which developer-app credentials a platform uses. */
export type CredentialKey = "meta" | "tiktok" | "google" | "linkedin" | "anthropic";

export interface Credentials {
  clientId: string;
  clientSecret: string;
  options: Record<string, unknown>;
}

/** Tokens as returned by a sign-in or refresh. */
export interface TokenSet {
  accessToken: string;
  refreshToken?: string | null;
  expiresAt?: string | null;
  refreshExpiresAt?: string | null;
  scope?: string | null;
}

/** An account discovered while connecting a platform. */
export interface ConnectedAccount extends TokenSet {
  platform: Platform;
  externalId: string;
  name: string;
  username?: string;
  avatarUrl?: string;
  meta: Record<string, unknown>;
}

export type FetchFn = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

export interface MediaItem {
  kind: "image" | "video";
  /** Short-lived public link the platform (or we) can download from. */
  url: string;
  mimeType: string;
  sizeBytes: number;
  width?: number | null;
  height?: number | null;
}

export interface PublishJob {
  targetId: string;
  platform: Platform;
  /** instagram: feed | reel | story - facebook: feed | reel - tiktok: direct | inbox - youtube: video - linkedin: feed */
  postType: string;
  caption: string;
  linkUrl?: string | null;
  options: Record<string, unknown>;
  /** Progress carried between worker runs (container ids, publish ids, phase). */
  state: Record<string, unknown>;
  media: MediaItem[];
  account: {
    id: string;
    externalId: string;
    username?: string | null;
    meta: Record<string, unknown>;
  };
  accessToken: string;
}

export type StepResult =
  | { kind: "published"; externalId?: string; permalink?: string; note?: string }
  | { kind: "wait"; state: Record<string, unknown>; retryInSec: number };

export interface StepContext {
  fetch: FetchFn;
  now: () => number;
  /**
   * Saves progress in the middle of a step (e.g. an upload session that has
   * just been opened), so an interrupted run can pick up from there.
   */
  checkpoint: (state: Record<string, unknown>) => Promise<void>;
}

export type Publisher = (job: PublishJob, ctx: StepContext) => Promise<StepResult>;
