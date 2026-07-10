import { describe, expect, it, vi } from "vitest";
import { realmRoles, rotateToken, type RefreshConfig } from "./session-token";

const config: RefreshConfig = {
  tokenEndpoint: "http://kc.test/realms/tradecore/protocol/openid-connect/token",
  clientId: "tradecore-web",
  clientSecret: "s3cret",
};

const NOW = 1_752_000_000_000; // fixed ms epoch
const now = () => NOW;
const sec = (ms: number) => Math.floor(ms / 1000);

const fetchNever = () => {
  throw new Error("token endpoint must not be called");
};

describe("rotateToken", () => {
  it("persistsTokensOnInitialSignIn", async () => {
    const account = {
      access_token: "at-1",
      expires_at: sec(NOW) + 300,
      refresh_token: "rt-1",
      id_token: "idt-1",
    };
    const token = await rotateToken({ name: "trader1" }, account, config, fetchNever, now);
    expect(token).toMatchObject({
      name: "trader1",
      access_token: "at-1",
      expires_at: sec(NOW) + 300,
      refresh_token: "rt-1",
      // kept for RP-initiated logout (id_token_hint) so sign-out also ends the Keycloak SSO session
      id_token: "idt-1",
    });
  });

  it("returnsTokenUnchangedWhileAccessTokenValid", async () => {
    const token = { access_token: "at-1", expires_at: sec(NOW) + 120, refresh_token: "rt-1" };
    expect(await rotateToken(token, null, config, fetchNever, now)).toEqual(token);
  });

  it("refreshesExpiredTokenViaTokenEndpoint", async () => {
    const fetchFn = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      expect(String(url)).toBe(config.tokenEndpoint);
      const params = new URLSearchParams(String(init?.body));
      expect(params.get("grant_type")).toBe("refresh_token");
      expect(params.get("refresh_token")).toBe("rt-1");
      expect(params.get("client_id")).toBe("tradecore-web");
      expect(params.get("client_secret")).toBe("s3cret");
      return Response.json({ access_token: "at-2", expires_in: 300 });
    });
    const expired = { access_token: "at-1", expires_at: sec(NOW) - 10, refresh_token: "rt-1" };
    const token = await rotateToken(expired, null, config, fetchFn as typeof fetch, now);
    expect(fetchFn).toHaveBeenCalledOnce();
    expect(token.access_token).toBe("at-2");
    expect(token.expires_at).toBe(sec(NOW) + 300);
    expect(token.refresh_token).toBe("rt-1"); // provider sent no new one — keep the old
    expect(token.error).toBeUndefined();
  });

  it("rotatesRefreshTokenWhenProviderIssuesNewOne", async () => {
    const fetchFn = async () =>
      Response.json({ access_token: "at-2", expires_in: 300, refresh_token: "rt-2" });
    const expired = { access_token: "at-1", expires_at: sec(NOW) - 10, refresh_token: "rt-1" };
    const token = await rotateToken(expired, null, config, fetchFn as typeof fetch, now);
    expect(token.refresh_token).toBe("rt-2");
  });

  it("refreshesInsideExpiryBufferToAvoidUsingAboutToExpireToken", async () => {
    const fetchFn = vi.fn(async () => Response.json({ access_token: "at-2", expires_in: 300 }));
    // Still 10s of nominal validity left — inside the 30s skew buffer, so refresh anyway.
    const nearlyExpired = {
      access_token: "at-1",
      expires_at: sec(NOW) + 10,
      refresh_token: "rt-1",
    };
    const token = await rotateToken(nearlyExpired, null, config, fetchFn as typeof fetch, now);
    expect(fetchFn).toHaveBeenCalledOnce();
    expect(token.access_token).toBe("at-2");
  });

  it("marksErrorWhenRefreshIsRejected", async () => {
    const fetchFn = async () =>
      Response.json({ error: "invalid_grant" }, { status: 400 });
    const expired = { access_token: "at-1", expires_at: sec(NOW) - 10, refresh_token: "rt-1" };
    const token = await rotateToken(expired, null, config, fetchFn as typeof fetch, now);
    expect(token.error).toBe("RefreshTokenError");
  });

  it("marksErrorWhenExpiredWithoutRefreshToken", async () => {
    const expired = { access_token: "at-1", expires_at: sec(NOW) - 10 };
    const token = await rotateToken(expired, null, config, fetchNever, now);
    expect(token.error).toBe("RefreshTokenError");
  });
});

describe("realmRoles", () => {
  const jwtWith = (payload: unknown) =>
    `header.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.sig`;

  it("extractsKeycloakRealmRolesFromTheAccessToken", () => {
    const token = jwtWith({ realm_access: { roles: ["TRADER", "offline_access"] } });
    expect(realmRoles(token)).toEqual(["TRADER", "offline_access"]);
  });

  it("returnsNoRolesWhenTheClaimIsAbsent", () => {
    expect(realmRoles(jwtWith({ sub: "x" }))).toEqual([]);
  });

  it("returnsNoRolesForAMalformedToken", () => {
    expect(realmRoles("not-a-jwt")).toEqual([]);
  });
});
