// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.endpoints;

import com.pointblue.scim.json.JsonObject;

import java.util.Map;

/** Endpoint result; the HTTP layer serializes it. */
public record Response(int status, JsonObject body, Map<String, String> headers) {

    public static Response ok(JsonObject body) { return new Response(200, body, Map.of()); }

    public static Response created(JsonObject body, String location) {
        return new Response(201, body, Map.of("Location", location));
    }

    public static Response noContent() { return new Response(204, null, Map.of()); }
}
