// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.endpoints;

import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.ResourceType;
import com.pointblue.scim.schema.Schema;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.Capabilities;

import java.util.List;

/** /ServiceProviderConfig, /Schemas, /ResourceTypes — generated, always truthful. */
public final class DiscoveryEndpoints {

    /** One enabled authentication scheme for ServiceProviderConfig (RFC 7643 §5). */
    public record AuthScheme(String type, String name) {}

    private final SchemaRegistry registry;
    private final Capabilities caps;
    private final String baseUrl;
    private final int maxResults;
    private final List<AuthScheme> authSchemes;

    public DiscoveryEndpoints(SchemaRegistry registry, Capabilities caps, String baseUrl, int maxResults,
                              List<AuthScheme> authSchemes) {
        this.registry = registry;
        this.caps = caps;
        this.baseUrl = baseUrl;
        this.maxResults = maxResults;
        this.authSchemes = authSchemes;
    }

    public JsonObject serviceProviderConfig() {
        JsonObject o = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig");
        o.put("schemas", schemas);
        o.put("documentationUri", "https://tools.ietf.org/html/rfc7644");
        o.put("patch", supported(true));
        JsonObject bulk = supported(false); // M4
        bulk.put("maxOperations", 0);
        bulk.put("maxPayloadSize", 0);
        o.put("bulk", bulk);
        JsonObject filter = supported(true);
        filter.put("maxResults", maxResults);
        o.put("filter", filter);
        o.put("changePassword", supported(false));
        o.put("sort", supported(false));
        o.put("etag", supported(caps.etagSupported()));
        JsonArray schemesJson = new JsonArray();
        for (AuthScheme s : this.authSchemes) {
            JsonObject scheme = new JsonObject();
            scheme.put("type", s.type());
            scheme.put("name", s.name());
            scheme.put("description", "Authorization header (" + s.name() + ")");
            schemesJson.add(scheme);
        }
        o.put("authenticationSchemes", schemesJson);
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "ServiceProviderConfig");
        meta.put("location", baseUrl + "/ServiceProviderConfig");
        o.put("meta", meta);
        return o;
    }

    private static JsonObject supported(boolean s) {
        return new JsonObject().put("supported", s);
    }

    public JsonObject schemasList() {
        JsonArray resources = new JsonArray();
        for (Schema s : registry.allSchemas()) resources.add(withLocation(s.toJson(), "/Schemas/" + s.id()));
        return listResponse(resources);
    }

    public JsonObject schema(String urn) {
        Schema s = registry.schema(urn);
        return s == null ? null : withLocation(s.toJson(), "/Schemas/" + s.id());
    }

    public JsonObject resourceTypesList() {
        JsonArray resources = new JsonArray();
        for (ResourceType rt : registry.allResourceTypes()) resources.add(rt.toJson(baseUrl));
        return listResponse(resources);
    }

    public JsonObject resourceType(String name) {
        ResourceType rt = registry.resourceType(name);
        return rt == null ? null : rt.toJson(baseUrl);
    }

    private JsonObject withLocation(JsonObject schemaJson, String path) {
        schemaJson.getIgnoreCase("meta").asObject().put("location", baseUrl + path);
        return schemaJson;
    }

    private JsonObject listResponse(JsonArray resources) {
        JsonObject o = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(CoreSchemas.LIST_RESPONSE_URN);
        o.put("schemas", schemas);
        o.put("totalResults", resources.size());
        o.put("startIndex", 1);
        o.put("itemsPerPage", resources.size());
        o.put("Resources", resources);
        return o;
    }
}
