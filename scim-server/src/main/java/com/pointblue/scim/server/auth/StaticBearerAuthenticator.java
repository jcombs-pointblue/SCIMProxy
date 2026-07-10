// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Long-lived bearer tokens defined in config, one per client. Tokens are held
 * as SHA-256 digests and compared constant-time; raw values are never stored on
 * the object beyond the digest map key and never logged.
 */
public final class StaticBearerAuthenticator implements Authenticator {

    private final Map<String, byte[]> digestToDigest = new HashMap<>(); // token -> sha256(token)
    private final Map<String, String> names;                            // token -> client name

    public StaticBearerAuthenticator(Map<String, String> tokensToNames) {
        this.names = Map.copyOf(tokensToNames);
        for (String token : tokensToNames.keySet()) {
            digestToDigest.put(token, sha256(token));
        }
    }

    @Override
    public String authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        byte[] presented = sha256(authorizationHeader.substring(7).trim());
        String match = null;
        for (Map.Entry<String, byte[]> e : digestToDigest.entrySet()) {
            // compare every entry so timing does not reveal which token matched
            if (MessageDigest.isEqual(presented, e.getValue())) match = names.get(e.getKey());
        }
        return match;
    }

    @Override public String schemeType() { return "oauthbearertoken"; }
    @Override public String schemeName() { return "Bearer Token"; }
    @Override public String challenge() { return "Bearer realm=\"scim\""; }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
