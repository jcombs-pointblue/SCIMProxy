package com.pointblue.scim.okta.http;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.okta.auth.TokenSource;
import com.pointblue.scim.spi.BackendException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin Okta Management API client: bearer auth, error translation to
 * BackendException, one bounded retry on 429 (honoring x-rate-limit-reset).
 */
public final class OktaClient {

    private static final Logger log = Logger.getLogger("scim.okta");
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    private static final long MAX_RATE_WAIT_MS = 15_000;

    public record Result(int status, JsonValue body, HttpResponse<String> raw) {
        /** The `after` cursor from the Link rel="next" header, or null. */
        public String nextCursor() {
            Optional<String> link = raw.headers().firstValue("link")
                    .or(() -> raw.headers().firstValue("Link"));
            if (link.isEmpty()) return null;
            Matcher m = NEXT_LINK.matcher(link.get());
            if (!m.find()) return null;
            for (String param : URI.create(m.group(1)).getQuery().split("&")) {
                if (param.startsWith("after=")) return param.substring("after=".length());
            }
            return null;
        }
    }

    private final HttpClient http;
    private final TokenSource tokens;
    private final String orgUrl;

    public OktaClient(HttpClient http, TokenSource tokens, String orgUrl) {
        this.http = http;
        this.tokens = tokens;
        this.orgUrl = orgUrl;
    }

    public Result get(String pathAndQuery)                    { return send("GET", pathAndQuery, null); }
    public Result post(String pathAndQuery, JsonValue body)   { return send("POST", pathAndQuery, body); }
    public Result put(String pathAndQuery, JsonValue body)    { return send("PUT", pathAndQuery, body); }
    public Result delete(String pathAndQuery)                 { return send("DELETE", pathAndQuery, null); }

    public Result send(String method, String pathAndQuery, JsonValue body) {
        HttpResponse<String> resp = exchange(method, pathAndQuery, body);
        if (resp.statusCode() == 429) {
            waitForRateLimit(resp);
            resp = exchange(method, pathAndQuery, body);
        }
        if (resp.statusCode() >= 400) throw translate(resp, method, pathAndQuery);
        JsonValue parsed = resp.body() == null || resp.body().isBlank() ? null : Json.parse(resp.body());
        return new Result(resp.statusCode(), parsed, resp);
    }

    private HttpResponse<String> exchange(String method, String pathAndQuery, JsonValue body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(orgUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + tokens.bearer())
                .header("Accept", "application/json");
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw BackendException.unavailable("Okta request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BackendException.unavailable("interrupted during Okta request");
        }
    }

    private void waitForRateLimit(HttpResponse<String> resp) {
        long waitMs = 1000;
        Optional<String> reset = resp.headers().firstValue("x-rate-limit-reset");
        if (reset.isPresent()) {
            try {
                long resetEpoch = Long.parseLong(reset.get());
                waitMs = Math.max(0, resetEpoch - Instant.now().getEpochSecond()) * 1000 + 250;
            } catch (NumberFormatException ignored) { /* keep default */ }
        }
        waitMs = Math.min(waitMs, MAX_RATE_WAIT_MS);
        log.warning("Okta rate limit hit; waiting " + waitMs + " ms before retry");
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackendException(429, null, "rate limited by Okta");
        }
    }

    private BackendException translate(HttpResponse<String> resp, String method, String path) {
        String summary = "";
        String causes = "";
        try {
            JsonObject err = Json.parseObject(resp.body());
            summary = err.optString("errorSummary", "");
            if (err.get("errorCauses") instanceof JsonArray arr) {
                StringBuilder sb = new StringBuilder();
                for (JsonValue c : arr) {
                    if (!sb.isEmpty()) sb.append("; ");
                    sb.append(c.asObject().optString("errorSummary", ""));
                }
                causes = sb.toString();
            }
        } catch (RuntimeException ignored) { /* non-JSON error body */ }
        String detail = ("Okta: " + summary + (causes.isEmpty() ? "" : " (" + causes + ")")).trim();

        return switch (resp.statusCode()) {
            case 404 -> new BackendException(404, null, detail.isEmpty() ? "not found in Okta" : detail);
            case 429 -> new BackendException(429, null, "rate limited by Okta");
            case 400 -> {
                String all = (summary + " " + causes).toLowerCase();
                if (all.contains("already exists")) yield BackendException.conflict(detail);
                yield new BackendException(400, "invalidValue", detail);
            }
            case 401, 403 -> new BackendException(502, null,
                    "Okta rejected the proxy's credentials (" + resp.statusCode() + "): " + detail);
            default -> new BackendException(resp.statusCode() >= 500 ? 502 : resp.statusCode(), null,
                    "Okta " + method + " " + path + " -> " + resp.statusCode() + ": " + detail);
        };
    }
}
