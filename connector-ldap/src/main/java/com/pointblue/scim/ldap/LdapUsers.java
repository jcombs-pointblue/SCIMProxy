// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap;

import com.pointblue.scim.json.JsonBoolean;
import com.pointblue.scim.json.JsonObject;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** /Users backed by an LDAP directory. */
final class LdapUsers implements ResourceBackend {

    private static final Logger log = Logger.getLogger("scim.ldap");
    private static final String[] TIMESTAMPS =
            {"createTimestamp", "modifyTimestamp", "whenCreated", "whenChanged"};

    private final LdapStore store;
    private final LdapMapper mapper;
    private final LdapConfig config;
    private final PatchEngine patchEngine;
    private final String[] returnAttrs;
    private final LdapFilters.Resolver filterResolver;

    LdapUsers(LdapStore store, LdapMapper mapper, LdapConfig config, SchemaRegistry registry) {
        this.store = store;
        this.mapper = mapper;
        this.config = config;
        this.patchEngine = new PatchEngine(registry, "User");
        Set<String> attrs = new LinkedHashSet<>();
        for (LdapConfig.Mapping m : config.userMappings) attrs.add(m.ldapAttr());
        attrs.add(config.guidAttr);
        attrs.add("userAccountControl");
        attrs.add("loginDisabled");
        attrs.add("nsAccountLock");
        attrs.addAll(List.of(TIMESTAMPS));
        attrs.add(config.createdAttr);
        attrs.add(config.modifiedAttr);
        this.returnAttrs = attrs.toArray(new String[0]);
        this.filterResolver = new LdapFilters.Resolver() {
            @Override public LdapFilters.Target exact(AttributePath path) {
                LdapFilters.Target meta = config.metaTarget(path, CoreSchemas.USER_URN);
                return meta != null ? meta : mapper.exact(path);
            }
            @Override public List<Map<String, LdapFilters.Target>> elements(AttributePath path) {
                return mapper.elements(path);
            }
        };
    }

    String[] userReturnAttrs() { return returnAttrs; }

    // ---- SCIM shape ----

    JsonObject toScim(LdapStore.Entry entry) {
        JsonObject scim = mapper.toScim(entry.firstValues());
        scim.put("id", entry.guid());
        scim.put("active", readActive(entry));
        JsonObject meta = new JsonObject();
        meta.put("resourceType", "User");
        String created = LdapStore.toIso(coalesce(entry, config.createdAttr, "createTimestamp", "whenCreated"));
        String modified = LdapStore.toIso(coalesce(entry, config.modifiedAttr, "modifyTimestamp", "whenChanged"));
        if (created != null) meta.put("created", created);
        if (modified != null) meta.put("lastModified", modified);
        scim.put("meta", meta);
        return scim;
    }

    static String coalesce(LdapStore.Entry e, String... attrs) {
        for (String a : attrs) {
            String v = e.first(a);
            if (v != null) return v;
        }
        return null;
    }

    boolean readActive(LdapStore.Entry entry) {
        return switch (config.activeStrategy) {
            case USER_ACCOUNT_CONTROL -> (uacFlags(entry) & 2) == 0;
            case LOGIN_DISABLED -> !"TRUE".equalsIgnoreCase(entry.first("loginDisabled"));
            case NS_ACCOUNT_LOCK -> !"TRUE".equalsIgnoreCase(entry.first("nsAccountLock"));
            case NONE -> true;
        };
    }

