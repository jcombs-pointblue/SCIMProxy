// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap;

import com.pointblue.scim.filter.Filter;
import com.pointblue.scim.filter.Op;
import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonString;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.patch.PatchEngine;
import com.pointblue.scim.patch.PatchOp;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;

import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.ldap.Rdn;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * /Groups backed by an LDAP directory. Membership writes honor the configured
 * member pairs: every pair's group-side attribute gets the user DN, and pairs
 * with a user-side attribute (eDirectory's groupMembership/securityEquals)
 * are maintained on the user entry as well.
 */
final class LdapGroups implements ResourceBackend {

    private final LdapStore store;
    private final LdapUsers users;
    private final LdapConfig config;
    private final PatchEngine patchEngine;
    private final String[] returnAttrs;

    LdapGroups(LdapStore store, LdapUsers users, LdapConfig config, SchemaRegistry registry) {
        this.store = store;
        this.users = users;
        this.config = config;
        this.patchEngine = new PatchEngine(registry, "Group");
        Set<String> attrs = new LinkedHashSet<>();
        attrs.add(config.groupRdnAttr);
        attrs.add(config.guidAttr);
        for (LdapConfig.MemberPair p : config.memberPairs) attrs.add(p.groupAttr());
        attrs.add("createTimestamp");
        attrs.add("modifyTimestamp");
        attrs.add("whenCreated");
        attrs.add("whenChanged");
        attrs.add(config.createdAttr);
        attrs.add(config.modifiedAttr);
        this.returnAttrs = attrs.toArray(new String[0]);
    }

    private String memberAttr() { return config.memberPairs.get(0).groupAttr(); }

    private String groupBase() {
        if (config.groupBaseDn == null) {
            throw new BackendException(501, null, "groups.baseDn is not configured");
        }
        return config.groupBaseDn;
    }

    // ---- SCIM shape ----

    private JsonObject toScim(LdapStore.Entry entry, boolean includeMembers) {
        JsonObject scim = new JsonObject();
        scim.put("schemas", new JsonArray().add(CoreSchemas.GROUP_URN));
        scim.put("id", entry.guid());
        scim.put("displayName", entry.first(config.groupRdnAttr));
        if (includeMembers) {
            JsonArray members = new JsonArray();
            List<String> dns = entry.all(memberAttr());
            for (String dn : dns.subList(0, Math.min(dns.size(), config.memberCap))) {
                try {
                    LdapStore.Entry user = store.lookup(dn,
                            new String[] {config.guidAttr, config.userRdnAttr});
                    JsonObject m = new JsonObject();
                    m.put("value", user.guid());
                    String display = user.first(config.userRdnAttr);
                    if (display != null) m.put("display", display);
                    members.add(m);
                } catch (BackendException dangling) {
                    // referenced entry no longer exists; skip it
                }
            }
            scim.put("members", members);
        }
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "Group");
        String created = LdapStore.toIso(LdapUsers.coalesce(entry, config.createdAttr, "createTimestamp", "whenCreated"));
        String modified = LdapStore.toIso(LdapUsers.coalesce(entry, config.modifiedAttr, "modifyTimestamp", "whenChanged"));
        if (created != null) meta.put("created", created);
        if (modified != null) meta.put("lastModified", modified);
        scim.put("meta", meta);
        return scim;
    }

    // ---- CRUD ----

    @Override
    public JsonObject create(JsonObject resource) {
        String name = resource.optString("displayName", null);
        if (name == null) throw new BackendException(400, "invalidValue", "displayName is required");
        String dn = config.groupRdnAttr + "=" + Rdn.escapeValue(name) + "," + groupBase();

        List<String> memberDns = new ArrayList<>();
        if (resource.getIgnoreCase("members") instanceof JsonArray members) {
            for (JsonValue m : members) memberDns.add(users.fetch(memberId(m)).dn());
        }
        BasicAttributes attrs = new BasicAttributes(true);
        BasicAttribute oc = new BasicAttribute("objectClass");
        config.groupObjectClasses.forEach(oc::add);
        attrs.put(oc);
        attrs.put(config.groupRdnAttr, name);
        if (!memberDns.isEmpty()) {
            for (LdapConfig.MemberPair pair : config.memberPairs) {
                BasicAttribute a = new BasicAttribute(pair.groupAttr());
                memberDns.forEach(a::add);
                attrs.put(a);
            }
        }
        store.create(dn, attrs);
        // user-side halves of the membership pairs
        for (String userDn : memberDns) applyUserSide(dn, userDn, true);
        return toScim(store.lookup(dn, returnAttrs), true);
    }

    @Override
    public JsonObject get(String id) {
        return toScim(fetch(id), true);
    }

    private LdapStore.Entry fetch(String id) {
        return store.findByGuid(id, groupBase(), config.groupFilter, returnAttrs);
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        LdapStore.Entry current = fetch(id);
        String dn = current.dn();
        String name = resource.optString("displayName", null);
        if (name != null && !name.equals(current.first(config.groupRdnAttr))) {
            dn = store.rename(dn, config.groupRdnAttr, name);
        }
        // reconcile membership from the submitted list
        Set<String> desiredDns = new LinkedHashSet<>();
        if (resource.getIgnoreCase("members") instanceof JsonArray members) {
            for (JsonValue m : members) desiredDns.add(users.fetch(memberId(m)).dn());
        }
        Set<String> currentDns = new LinkedHashSet<>(current.all(memberAttr()));
        for (String userDn : desiredDns) {
            if (!containsIgnoreCase(currentDns, userDn)) changeMembership(dn, userDn, true);
        }
        for (String userDn : currentDns) {
            if (!containsIgnoreCase(desiredDns, userDn)) changeMembership(dn, userDn, false);
        }
        return toScim(store.lookup(dn, returnAttrs), true);
    }

    @Override
    public void delete(String id) {
        LdapStore.Entry entry = fetch(id);
        // clear user-side membership attributes before removing the group
        for (String userDn : entry.all(memberAttr())) applyUserSide(entry.dn(), userDn, false);
        store.destroy(entry.dn());
    }

    @Override
    public SearchResult search(SearchRequest request) {
        String filter = config.groupFilter;
        if (request.filter() != null) {
            filter = "(&" + filter + LdapFilters.toLdap(request.filter(), this::groupTarget) + ")";
        }
        LdapStore.Page page = store.search(groupBase(), filter, returnAttrs,
                request.startIndex(), request.count());
        List<JsonObject> resources = new ArrayList<>();
        for (LdapStore.Entry e : page.entries()) resources.add(toScim(e, config.membersInList));
        return new SearchResult(resources, page.total());
    }

    private LdapFilters.Target groupTarget(AttributePath path) {
        if (path.valueFilter() == null && path.subAttr() == null && path.attr().equalsIgnoreCase("displayName")) {
            return LdapFilters.Target.attr(config.groupRdnAttr);
        }
        return config.metaTarget(path, CoreSchemas.GROUP_URN);
    }

    // ---- PATCH ----

    @Override
    public JsonObject patch(String id, List<PatchOp> ops) {
        LdapStore.Entry current = fetch(id);
        List<Runnable> actions = new ArrayList<>();
        for (PatchOp op : ops) {
            Runnable action = nativeAction(current.dn(), op);
            if (action == null) {
                JsonObject patched = toScim(current, true);
                patchEngine.apply(patched, ops);
                return replace(id, patched);
            }
            actions.add(action);
        }
        actions.forEach(Runnable::run);
        return toScim(store.lookup(current.dn(), returnAttrs), true);
    }

    private Runnable nativeAction(String groupDn, PatchOp op) {
        AttributePath p = op.path();
        if (p == null || p.schemaUrn() != null) return null;
        if (p.attr().equalsIgnoreCase("displayName")) return null; // rename via replace path
        if (!p.attr().equalsIgnoreCase("members") || p.subAttr() != null) return null;

        if (op.type() == PatchOp.Type.ADD && p.valueFilter() == null && op.value() != null) {
            JsonArray additions = op.value() instanceof JsonArray a ? a : new JsonArray().add(op.value());
            List<String> dns = new ArrayList<>();
            for (JsonValue m : additions) dns.add(users.fetch(memberId(m)).dn());
            return () -> dns.forEach(dn -> changeMembership(groupDn, dn, true));
        }
        if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() == null
                && op.value() instanceof JsonArray list) {
            List<String> dns = new ArrayList<>();
            for (JsonValue m : list) dns.add(users.fetch(memberId(m)).dn());
            return () -> dns.forEach(dn -> changeMembership(groupDn, dn, false));
        }
        if (op.type() == PatchOp.Type.REMOVE && p.valueFilter() != null) {
            String memberId = singleEqValue(p.valueFilter());
            if (memberId == null) return null;
            String dn = users.fetch(memberId).dn();
            return () -> changeMembership(groupDn, dn, false);
        }
        return null;
    }

    private static String singleEqValue(Filter f) {
        if (f instanceof Filter.Compare c && c.op() == Op.EQ
                && c.path().attr().equalsIgnoreCase("value") && c.path().subAttr() == null
                && c.literal() instanceof JsonString s) {
            return s.value();
        }
        return null;
    }

    // ---- membership plumbing ----

    /** Adds or removes one user across every configured member pair (both sides). */
    private void changeMembership(String groupDn, String userDn, boolean add) {
        int mod = add ? DirContext.ADD_ATTRIBUTE : DirContext.REMOVE_ATTRIBUTE;
        for (LdapConfig.MemberPair pair : config.memberPairs) {
            store.modifyIdempotent(groupDn,
                    new ModificationItem(mod, new BasicAttribute(pair.groupAttr(), userDn)));
        }
        applyUserSide(groupDn, userDn, add);
    }

    /** The user-entry half: e.g. eDirectory groupMembership and securityEquals. */
    private void applyUserSide(String groupDn, String userDn, boolean add) {
        int mod = add ? DirContext.ADD_ATTRIBUTE : DirContext.REMOVE_ATTRIBUTE;
        for (LdapConfig.MemberPair pair : config.memberPairs) {
            if (pair.userAttr() == null) continue;
            store.modifyIdempotent(userDn,
                    new ModificationItem(mod, new BasicAttribute(pair.userAttr(), groupDn)));
        }
    }

    private static String memberId(JsonValue member) {
        if (member instanceof JsonObject o) {
            String v = o.optString("value", null);
            if (v != null) return v;
        }
        if (member instanceof JsonString s) return s.value();
        throw new BackendException(400, "invalidValue", "group member entries need a 'value'");
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
