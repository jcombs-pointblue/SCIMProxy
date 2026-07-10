// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.auth;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Fetches and caches a JWKS (JSON Web Key Set) from an IdP, resolving signing
 * keys by {@code kid}. The set is cached with a TTL; a request for an unknown
 * kid triggers at most one refresh per {@code minRefreshInterval} (so rotated
 * keys are picked up without letting a bogus kid hammer the IdP).
 */
final class JwksCache {

    private final HttpClient http;
    private final String jwksUrl;
    private final Duration ttl;
    private final Duration minRefreshInterval;

    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;

    JwksCache(HttpClient http, String jwksUrl, Duration ttl, Duration minRefreshInterval) {
        this.http = http;
        this.jwksUrl = jwksUrl;
        this.ttl = ttl;
        this.minRefreshInterval = minRefreshInterval;
    }

    /** Public key for the kid, refreshing the set if stale or the kid is unknown. Null if not found. */
    PublicKey key(String kid) {
        Map<String, PublicKey> current = keys;
        Instant now = Instant.now();
        boolean stale = fetchedAt.plus(ttl).isBefore(now);
        boolean unknown = kid != null && !current.containsKey(kid);
        if (stale || unknown) {
            synchronized (this) {
                boolean canRefresh = fetchedAt.plus(minRefreshInterval).isBefore(Instant.now());
                if ((stale || (unknown && !keys.containsKey(kid))) && canRefresh) {
                    refresh();
                }
                current = keys;
            }
        }
        // a single-key JWKS may omit kid; fall back to the sole key
        if (kid == null && current.size() == 1) return current.values().iterator().next();
        return current.get(kid);
    }

    private void refresh() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(jwksUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return; // keep the last-good set
            keys = parse(resp.body());
            fetchedAt = Instant.now();
        } catch (IOException e) {
            // network hiccup: keep the last-good set rather than failing all auth
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, PublicKey> parse(String body) {
        Map<String, PublicKey> result = new HashMap<>();
        JsonValue root = Json.parse(body);
        if (!(root instanceof JsonObject o) || !(o.getIgnoreCase("keys") instanceof JsonArray keys)) {
            return result;
        }
        for (JsonValue v : keys) {
            if (!(v instanceof JsonObject jwk)) continue;
            if (!"RSA".equalsIgnoreCase(jwk.optString("kty", ""))) continue; // RSA signing keys only
            String use = jwk.optString("use", "sig");
            if (!"sig".equalsIgnoreCase(use)) continue;
            try {
                BigInteger n = new BigInteger(1, b64url(jwk.optString("n", "")));
                BigInteger e = new BigInteger(1, b64url(jwk.optString("e", "")));
                RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
                String kid = jwk.optString("kid", null);
                result.put(kid == null ? "" : kid, key);
            } catch (Exception malformed) {
                // skip a bad key rather than reject the whole set
            }
        }
        return result;
    }

    private static byte[] b64url(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
