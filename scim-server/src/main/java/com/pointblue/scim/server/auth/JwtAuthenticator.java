// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.auth;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Validates OAuth 2.0 bearer JWTs issued by an external IdP (resource-server
 * mode). Checks the RS256/384/512 signature against the IdP's JWKS, then the
 * issuer, audience, expiry/not-before, and any required scopes. Generic: the
 * IdP is described entirely by config (jwksUrl / issuer / audience / scopes),
 * so the same Okta org used as a backend — or any standards-compliant IdP —
 * can issue inbound tokens.
 */
public final class JwtAuthenticator implements Authenticator {

    private static final Logger log = Logger.getLogger("scim.auth");
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    public record Config(String jwksUrl, String issuer, String audience,
                         List<String> requiredScopes, String principalClaim, long clockSkewSeconds) {}

    private final JwksCache jwks;
    private final Config config;

    public JwtAuthenticator(HttpClient http, Config config) {
        this.config = config;
        this.jwks = new JwksCache(http, config.jwksUrl(),
                Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    @Override
    public String authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = authorizationHeader.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null; // not a JWS — let a static-bearer authenticator try
        try {
            JsonObject header = decode(parts[0]);
            JsonObject claims = decode(parts[1]);
            if (!verifySignature(header, parts, token)) return deny("bad signature");
            if (!checkTime(claims)) return deny("expired or not yet valid");
            if (!checkIssuer(claims)) return deny("issuer mismatch");
            if (!checkAudience(claims)) return deny("audience mismatch");
            if (!checkScopes(claims)) return deny("missing required scope");
            String principal = claims.optString(config.principalClaim(), null);
            return principal != null ? principal : claims.optString("sub", "jwt-client");
        } catch (RuntimeException malformed) {
            return null; // malformed JWT: not a credential we accept
        }
    }

    private boolean verifySignature(JsonObject header, String[] parts, String token) throws RuntimeException {
        String alg = header.optString("alg", "");
        String sigAlg = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            default -> null;
        };
        if (sigAlg == null) { deny("unsupported alg " + alg); return false; }
        PublicKey key = jwks.key(header.optString("kid", null));
        if (key == null) { deny("no JWKS key for kid"); return false; }
        try {
            Signature sig = Signature.getInstance(sigAlg);
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return sig.verify(B64URL.decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean checkTime(JsonObject claims) {
        long now = Instant.now().getEpochSecond();
        long skew = config.clockSkewSeconds();
        JsonValue exp = claims.getIgnoreCase("exp");
        if (exp != null && !exp.isNull() && exp.asNumber().longValue() + skew < now) return false;
        JsonValue nbf = claims.getIgnoreCase("nbf");
        if (nbf != null && !nbf.isNull() && nbf.asNumber().longValue() - skew > now) return false;
        return true;
    }

    private boolean checkIssuer(JsonObject claims) {
        if (config.issuer() == null) return true;
        return config.issuer().equals(claims.optString("iss", null));
    }

    private boolean checkAudience(JsonObject claims) {
        if (config.audience() == null) return true;
        JsonValue aud = claims.getIgnoreCase("aud");
        if (aud == null) return false;
        if (aud.isString()) return config.audience().equals(aud.asString());
        if (aud instanceof JsonArray arr) {
            for (JsonValue v : arr) if (v.isString() && config.audience().equals(v.asString())) return true;
        }
        return false;
    }

    private boolean checkScopes(JsonObject claims) {
        if (config.requiredScopes() == null || config.requiredScopes().isEmpty()) return true;
        Set<String> granted = new LinkedHashSet<>();
        // "scope": space-delimited string (RFC 8693) or "scp": array (Okta/Entra)
        JsonValue scope = claims.getIgnoreCase("scope");
        if (scope != null && scope.isString()) {
            for (String s : scope.asString().split("\\s+")) if (!s.isBlank()) granted.add(s);
        }
        JsonValue scp = claims.getIgnoreCase("scp");
        if (scp instanceof JsonArray arr) {
            for (JsonValue v : arr) if (v.isString()) granted.add(v.asString());
        }
        return granted.containsAll(config.requiredScopes());
    }

    private static JsonObject decode(String segment) {
        return Json.parseObject(new String(B64URL.decode(segment), StandardCharsets.UTF_8));
    }

    private String deny(String why) {
        log.log(Level.FINE, "JWT rejected: {0}", why);
        return null;
    }

    @Override public String schemeType() { return "oauthbearertoken"; }
    @Override public String schemeName() { return "OAuth Bearer JWT"; }
    @Override public String challenge() {
        return "Bearer realm=\"scim\", error=\"invalid_token\"";
    }
}
