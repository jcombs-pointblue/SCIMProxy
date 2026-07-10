// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.auth;

import java.util.List;

/**
 * Tries each configured authenticator in order; the first to return a principal
 * wins. Enables mixing methods (static bearer, OAuth JWT, Basic) so clients
 * using any enabled scheme are accepted.
 */
public final class AuthChain {

    private final List<Authenticator> authenticators;

    public AuthChain(List<Authenticator> authenticators) {
        if (authenticators.isEmpty()) {
            throw new IllegalArgumentException(
                    "no authentication configured; refusing to start unauthenticated "
                            + "(configure auth.bearerTokens, auth.basic, and/or auth.oauth)");
        }
        this.authenticators = List.copyOf(authenticators);
    }

    /** Authenticated principal name, or null if no method accepts the request. */
    public String authenticate(String authorizationHeader) {
        for (Authenticator a : authenticators) {
            String principal = a.authenticate(authorizationHeader);
            if (principal != null) return principal;
        }
        return null;
    }

    public List<Authenticator> authenticators() { return authenticators; }

    /** Combined {@code WWW-Authenticate} challenge, one per distinct scheme keyword (Bearer, Basic). */
    public String challengeHeader() {
        java.util.Map<String, String> byScheme = new java.util.LinkedHashMap<>();
        for (Authenticator a : authenticators) {
            String challenge = a.challenge();
            String keyword = challenge.split(" ", 2)[0]; // "Bearer" / "Basic"
            byScheme.putIfAbsent(keyword, challenge);
        }
        return byScheme.isEmpty() ? "Bearer realm=\"scim\""
                : String.join(", ", byScheme.values());
    }
}
