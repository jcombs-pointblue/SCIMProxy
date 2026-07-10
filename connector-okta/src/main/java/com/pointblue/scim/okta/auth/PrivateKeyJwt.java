// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta.auth;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Builds RS256 client-assertion JWTs for the OAuth2 private_key_jwt flow using
 * only JDK crypto. Expects a PKCS#8 PEM ("BEGIN PRIVATE KEY").
 */
public final class PrivateKeyJwt {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey key;
    private final String kid; // key id registered on the Okta app; null when the app has one key

    public PrivateKeyJwt(Path pemFile) {
        this(pemFile, null);
    }

    public PrivateKeyJwt(Path pemFile, String kid) {
        this.key = loadPkcs8(pemFile);
        this.kid = kid;
    }

    private static PrivateKey loadPkcs8(Path pemFile) {
        try {
            String pem = Files.readString(pemFile);
            if (!pem.contains("BEGIN PRIVATE KEY")) {
                throw new IllegalArgumentException(pemFile + " is not a PKCS#8 PEM (expected "
                        + "'BEGIN PRIVATE KEY'; convert with: openssl pkcs8 -topk8 -nocrypt)");
            }
            String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read private key " + pemFile, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot load RSA private key from " + pemFile, e);
        }
    }

    /** A 60-second client assertion: iss = sub = clientId, aud = token endpoint. */
    public String assertion(String clientId, String tokenUrl) {
        long now = Instant.now().getEpochSecond();
        JsonObject header = new JsonObject().put("alg", "RS256").put("typ", "JWT");
        if (kid != null) header.put("kid", kid);
        JsonObject claims = new JsonObject()
                .put("iss", clientId)
                .put("sub", clientId)
                .put("aud", tokenUrl)
                .put("iat", now)
                .put("exp", now + 60)
                .put("jti", UUID.randomUUID().toString());
        String signingInput = b64(Json.write(header)) + "." + b64(Json.write(claims));
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64URL.encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT signing failed", e);
        }
    }

    private static String b64(String json) {
        return B64URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
