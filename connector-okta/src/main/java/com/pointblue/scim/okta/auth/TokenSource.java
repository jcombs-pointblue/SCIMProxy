package com.pointblue.scim.okta.auth;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.spi.BackendException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Caches the Okta access token; single-flight refresh 60 s before expiry. */
public final class TokenSource {

    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final HttpClient http;
    private final PrivateKeyJwt jwt;      // exactly one of jwt / clientSecret is set
    private final String clientSecret;
    private final String clientId;
    private final String tokenUrl;
    private final String scopes;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public TokenSource(HttpClient http, PrivateKeyJwt jwt, String clientSecret,
                       String clientId, String tokenUrl, String scopes) {
        this.http = http;
        this.jwt = jwt;
        this.clientSecret = clientSecret;
        this.clientId = clientId;
        this.tokenUrl = tokenUrl;
        this.scopes = scopes;
    }

    public synchronized String bearer() {
        if (token != null && Instant.now().isBefore(expiresAt)) return token;
        String form = "grant_type=client_credentials&scope=" + url(scopes);
        if (jwt != null) {
            form += "&client_assertion_type=" + url(ASSERTION_TYPE)
                    + "&client_assertion=" + url(jwt.assertion(clientId, tokenUrl));
        } else {
            form += "&client_id=" + url(clientId) + "&client_secret=" + url(clientSecret);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new BackendException(502, null,
                        "Okta token endpoint returned " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            JsonObject body = Json.parseObject(resp.body());
            token = body.optString("access_token", null);
            if (token == null) throw new BackendException(502, null, "Okta token response missing access_token");
            long expiresIn = body.getIgnoreCase("expires_in") == null
                    ? 300 : body.getIgnoreCase("expires_in").asNumber().longValue();
            expiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn) - 60);
            return token;
        } catch (IOException e) {
            throw BackendException.unavailable("cannot reach Okta token endpoint: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BackendException.unavailable("interrupted while fetching Okta token");
        }
    }

    private static String url(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String truncate(String s) { return s.length() > 300 ? s.substring(0, 300) + "..." : s; }
}
