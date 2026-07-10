// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.okta;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.okta.http.OktaClient;
import com.pointblue.scim.okta.map.FilterTranslator;
import com.pointblue.scim.patch.PatchEngine;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.pointblue.scim.okta.OktaUsers.pathSegment;

/** /Groups backed by the Okta Groups API (OKTA_GROUP type only). */
final class OktaGroups implements ResourceBackend {

    private static final int PAGE = 200;

    private final OktaClient client;
    private final OktaConfig config;
    private final PatchEngine patchEngine;

    OktaGroups(OktaClient client, OktaConfig config, SchemaRegistry registry) {
        this.client = client;
        this.config = config;
        this.patchEngine = new PatchEngine(registry, "Group");
    }

    // ---- mapping ----

    private JsonObject toScim(JsonObject oktaGroup, JsonArray members) {
        JsonObject scim = new JsonObject();
        scim.put("schemas", new JsonArray().add(CoreSchemas.GROUP_URN));
        scim.put("id", oktaGroup.optString("id", null));
        if (oktaGroup.getIgnoreCase("profile") instanceof JsonObject profile) {
            scim.put("displayName", profile.optString("name", null));
        }
        if (members != null) scim.put("members", members);
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "Group");
        String created = oktaGroup.optString("created", null);
        String updated = oktaGroup.optString("lastUpdated", null);
        if (created != null) meta.put("created", created);
        if (updated != null) meta.put("lastModified", updated);
        scim.put("meta", meta);
        return scim;
    }

    private JsonObject profileBody(JsonObject scimGroup) {
        String name = scimGroup.optString("displayName", null);
        if (name == null) throw new BackendException(400, "invalidValue", "displayName is required");
        return new JsonObject().put("profile", new JsonObject().put("name", name));
    }

    private void guardOktaGroup(JsonObject oktaGroup) {
        String type = oktaGroup.optString("type", "OKTA_GROUP");
        if (!"OKTA_GROUP".equals(type)) {
            throw new BackendException(403, null,
                    "group is of type " + type + " and is not managed through this proxy");
        }
    }

    // ---- CRUD ----

    @Override
    public JsonObject create(JsonObject resource) {
        OktaClient.Result r = client.post("/api/v1/groups", profileBody(resource));
        String id = r.body().asObject().optString("id", null);
        if (resource.getIgnoreCase("members") instanceof JsonArray members) {
            for (JsonValue m : members) addMember(id, memberId(m));
        }
        return get(id);
    }

    @Override
    public JsonObject get(String id) {
        JsonObject group = fetch(id);
        return toScim(group, fetchMembers(id));
    }

    private JsonObject fetch(String id) {
        return client.get("/api/v1/groups/" + pathSegment(id)).body().asObject();
    }

    private JsonArray fetchMembers(String id) {
        JsonArray members = new JsonArray();
        String after = null;
        while (members.size() < config.memberCap) {
            String q = "/api/v1/groups/" + pathSegment(id) + "/users?limit=" + PAGE
                    + (after == null ? "" : "&after=" + after);
            OktaClient.Result r = client.get(q);
            for (JsonValue v : r.body().asArray()) {
                JsonObject u = v.asObject();
                JsonObject member = new JsonObject();
                member.put("value", u.optString("id", null));
                if (u.getIgnoreCase("profile") instanceof JsonObject p) {
                    member.put("display", p.optString("login", null));
                }
                members.add(member);
            }
            after = r.nextCursor();
            if (after == null) break;
        }
        return members;
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        guardOktaGroup(fetch(id));
        client.put("/api/v1/groups/" + pathSegment(id), profileBody(resource));
        // reconcile membership against the submitted list
        Set<String> desired = new LinkedHashSet<>();
        if (resource.getIgnoreCase("members") instanceof JsonArray members) {
            for (JsonValue m : members) desired.add(memberId(m));
        }
        Set<String> current = new LinkedHashSet<>();
        for (JsonValue m : fetchMembers(id)) current.add(m.asObject().optString("value", ""));
        for (String uid : desired) {
            if (!current.contains(uid)) addMember(id, uid);
        }
        for (String uid : current) {
            if (!desired.contains(uid)) removeMember(id, uid);
        }
        return get(id);
    }

    @Override
    public void delete(String id) {
        guardOktaGroup(fetch(id));
        client.delete("/api/v1/groups/" + pathSegment(id));
    }

    @Override
    public SearchResult search(SearchRequest request) {
        String query = "/api/v1/groups?limit=" + PAGE;
        if (request.filter() != null) {
            String search = FilterTranslator.toOktaSearch(request.filter(), this::groupSearchPath);
            query += "&search=" + URLEncoder.encode(search, StandardCharsets.UTF_8);
        }
        // members are intentionally not materialized on list responses
        int needed = request.startIndex() - 1 + request.count();
        List<JsonObject> collected = new ArrayList<>();
        String after = null;
        boolean exhausted = false;
        while (collected.size() < Math.max(needed, 1)) {
            String q = after == null ? query : query + "&after=" + after;
            OktaClient.Result r = client.get(q);
            for (JsonValue v : r.body().asArray()) collected.add(toScim(v.asObject(), null));
            after = r.nextCursor();
            if (after == null) { exhausted = true; break; }
        }
        long total = exhausted ? collected.size() : -1;
        int from = Math.min(request.startIndex() - 1, collected.size());
        int to = Math.min(from + request.count(), collected.size());
        return new SearchResult(List.copyOf(collected.subList(from, to)), total);
    }

    private String groupSearchPath(AttributePath path) {
        if (path.subAttr() == null && path.attr().equalsIgnoreCase("displayName")) return "profile.name";
        return null;
    }

    // ---- PATCH ----

    @Override
    public JsonObject patch(String id, List<PatchOp> ops) {
        guardOktaGroup(fetch(id));
        List<Runnable> actions = new ArrayList<>();
        for (PatchOp op : ops) {
            Runnable action = nativeAction(id, op);
            if (action == null) {
                // something non-trivial: apply everything via get-apply-replace
                JsonObject current = get(id);
                patchEngine.apply(current, ops);
                return replace(id, current);
            }
            actions.add(action);
        }
        actions.forEach(Runnable::run);
        return get(id);
    }

    /** Maps one PATCH op to a native Okta call, or null when it needs the fallback. */
    private Runnable nativeAction(String id, PatchOp op) {
        AttributePath p = op.path();
        if (p == null || p.schemaUrn() != null) return null;
        if (p.attr().equalsIgnoreCase("displayName") && p.subAttr() == null && p.valueFilter() == null
                && op.type() != PatchOp.Type.REMOVE && op.value() instanceof JsonString name) {
            return () -> client.put("/api/v1/groups/" + pathSegment(id),
                    new JsonObject().put("profile", new JsonObject().put("name", name.value())));
        }
        if (!p.attr().equalsIgnoreCase("members") || p.subAttr() != null) return null;
        if (op.type() == PatchOp.Type.ADD && p.valueFilter() == null && op.value() != null) {
            JsonArray additions = op.value() instanceof JsonArray a ? a : new JsonArray().add(op.value());
            List<String> ids = new ArrayList<>();
            for (JsonValue m : additions) ids.add(memberId(m));
            return () -> ids.forEach(uid -> addMember(id, uid));
        }
        if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() == null && op.value() instanceof JsonArray list) {
            List<String> ids = new ArrayList<>();
            for (JsonValue m : list) ids.add(memberId(m));
            return () -> ids.forEach(uid -> removeMember(id, uid));
        }
        if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() != null) {
            String uid = singleEqValue(p.valueFilter());
            if (uid == null) return null;
            return () -> removeMember(id, uid);
        }
        return null;
    }

    /** Extracts X from a bare `value eq "X"` filter, else null. */
    private static String singleEqValue(Filter f) {
        if (f instanceof Filter.Compare c && c.op() == Op.EQ
                && c.path().attr().equalsIgnoreCase("value") && c.path().subAttr() == null
                && c.literal() instanceof JsonString s) {
            return s.value();
        }
        return null;
    }

    private static String memberId(JsonValue member) {
        if (member instanceof JsonObject o) {
            String v = o.optString("value", null);
            if (v != null) return v;
        }
        if (member instanceof JsonString s) return s.value();
        throw new BackendException(400, "invalidValue", "group member entries need a 'value'");
    }

    private void addMember(String groupId, String userId) {
        client.put("/api/v1/groups/" + pathSegment(groupId) + "/users/" + pathSegment(userId), null);
    }

    private void removeMember(String groupId, String userId) {
        client.delete("/api/v1/groups/" + pathSegment(groupId) + "/users/" + pathSegment(userId));
    }
}
