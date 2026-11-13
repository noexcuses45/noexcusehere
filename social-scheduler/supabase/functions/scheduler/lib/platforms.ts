// One place that knows every supported platform: how to publish to it, how
// to sign in, and how to refresh its tokens.

import { facebookStep, instagramStep, metaAuthorizeUrl, metaConnect } from "./meta.ts";
import { linkedinAuthorizeUrl, linkedinConnect, linkedinRefresh, linkedinStep } from "./linkedin.ts";
import { tiktokAuthorizeUrl, tiktokExchangeCode, tiktokRefresh, tiktokStep, tiktokUserInfo } from "./tiktok.ts";
import { googleAuthorizeUrl, googleRefresh, youtubeConnect, youtubeStep } from "./youtube.ts";
import type { ConnectedAccount, CredentialKey, Credentials, FetchFn, Platform, Publisher, TokenSet } from "./types.ts";

export type Refresher = (
  fetchFn: FetchFn,
  creds: Credentials,
  refreshToken: string,
  now: number,
) => Promise<TokenSet>;

export interface PlatformInfo {
  label: string;
  credentials: CredentialKey;
  publish: Publisher;
  refresh?: Refresher;
  /**
   * Phases a publisher can safely re-enter after an interrupted run. Anything
   * else might already have created a post, so it is never re-run blindly.
   */
  resumablePhases: string[];
}

export const PLATFORMS: Record<Platform, PlatformInfo> = {
  facebook: {
    label: "Facebook",
    credentials: "meta",
    publish: facebookStep,
    resumablePhases: ["video_wait"],
  },
  instagram: {
    label: "Instagram",
    credentials: "meta",
    publish: instagramStep,
    resumablePhases: ["children", "wait"],
  },
  tiktok: {
    label: "TikTok",
    credentials: "tiktok",
    publish: tiktokStep,
    refresh: tiktokRefresh,
    resumablePhases: ["uploading", "wait"],
  },
  youtube: {
    label: "YouTube",
    credentials: "google",
    publish: youtubeStep,
    refresh: googleRefresh,
    resumablePhases: ["uploading", "wait"],
  },
  linkedin: {
    label: "LinkedIn",
    credentials: "linkedin",
    publish: linkedinStep,
    refresh: linkedinRefresh,
    resumablePhases: ["video_wait"],
  },
};

export type OAuthKey = "meta" | "tiktok" | "google" | "linkedin";

export interface OAuthProvider {
  label: string;
  authorizeUrl(creds: Credentials, redirectUri: string, state: string): string;
  connect(
    fetchFn: FetchFn,
    creds: Credentials,
    code: string,
    redirectUri: string,
    now: number,
  ): Promise<ConnectedAccount[]>;
}

export const OAUTH: Record<OAuthKey, OAuthProvider> = {
  meta: {
    label: "Facebook & Instagram",
    authorizeUrl: (creds, redirectUri, state) => metaAuthorizeUrl(creds.clientId, redirectUri, state),
    connect: (fetchFn, creds, code, redirectUri) => metaConnect(fetchFn, creds, code, redirectUri),
  },
  tiktok: {
    label: "TikTok",
    authorizeUrl: (creds, redirectUri, state) =>
      tiktokAuthorizeUrl(creds.clientId, redirectUri, state, creds.options.direct_post === true),
    connect: async (fetchFn, creds, code, redirectUri, now) => {
      const tokens = await tiktokExchangeCode(fetchFn, creds, code, redirectUri, now);
      const user = await tiktokUserInfo(fetchFn, tokens.accessToken);
      return [{
        platform: "tiktok",
        externalId: user.openId || tokens.openId,
        name: user.displayName,
        avatarUrl: user.avatarUrl,
        meta: { scopes: tokens.scope },
        ...tokens,
      }];
    },
  },
  google: {
    label: "YouTube",
    authorizeUrl: (creds, redirectUri, state) => googleAuthorizeUrl(creds.clientId, redirectUri, state),
    connect: youtubeConnect,
  },
  linkedin: {
    label: "LinkedIn",
    authorizeUrl: (creds, redirectUri, state) =>
      linkedinAuthorizeUrl(creds.clientId, redirectUri, state, creds.options.pages === true),
    connect: linkedinConnect,
  },
};

export function isOAuthKey(value: unknown): value is OAuthKey {
  return typeof value === "string" && value in OAUTH;
}
