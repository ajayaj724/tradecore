// OIDC access-token rotation for the Auth.js `jwt` callback. Pure logic — the caller wires
// in the Keycloak endpoint config, fetch, and clock so this can be tested deterministically.

export interface TokenBundle {
  access_token?: string;
  expires_at?: number; // seconds since epoch, as issued by the provider
  refresh_token?: string;
  error?: "RefreshTokenError";
  [claim: string]: unknown;
}

export interface AccountTokens {
  access_token?: string;
  expires_at?: number;
  refresh_token?: string;
  id_token?: string;
}

export interface RefreshConfig {
  tokenEndpoint: string;
  clientId: string;
  clientSecret: string;
}

/** Refresh this many seconds before nominal expiry so a token never dies mid-request. */
const EXPIRY_BUFFER_SECONDS = 30;

export async function rotateToken(
  token: TokenBundle,
  account: AccountTokens | null | undefined,
  config: RefreshConfig,
  fetchFn: typeof fetch = fetch,
  nowMs: () => number = Date.now,
): Promise<TokenBundle> {
  if (account) {
    return {
      ...token,
      access_token: account.access_token,
      expires_at: account.expires_at,
      refresh_token: account.refresh_token,
      id_token: account.id_token,
    };
  }
  const nowSeconds = Math.floor(nowMs() / 1000);
  if (token.expires_at !== undefined && nowSeconds < token.expires_at - EXPIRY_BUFFER_SECONDS) {
    return token;
  }
  if (!token.refresh_token) {
    return { ...token, error: "RefreshTokenError" };
  }
  const response = await fetchFn(config.tokenEndpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: token.refresh_token,
      client_id: config.clientId,
      client_secret: config.clientSecret,
    }),
    cache: "no-store",
  });
  if (!response.ok) {
    return { ...token, error: "RefreshTokenError" };
  }
  const fresh = (await response.json()) as {
    access_token: string;
    expires_in: number;
    refresh_token?: string;
  };
  return {
    ...token,
    access_token: fresh.access_token,
    expires_at: nowSeconds + fresh.expires_in,
    refresh_token: fresh.refresh_token ?? token.refresh_token,
    error: undefined,
  };
}
