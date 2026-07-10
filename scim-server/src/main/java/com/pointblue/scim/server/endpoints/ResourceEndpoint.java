// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.endpoints;

import com.pointblue.scim.ScimException;
import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.FilterEvaluator;
import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.patch.PatchEngine;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.Attribute;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.ResourceType;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.schema.Validator;
import com.pointblue.scim.spi.Capabilities;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;
import com.pointblue.scim.spi.UnsupportedFilterException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Protocol handling for one resource type (/Users, /Groups): validation,
 * PATCH emulation, filter fallback, pagination, projection. All backend
 * specifics stay behind the ResourceBackend SPI.
 */
public final class ResourceEndpoint {

    private final SchemaRegistry registry;
    private final ResourceType rt;
    private final ResourceBackend backend;
    private final Capabilities caps;
    private final Validator validator;
    private final PatchEngine patchEngine;
    private final FilterEvaluator evaluator;
    private final String baseUrl;
    private final int defaultPageSize;

    public ResourceEndpoint(SchemaRegistry registry, ResourceType rt, ResourceBackend backend,
                            Capabilities caps, String baseUrl, int defaultPageSize) {
        this.registry = registry;
        this.rt = rt;
        this.backend = backend;
        this.caps = caps;
        this.validator = new Validator(registry);
        this.patchEngine = new PatchEngine(registry, rt.name());
        this.evaluator = new FilterEvaluator(rt.baseSchema(), path -> {
            Attribute a = registry.resolve(rt.name(), path);
            return a != null && a.caseExact();
        });
        this.baseUrl = baseUrl;
        this.defaultPageSize = defaultPageSize;
    }

    public ResourceType resourceType() { return rt; }

    // ---- operations ----

    public Response create(JsonObject body) {
        validator.validate(rt.name(), body);
        JsonObject created = backend.create(body);
        String id = created.optString("id", null);
        JsonObject finished = finish(created, Projection.NONE);
        return Response.created(finished, location(id));
    }

    public Response get(String id, Map<String, String> query) {
        JsonObject resource = backend.get(id);
        return Response.ok(finish(resource, projectionFrom(query)));
    }

    public Response put(String id, JsonObject body) {
        validator.validate(rt.name(), body);
        JsonObject replaced = backend.replace(id, body);
        return Response.ok(finish(replaced, Projection.NONE));
    }

    public Response patch(String id, JsonObject body) {
        List<PatchOp> ops = PatchOp.parseAll(body);
        JsonObject result;
        if (caps.nativePatch()) {
            result = backend.patch(id, ops);
        } else {
            JsonObject current = backend.get(id);
            patchEngine.apply(current, ops);
            validator.validate(rt.name(), current);
            result = backend.replace(id, current);
        }
        return Response.ok(finish(result, Projection.NONE));
    }

    public Response delete(String id) {
        backend.delete(id);
        return Response.noContent();
    }

    public Response list(Map<String, String> query) {
        String filterStr = query.get("filter");
        Filter filter = filterStr == null ? null : FilterParser.parse(filterStr);
        int startIndex = intParam(query.get("startIndex"), 1);
        int count = Math.min(intParam(query.get("count"), defaultPageSize), caps.maxPageSize());
        return listInternal(filter, startIndex, count, projectionFrom(query));
    }

    /** POST /.search with a SearchRequest message body. */
    public Response search(JsonObject body) {
        JsonValue schemas = body.getIgnoreCase("schemas");
        boolean declared = schemas instanceof JsonArray arr && arr.list().stream().anyMatch(
                v -> v.isString() && v.asString().equalsIgnoreCase(CoreSchemas.SEARCH_REQUEST_URN));
        if (!declared) throw ScimException.invalidSyntax("body is not a SearchRequest message");
        String filterStr = body.optString("filter", null);
        Filter filter = filterStr == null ? null : FilterParser.parse(filterStr);
        int startIndex = numberParam(body.getIgnoreCase("startIndex"), 1);
        int count = Math.min(numberParam(body.getIgnoreCase("count"), defaultPageSize), caps.maxPageSize());
        Projection projection = new Projection(
                joinList(body.getIgnoreCase("attributes")),
                joinList(body.getIgnoreCase("excludedAttributes")),
                rt.baseSchema());
        return listInternal(filter, startIndex, count, projection);
    }

