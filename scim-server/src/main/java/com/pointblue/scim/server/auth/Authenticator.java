// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.auth;

/**
 * Authenticates one inbound request from its Authorization header. Several
 * authenticators are tried in a chain (see {@link AuthChain}); the first that
 * returns a principal wins. Returning null means "not me / not valid" — the
 * chain moves on, and a request no authenticator accepts is rejected 401.
 */
public interface Authenticator {

    /**
     * @param authorizationHeader the raw {@code Authorization} header value, or null
     * @return the authenticated principal name to log, or null if this method does
     *         not apply to the request or the credential is invalid
     */
    String authenticate(String authorizationHeader);

    /** RFC 7643 §5 authenticationScheme type for ServiceProviderConfig, e.g. "oauthbearertoken". */
    String schemeType();

    /** Human-readable scheme name for ServiceProviderConfig. */
    String schemeName();

    /** The {@code WWW-Authenticate} challenge this method offers on 401 (e.g. "Bearer", "Basic"). */
    String challenge();
}
