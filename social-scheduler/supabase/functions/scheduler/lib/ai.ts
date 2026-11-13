// AI caption writer (optional). Uses the workspace's own Anthropic API key,
// which the owner stores from the app (Tools > AI captions).

import Anthropic from "@anthropic-ai/sdk";
import { HttpError } from "./errors.ts";

export interface CaptionRequest {
  brief: string;
  platforms: string[];
  tone?: string;
  draft?: string;
  business?: string;
  hashtags?: boolean;
  emoji?: boolean;
}

export interface CaptionResult {
  captions: string[];
  hashtags: string[];
}

const SCHEMA = {
  type: "object",
  properties: {
    captions: {
      type: "array",
      description: "Three distinct caption options, ready to post.",
      items: { type: "string" },
    },
    hashtags: {
      type: "array",
      description: "Suggested hashtags, each starting with #. Empty when hashtags were not requested.",
      items: { type: "string" },
    },
  },
  required: ["captions", "hashtags"],
  additionalProperties: false,
} as const;

const SYSTEM = `You write social media captions for a small business owner who schedules their own posts.

Write the way a person who runs the business would write: specific, warm, and plain. Lead with the most interesting concrete detail, not a generic hook. Keep any claims to what the brief actually says; never invent prices, dates, offers, statistics or testimonials. Match the spelling conventions of the brief (for example Australian or British English if the brief uses it).

Give three options that differ in angle and length (one short, one medium, one fuller), so the owner has a real choice. Put hashtags only in the separate hashtags list, never inside the captions. Each caption is plain text with line breaks where they help; no markdown.`;

export function buildPrompt(req: CaptionRequest): string {
  const platforms = req.platforms.length > 0 ? req.platforms.join(", ") : "Instagram, Facebook and TikTok";
  const lines = [
    `Platforms this will be posted to: ${platforms}.`,
    `Tone: ${req.tone?.trim() || "friendly and natural"}.`,
    `Emoji: ${req.emoji === false ? "do not use emoji" : "a few are fine where they fit"}.`,
    `Hashtags: ${req.hashtags === false ? "not wanted - return an empty hashtags list" : "suggest 8 to 15 relevant ones, mixing broad and niche"}.`,
  ];
  if (req.business?.trim()) lines.push(`About the business: ${req.business.trim()}`);
  lines.push("", "What the post is about:", req.brief.trim());
  if (req.draft?.trim()) {
    lines.push("", "The owner's current draft (improve on it, keep their facts and voice):", req.draft.trim());
  }
  return lines.join("\n");
}

export function parseCaptionJson(text: string): CaptionResult {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new HttpError(502, "The AI returned an unreadable answer. Try again.");
  }
  const obj = (parsed ?? {}) as Record<string, unknown>;
  const captions = Array.isArray(obj.captions)
    ? obj.captions.map((c) => String(c).trim()).filter((c) => c.length > 0)
    : [];
  const hashtags = Array.isArray(obj.hashtags)
    ? obj.hashtags
      .map((h) => String(h).trim().replace(/\s+/g, ""))
      .filter((h) => h.length > 1)
      .map((h) => (h.startsWith("#") ? h : `#${h}`))
    : [];
  if (captions.length === 0) throw new HttpError(502, "The AI did not return any captions. Try again.");
  return { captions, hashtags: [...new Set(hashtags)] };
}

export async function generateCaptions(apiKey: string, req: CaptionRequest): Promise<CaptionResult> {
  if (!req.brief?.trim() && !req.draft?.trim()) {
    throw new HttpError(400, "Describe what the post is about first.");
  }
  const client = new Anthropic({ apiKey });

  let response;
  try {
    response = await client.beta.messages.create({
      model: "claude-opus-5-5",
      max_tokens: 16000,
      // If a safety classifier declines the request, the API re-runs it on a
      // fallback model inside the same call instead of failing.
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      thinking: { type: "adaptive" },
      output_config: {
        effort: "low",
        format: { type: "json_schema", schema: SCHEMA },
      },
      system: SYSTEM,
      messages: [{ role: "user", content: buildPrompt(req) }],
    });
  } catch (error) {
    if (error instanceof Anthropic.AuthenticationError || error instanceof Anthropic.PermissionDeniedError) {
      throw new HttpError(400, "The Anthropic API key was rejected. Check it under Tools > AI captions.");
    }
    if (error instanceof Anthropic.RateLimitError) {
      throw new HttpError(429, "The AI service is rate limited right now. Try again in a minute.");
    }
    if (error instanceof Anthropic.BadRequestError) {
      throw new HttpError(502, `The AI service rejected the request: ${error.message}`);
    }
    if (error instanceof Anthropic.APIError) {
      throw new HttpError(502, `The AI service had a problem (${error.status ?? "network"}). Try again.`);
    }
    throw error;
  }

  if (response.stop_reason === "refusal") {
    throw new HttpError(422, "The AI declined to write this one. Try rewording the description.");
  }
  if (response.stop_reason === "max_tokens") {
    throw new HttpError(502, "The AI answer was cut off. Try a shorter description.");
  }
  const text = response.content
    .filter((block) => block.type === "text")
    .map((block) => (block.type === "text" ? block.text : ""))
    .join("");
  return parseCaptionJson(text);
}