    private Response listInternal(Filter filter, int startIndex, int count, Projection projection) {
        List<JsonObject> page;
        long total;
        SearchResult result = null;
        if (filter == null || caps.filterable()) {
            try {
                result = backend.search(new SearchRequest(filter, startIndex, count));
            } catch (UnsupportedFilterException e) {
                // this particular filter can't be pushed down; scan instead
            }
        }
        if (result != null) {
            page = result.resources();
            total = result.totalResults();
        } else {
            // backend cannot filter: scan pages and evaluate here, bounded by maxInMemoryScan
            List<JsonObject> matched = new ArrayList<>();
            int scanIndex = 1;
            int scanned = 0;
            while (scanned < caps.maxInMemoryScan()) {
                SearchResult batch = backend.search(new SearchRequest(null, scanIndex, caps.maxPageSize()));
                if (batch.resources().isEmpty()) break;
                for (JsonObject r : batch.resources()) {
                    if (evaluator.matches(filter, r)) matched.add(r);
                }
                scanned += batch.resources().size();
                scanIndex += batch.resources().size();
                if (batch.resources().size() < caps.maxPageSize()) break;
            }
            if (scanned >= caps.maxInMemoryScan()) {
                throw new ScimException(400, "tooMany",
                        "filter cannot be pushed to the backend and the result set is too large to scan");
            }
            total = matched.size();
            int from = Math.min(startIndex - 1, matched.size());
            int to = Math.min(from + count, matched.size());
            page = matched.subList(from, to);
        }

        JsonObject resp = new JsonObject();
        JsonArray schemas = new JsonArray();
        schemas.add(CoreSchemas.LIST_RESPONSE_URN);
        resp.put("schemas", schemas);
        if (total >= 0) resp.put("totalResults", total);
        resp.put("startIndex", startIndex);
        resp.put("itemsPerPage", page.size());
        JsonArray resources = new JsonArray();
        for (JsonObject r : page) resources.add(finish(r, projection));
        resp.put("Resources", resources);
        return Response.ok(resp);
    }

    // ---- helpers ----

    private Projection projectionFrom(Map<String, String> query) {
        String include = query.get("attributes");
        String exclude = query.get("excludedAttributes");
        if (include == null && exclude == null) return Projection.NONE;
        return new Projection(include, exclude, rt.baseSchema());
    }

    /** Outbound finishing: strip write-only attrs, set meta.location, project. */
    private JsonObject finish(JsonObject resource, Projection projection) {
        validator.stripNeverReturned(rt.name(), resource);
        String id = resource.optString("id", null);
        JsonValue metaVal = resource.getIgnoreCase("meta");
        JsonObject meta;
        if (metaVal instanceof JsonObject m) meta = m;
        else { meta = new JsonObject(); resource.put("meta", meta); }
        meta.put("resourceType", rt.name());
        if (id != null) meta.put("location", location(id));
        if (projection != null && !projection.isNoOp()) projection.apply(resource);
        return resource;
    }

    private String location(String id) {
        return baseUrl + rt.endpoint() + "/" + id;
    }

    private static int intParam(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw ScimException.invalidValue("expected an integer, got '" + value + "'");
        }
    }

    private static int numberParam(JsonValue value, int fallback) {
        if (value == null || value.isNull()) return fallback;
        return value.asNumber().intValue();
    }

    private static String joinList(JsonValue value) {
        if (!(value instanceof JsonArray arr) || arr.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonValue v : arr) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(v.asString());
        }
        return sb.toString();
    }
}
