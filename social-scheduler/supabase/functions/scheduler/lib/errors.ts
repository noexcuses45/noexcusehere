/**
 * Raised by publishers.
 *  - transient: worth retrying later (rate limit, 5xx, network blip)
 *  - reconnect: the account's access has been revoked or expired
 *  - ambiguous: the request may have reached the platform; never auto-retry,
 *    or the post could go out twice
 */
export class PublishError extends Error {
  transient: boolean;
  reconnect: boolean;
  ambiguous: boolean;
  /** The platform's own error code, when it gave one. */
  code: string;
  status: number;

  constructor(
    message: string,
    opts: { transient?: boolean; reconnect?: boolean; ambiguous?: boolean; code?: string; status?: number } = {},
  ) {
    super(message);
    this.name = "PublishError";
    this.transient = opts.transient ?? false;
    this.reconnect = opts.reconnect ?? false;
    this.ambiguous = opts.ambiguous ?? false;
    this.code = opts.code ?? "";
    this.status = opts.status ?? 0;
  }
}

export class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "HttpError";
    this.status = status;
  }
}

export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  return String(e);
}
