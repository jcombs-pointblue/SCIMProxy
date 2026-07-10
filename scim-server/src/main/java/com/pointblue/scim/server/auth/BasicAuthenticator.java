// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP Basic auth for legacy clients that only support username/password.
 * Credentials come from config; the password is matched constant-time against
 * a SHA-256 digest so a timing side channel cannot probe it.
 */
public final class BasicAuthenticator implements Authenticator {

    private record Cred(byte[] passwordDigest, String name) {}

    private final Map<String, Cred> byUser = new LinkedHashMap<>(); // username (lowercased) -> cred

    /** @param users username -> [name, password] */
    public BasicAuthenticator(Map<String, String[]> users) {
        users.forEach((username, nameAndPassword) ->
                byUser.put(username.toLowerCase(),
                        new Cred(StaticBearerAuthenticator.sha256(nameAndPassword[1]), nameAndPassword[0])));
    }

    @Override
    public String authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) return null;
        String username = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        Cred cred = byUser.get(username.toLowerCase());
        // always hash the presented password so a missing user isn't faster than a wrong password
        byte[] presented = StaticBearerAuthenticator.sha256(password);
        if (cred != null && MessageDigest.isEqual(presented, cred.passwordDigest())) return cred.name();
        return null;
    }

    @Override public String schemeType() { return "httpbasic"; }
    @Override public String schemeName() { return "HTTP Basic"; }
    @Override public String challenge() { return "Basic realm=\"scim\""; }
}
