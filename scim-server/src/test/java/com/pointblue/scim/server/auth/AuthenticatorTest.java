// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.auth;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatorTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    // ---- static bearer ----

    @Test
    void staticBearerMatchesConfiguredToken() {
        var a = new StaticBearerAuthenticator(Map.of("s3cret-token", "grove"));
        assertEquals("grove", a.authenticate("Bearer s3cret-token"));
        assertNull(a.authenticate("Bearer wrong"));
        assertNull(a.authenticate("Basic s3cret-token"));
        assertNull(a.authenticate(null));
    }

    // ---- basic ----

    @Test
    void basicAuthChecksUsernameAndPassword() {
        var a = new BasicAuthenticator(Map.of("svc", new String[] {"legacy", "p@ss"}));
        String ok = "Basic " + Base64.getEncoder().encodeToString("svc:p@ss".getBytes(StandardCharsets.UTF_8));
        assertEquals("legacy", a.authenticate(ok));
        String badPw = "Basic " + Base64.getEncoder().encodeToString("svc:nope".getBytes(StandardCharsets.UTF_8));
        assertNull(a.authenticate(badPw));
        String noUser = "Basic " + Base64.getEncoder().encodeToString("ghost:p@ss".getBytes(StandardCharsets.UTF_8));
        assertNull(a.authenticate(noUser));
        assertNull(a.authenticate("Bearer x"));
    }

    // ---- chain ----

    @Test
    void chainTriesEachMethodAndRefusesEmpty() {
        var chain = new AuthChain(List.of(
                new StaticBearerAuthenticator(Map.of("tok", "svc-a")),
                new BasicAuthenticator(Map.of("u", new String[] {"svc-b", "pw"}))));
        assertEquals("svc-a", chain.authenticate("Bearer tok"));
        assertEquals("svc-b", chain.authenticate(
                "Basic " + Base64.getEncoder().encodeToString("u:pw".getBytes())));
        assertNull(chain.authenticate("Bearer nope"));
        assertThrows(IllegalArgumentException.class, () -> new AuthChain(List.of()));
    }

    // ---- JWT against a local JWKS server ----

    private static KeyPair keyPair;
    private static HttpServer jwks;
    private static String jwksUrl;

    @BeforeAll
    static void startJwks() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        String n = B64.encodeToString(toUnsigned(pub.getModulus().toByteArray()));
        String e = B64.encodeToString(toUnsigned(pub.getPublicExponent().toByteArray()));
        String body = "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\"k1\",\"alg\":\"RS256\","
                + "\"n\":\"" + n + "\",\"e\":\"" + e + "\"}]}";
        jwks = HttpServer.create(new InetSocketAddress(0), 0);
        jwks.createContext("/jwks", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        jwks.start();
        jwksUrl = "http://localhost:" + jwks.getAddress().getPort() + "/jwks";
    }

    @AfterAll
    static void stopJwks() {
        if (jwks != null) jwks.stop(0);
    }

    private static byte[] toUnsigned(byte[] b) {
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private String jwt(JsonObject claims) throws Exception {
        String header = B64.encodeToString(
                "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8));
        String payload = B64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + B64.encodeToString(sig.sign());
    }

    private JwtAuthenticator authenticator(List<String> requiredScopes) {
        return new JwtAuthenticator(HttpClient.newHttpClient(),
                new JwtAuthenticator.Config(jwksUrl, "https://idp.example.com", "scim-proxy",
                        requiredScopes, "sub", 60));
    }

    private JsonObject baseClaims() {
        long now = Instant.now().getEpochSecond();
        return new JsonObject()
                .put("iss", "https://idp.example.com")
                .put("aud", "scim-proxy")
                .put("sub", "client-42")
                .put("exp", now + 300)
                .put("scope", "scim:read scim:write");
    }

    @Test
    void validJwtIsAccepted() throws Exception {
        assertEquals("client-42", authenticator(List.of()).authenticate("Bearer " + jwt(baseClaims())));
    }

    @Test
    void rejectsExpiredWrongIssuerAudienceAndScope() throws Exception {
        var a = authenticator(List.of("scim:write"));
        long now = Instant.now().getEpochSecond();

        assertNull(a.authenticate("Bearer " + jwt(baseClaims().put("exp", now - 120))),
                "expired beyond the 60s clock-skew leeway");
        assertNull(a.authenticate("Bearer " + jwt(baseClaims().put("iss", "https://evil"))), "issuer");
        assertNull(a.authenticate("Bearer " + jwt(baseClaims().put("aud", "someone-else"))), "audience");
        assertNull(a.authenticate("Bearer " + jwt(baseClaims().put("scope", "scim:read"))), "missing scope");
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        String token = jwt(baseClaims());
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertNull(authenticator(List.of()).authenticate("Bearer " + tampered));
    }

    @Test
    void nonJwtBearerIsIgnoredNotCrashed() {
        // a static opaque token must not throw in the JWT authenticator (chain falls through)
        assertNull(authenticator(List.of()).authenticate("Bearer opaque-token-no-dots"));
    }
}