    private static int uacFlags(LdapStore.Entry entry) {
        String v = entry == null ? null : entry.first("userAccountControl");
        if (v == null) return 512;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 512;
        }
    }

    private List<ModificationItem> activeMods(boolean desired, LdapStore.Entry current) {
        List<ModificationItem> mods = new ArrayList<>();
        switch (config.activeStrategy) {
            case USER_ACCOUNT_CONTROL -> {
                int flags = uacFlags(current);
                int updated = desired ? (flags & ~2) : (flags | 2);
                mods.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                        new BasicAttribute("userAccountControl", String.valueOf(updated))));
            }
            case LOGIN_DISABLED -> mods.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                    new BasicAttribute("loginDisabled", desired ? "FALSE" : "TRUE")));
            case NS_ACCOUNT_LOCK -> mods.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                    new BasicAttribute("nsAccountLock", desired ? "false" : "true")));
            case NONE -> log.warning("activeStrategy is 'none'; ignoring active=" + desired);
        }
        return mods;
    }

    private ModificationItem passwordMod(String password) {
        if (config.passwordAttr != null) {
            return new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                    new BasicAttribute(config.passwordAttr, password));
        }
        // AD semantics: unicodePwd is the UTF-16LE encoding of the quoted password
        if (!config.ldaps) {
            throw new BackendException(400, "invalidValue",
                    "Active Directory only accepts password changes over ldaps://");
        }
        byte[] quoted = ("\"" + password + "\"").getBytes(StandardCharsets.UTF_16LE);
        return new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                new BasicAttribute("unicodePwd", quoted));
    }

    // ---- CRUD ----

    @Override
    public JsonObject create(JsonObject resource) {
        String password = resource.optString("password", null);
        if (password != null && config.passwordAttr == null && !config.ldaps) {
            throw new BackendException(400, "invalidValue",
                    "Active Directory only accepts password changes over ldaps://");
        }
        Map<String, String> values = mapper.toLdap(resource, false);
        String rdnValue = firstIgnoreCase(values, config.userRdnAttr);
        if (rdnValue == null) {
            // AD's cn is typically unmapped (userName -> sAMAccountName); fall back to userName
            rdnValue = resource.optString("userName", null);
        }
        if (rdnValue == null) {
            throw new BackendException(400, "invalidValue",
                    "no SCIM attribute maps to the RDN attribute '" + config.userRdnAttr + "'");
        }
        String dn = config.userRdnAttr + "=" + Rdn.escapeValue(rdnValue) + "," + config.userBaseDn;

        BasicAttributes attrs = new BasicAttributes(true);
        BasicAttribute oc = new BasicAttribute("objectClass");
        config.userObjectClasses.forEach(oc::add);
        attrs.put(oc);
        values.forEach((k, v) -> { if (v != null) attrs.put(k, v); });

        // the 'person' object class requires cn; when uid (not cn) is the RDN nothing
        // maps to it, so derive one. No-op for flavors where cn is the RDN (already set).
        if (attrs.get("cn") == null) {
            String cn = deriveCn(resource, rdnValue);
            if (cn != null) attrs.put("cn", cn);
        }

        boolean active = resource.optBoolean("active", true);
        boolean uacStrategy = config.activeStrategy == LdapConfig.Active.USER_ACCOUNT_CONTROL;
        switch (config.activeStrategy) {
            // AD refuses to create an enabled account with no password in the add itself,
            // so always add disabled (514) and enable afterwards once a password is set.
            case USER_ACCOUNT_CONTROL -> attrs.put("userAccountControl", "514");
            case LOGIN_DISABLED -> attrs.put("loginDisabled", active ? "FALSE" : "TRUE");
            // 389-ds: absent nsAccountLock means active, so set it only to create disabled
            case NS_ACCOUNT_LOCK -> { if (!active) attrs.put("nsAccountLock", "true"); }
            case NONE -> { }
        }
        if (password != null && config.passwordAttr != null) attrs.put(config.passwordAttr, password);

        store.create(dn, attrs);
        if (password != null && config.passwordAttr == null) {
            store.modify(dn, List.of(passwordMod(password)));
        }
        if (uacStrategy && active) {
            // enable only once a password exists; AD would otherwise reject the enable
            if (password != null) {
                store.modify(dn, List.of(new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                        new BasicAttribute("userAccountControl", "512"))));
            } else {
                log.warning("AD user " + dn + " created disabled: cannot enable an account "
                        + "without a password. Provide 'password' on create to enable.");
            }
        }
        return toScim(store.lookup(dn, returnAttrs));
    }

    @Override
    public JsonObject get(String id) {
        return toScim(fetch(id));
    }

    LdapStore.Entry fetch(String id) {
        return store.findByGuid(id, config.userBaseDn, config.userFilter, returnAttrs);
    }

    @Override
    public JsonObject replace(String id, JsonObject resource) {
        LdapStore.Entry current = fetch(id);
        String dn = current.dn();
        Map<String, String> desired = mapper.toLdap(resource, true);

        // rename first when the RDN source attribute changed
        String newRdnValue = firstIgnoreCase(desired, config.userRdnAttr);
        if (newRdnValue != null && !newRdnValue.equals(current.first(config.userRdnAttr))) {
            dn = store.rename(dn, config.userRdnAttr, newRdnValue);
        }

        List<ModificationItem> mods = new ArrayList<>();
        for (Map.Entry<String, String> e : desired.entrySet()) {
            String attr = e.getKey();
            String newVal = e.getValue();
            String curVal = current.first(attr);
            if (attr.equalsIgnoreCase(config.userRdnAttr)) continue; // handled by rename
            if (newVal == null) {
                if (curVal != null) mods.add(new ModificationItem(DirContext.REMOVE_ATTRIBUTE,
                        new BasicAttribute(attr)));
            } else if (!newVal.equals(curVal)) {
                mods.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                        new BasicAttribute(attr, newVal)));
            }
        }
        JsonValue active = resource.getIgnoreCase("active");
        if (active instanceof JsonBoolean b && b.value() != readActive(current)) {
            mods.addAll(activeMods(b.value(), current));
        }
        String password = resource.optString("password", null);
        if (password != null) mods.add(passwordMod(password));
        store.modify(dn, mods);
        return toScim(store.lookup(dn, returnAttrs));
    }

    @Override
    public void delete(String id) {
        LdapStore.Entry entry = fetch(id);
        if (config.deleteDisablesOnly) {
            store.modify(entry.dn(), activeMods(false, entry));
        } else {
            store.destroy(entry.dn());
        }
    }

    @Override
    public SearchResult search(SearchRequest request) {
        String filter = config.userFilter;
        if (request.filter() != null) {
            filter = "(&" + filter + LdapFilters.toLdap(request.filter(), filterResolver) + ")";
        }
        LdapStore.Page page = store.search(config.userBaseDn, filter, returnAttrs,
                request.startIndex(), request.count());
        List<JsonObject> resources = new ArrayList<>();
        for (LdapStore.Entry e : page.entries()) resources.add(toScim(e));
        return new SearchResult(resources, page.total());
    }

    @Override
    public JsonObject patch(String id, List<PatchOp> ops) {
        LdapStore.Entry current = fetch(id);
        List<ModificationItem> mods = new ArrayList<>();
        boolean allNative = true;
        for (PatchOp op : ops) {
            AttributePath p = op.path();
            if (p == null || p.valueFilter() != null) { allNative = false; break; }
            if (p.schemaUrn() == null && p.subAttr() == null && p.attr().equalsIgnoreCase("active")
                    && op.type() != PatchOp.Type.REMOVE && op.value() instanceof JsonBoolean b) {
                mods.addAll(activeMods(b.value(), current));
                continue;
            }
            String ldapAttr = mapper.ldapAttrFor(p);
            if (ldapAttr == null || ldapAttr.equalsIgnoreCase(config.userRdnAttr)) {
                allNative = false; // renames and unmapped paths go through replace
                break;
            }
            if (op.type() == PatchOp.Type.REMOVE) {
                mods.add(new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute(ldapAttr)));
            } else {
                String v = LdapMapper.ldapString(op.value());
                if (v == null) { allNative = false; break; }
                mods.add(new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute(ldapAttr, v)));
            }
        }
        if (allNative) {
            store.modify(current.dn(), mods);
            return toScim(store.lookup(current.dn(), returnAttrs));
        }
        JsonObject patched = toScim(current);
        patchEngine.apply(patched, ops);
        return replace(id, patched);
    }

    private static String firstIgnoreCase(Map<String, String> values, String key) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    /** Best available common name: displayName, formatted, "given family", else the RDN value. */
    private static String deriveCn(JsonObject resource, String rdnValue) {
        String display = resource.optString("displayName", null);
        if (display != null && !display.isBlank()) return display;
        if (resource.getIgnoreCase("name") instanceof JsonObject name) {
            String formatted = name.optString("formatted", null);
            if (formatted != null && !formatted.isBlank()) return formatted;
            String full = (name.optString("givenName", "") + " "
                    + name.optString("familyName", "")).trim();
            if (!full.isBlank()) return full;
        }
        return rdnValue;
    }
}
