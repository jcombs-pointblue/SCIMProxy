package com.pointblue.scim.okta;

import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonBoolean;
import com.pointblue.scim.json.JsonNull;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.okta.http.OktaClient;
import com.pointblue.scim.okta.map.FilterTranslator;
import com.pointblue.scim.okta.map.OktaStatus;
import com.pointblue.scim.okta.map.UserMapper;
import com.pointblue.scim.patch.PatchEngine;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** /Users backed by the Okta Users API. */
final class OktaUsers implements ResourceBackend {

    private static final int PAGE = 200;

    private final OktaClient client;
    private final UserMapper mapper;
    private final OktaConfig config;
    private final PatchEngine patchEngine;

    OktaUsers(OktaClient client, UserMapper mapper, OktaConfig config, SchemaRegistry registry) {
        this.client = client;
        this.mapper = mapper;
        this.config = config;
        this.patchEngine = new PatchEngine(registry, "User");
    }

    @Override
    public JsonObject create(JsonObject resource) {
        boolean active = resource.optBoolean("active", true);
        JsonObject body = new JsonObject();
        body.put("profile", mapper.toProfile(resource, false));
        String password = resource.optString("password", null);
        if (password != null) {
            body.put("credentials", new JsonObject()
                    .put("password", new JsonObject().put("value", password)));
        }
        OktaClient.Result r = client.post("/api/v1/users?activate=" + active, body);
        return mapper.toScim(r.body().asObject());
    }

    @Override
    public JsonObject get(String id) {
        return mapper.toScim(fetch(id));
    }

    private JsonObject fetch(String id) {
        return client.get("/api/v1/users/" + pathSegment(id)).body().asObject();
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        JsonObject current = fetch(id);
        // partial update with explicit nulls: clears mapped-but-absent attributes,
        // leaves unmapped Okta profile attributes untouched (design decision)
        JsonObject body = new JsonObject().put("profile", mapper.toProfile(resource, true));
        client.post("/api/v1/users/" + pathSegment(id), body);
        JsonValue active = resource.getIgnoreCase("active");
        if (active instanceof JsonBoolean b) {
            applyActive(id, b.value(), current.optString("status", "ACTIVE"));
        }
        return get(id);
    }

    @Override
    public void delete(String id) {
        JsonObject current = fetch(id);
        String status = current.optString("status", "ACTIVE");
        if (!"DEPROVISIONED".equals(status)) {
            client.post("/api/v1/users/" + pathSegment(id) + "/lifecycle/deactivate", null);
        }
        if (config.destroyOnDelete) {
            client.delete("/api/v1/users/" + pathSegment(id));
        }
    }

    @Override
    public SearchResult search(SearchRequest request) {
        String query = "/api/v1/users?limit=" + PAGE;
        if (request.filter() != null) {
            String search = FilterTranslator.toOktaSearch(request.filter(), mapper::oktaAttrForSearch);
            query += "&search=" + URLEncoder.encode(search, StandardCharsets.UTF_8);
        }
        return page(query, request, body -> mapper.toScim(body.asObject()));
    }

    /** Walks Okta cursor pages until the requested window is covered. */
    SearchResult page(String baseQuery, SearchRequest request,
                      java.util.function.Function<JsonValue, JsonObject> toScim) {
        int needed = request.startIndex() - 1 + request.count();
        List<JsonObject> collected = new ArrayList<>();
        String after = null;
        boolean exhausted = false;
        while (collected.size() < Math.max(needed, 1)) {
            String q = after == null ? baseQuery : baseQuery + "&after=" + after;
            OktaClient.Result r = client.get(q);
            for (JsonValue v : r.body().asArray()) collected.add(toScim.apply(v));
            after = r.nextCursor();
            if (after == null) { exhausted = true; break; }
        }
        long total = exhausted ? collected.size() : -1;
        int from = Math.min(request.startIndex() - 1, collected.size());
        int to = Math.min(from + request.count(), collected.size());
        return new SearchResult(List.copyOf(collected.subList(from, to)), total);
    }

    @Override
    public JsonObject patch(String id, List<PatchOp> ops) {
        JsonObject profile = new JsonObject();
        Boolean activeChange = null;
        boolean allNative = true;
        for (PatchOp op : ops) {
            if (op.path() == null || op.path().valueFilter() != null) { allNative = false; break; }
            AttributePath p = op.path();
            if (p.schemaUrn() == null && p.subAttr() == null && p.attr().equalsIgnoreCase("active")
                    && op.type() != PatchOp.Type.REMOVE && op.value() instanceof JsonBoolean b) {
                activeChange = b.value();
                continue;
            }
            String oktaAttr = mapper.oktaAttrFor(p);
            if (oktaAttr == null) { allNative = false; break; }
            profile.put(oktaAttr, op.type() == PatchOp.Type.REMOVE ? JsonNull.NULL : op.value().copy());
        }
        if (allNative) {
            if (!profile.isEmpty()) {
                client.post("/api/v1/users/" + pathSegment(id), new JsonObject().put("profile", profile));
            }
            if (activeChange != null) {
                String status = fetch(id).optString("status", "ACTIVE");
                applyActive(id, activeChange, status);
            }
            return get(id);
        }
        // general case: get-apply-replace through the core PATCH engine
        JsonObject current = get(id);
        patchEngine.apply(current, ops);
        return replace(id, current);
    }

    private void applyActive(String id, boolean desired, String currentStatus) {
        boolean currentlyActive = OktaStatus.isActive(currentStatus);
        if (desired == currentlyActive) return;
        String seg = pathSegment(id);
        if (desired) {
            if ("SUSPENDED".equals(currentStatus)) {
                client.post("/api/v1/users/" + seg + "/lifecycle/unsuspend", null);
            } else {
                client.post("/api/v1/users/" + seg + "/lifecycle/activate?sendEmail=false", null);
            }
        } else {
            client.post("/api/v1/users/" + seg + "/lifecycle/deactivate", null);
        }
    }

    static String pathSegment(String id) {
        if (id.isBlank() || id.contains("/") || id.contains("?") || id.contains("#")) {
            throw new BackendException(400, "invalidValue", "malformed resource id");
        }
        return URLEncoder.encode(id, StandardCharsets.UTF_8);
    }
}
