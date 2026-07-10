// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta;

import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;

import java.util.ArrayList;
import java.util.List;

/** Parsed backend.{} config block for the Okta connector. */
public final class OktaConfig {

    final String orgUrl;
    final String clientId;
    final String privateKeyPem;      // path to a PKCS#8 PEM file (private_key_jwt), or
    final String clientSecret;       // client_secret_post — exactly one of the two
    final String kid;                // key id for private_key_jwt; optional
    final String scopes;
    final boolean destroyOnDelete;   // default false: DELETE deactivates only
    final int memberCap;             // max group members materialized on GET
    final List<Mapping> userMappings; // extra/overriding mappings from config

    public record Mapping(String scimPath, String oktaAttr) {}

    OktaConfig(JsonObject cfg) {
        this.orgUrl = trimSlash(require(cfg, "orgUrl"));
        this.clientId = require(cfg, "clientId");
        this.privateKeyPem = cfg.optString("privateKeyPem", null);
        this.clientSecret = cfg.optString("clientSecret", null);
        if ((privateKeyPem == null) == (clientSecret == null)) {
            throw new IllegalArgumentException(
                    "okta backend config requires exactly one of 'privateKeyPem' (private_key_jwt) "
                    + "or 'clientSecret' (client_secret_post)");
        }
        this.kid = cfg.optString("kid", null);
        this.scopes = cfg.optString("scopes", null) != null
                ? joinScopes(cfg.get("scopes"))
                : "okta.users.manage okta.users.read okta.groups.manage okta.groups.read";
        String behavior = cfg.optString("deleteBehavior", "deactivate");
        this.destroyOnDelete = switch (behavior) {
            case "deactivate" -> false;
            case "destroy" -> true;
            default -> throw new IllegalArgumentException(
                    "backend.deleteBehavior must be 'deactivate' or 'destroy', got '" + behavior + "'");
        };
        JsonValue cap = cfg.getIgnoreCase("memberCap");
        this.memberCap = cap == null ? 1000 : cap.asNumber().intValue();
        List<Mapping> mappings = new ArrayList<>();
        if (cfg.getIgnoreCase("userMappings") instanceof JsonArray arr) {
            for (JsonValue v : arr) {
                JsonObject m = v.asObject();
                String scim = m.optString("scim", null);
                String okta = m.optString("okta", null);
                if (scim == null || okta == null) {
                    throw new IllegalArgumentException("userMappings entries need 'scim' and 'okta'");
                }
                mappings.add(new Mapping(scim, stripProfilePrefix(okta)));
            }
        }
        this.userMappings = List.copyOf(mappings);
    }

    private static String joinScopes(JsonValue v) {
        if (v instanceof JsonArray arr) {
            StringBuilder sb = new StringBuilder();
            for (JsonValue s : arr) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(s.asString());
            }
            return sb.toString();
        }
        return v.asString();
    }

    private static String stripProfilePrefix(String okta) {
        return okta.startsWith("profile.") ? okta.substring("profile.".length()) : okta;
    }

    private static String require(JsonObject cfg, String name) {
        String v = cfg.optString(name, null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("okta backend config requires '" + name + "'");
        }
        return v;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    String tokenUrl() { return orgUrl + "/oauth2/v1/token"; }
}
