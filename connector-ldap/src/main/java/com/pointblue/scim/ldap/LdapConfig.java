package com.pointblue.scim.ldap;

import com.pointblue.scim.json.JsonArray;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.schema.CoreSchemas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed backend.{} block for the LDAP connector. One connector serves all
 * directory types: a "flavor" (edirectory | ad | openldap) is a preset bundle
 * of naming, membership, active-flag, and mapping defaults, every one of
 * which can be overridden in config.
 */
public final class LdapConfig {

    private static final String ENT = CoreSchemas.ENTERPRISE_USER_URN;

    public enum Active { USER_ACCOUNT_CONTROL, LOGIN_DISABLED, NONE }

    /**
     * One side-pair of group membership linkage. groupAttr is set on the group
     * entry (value: user DN); userAttr, when non-null, is the attribute the
     * connector must maintain on the user entry (value: group DN).
     *
     * eDirectory: member/groupMembership plus equivalentToMe/securityEquals —
     * the client maintains both sides. AD: member only — the server derives
     * memberOf. OpenLDAP: member only.
     */
    public record MemberPair(String groupAttr, String userAttr) {}

    public record Mapping(String scimPath, String ldapAttr) {}

    public final String url;
    public final boolean ldaps;
    public final String bindDn;
    public final String bindPassword;
    public final boolean insecureSkipTlsVerify;
    public final String flavor;

    public final String userBaseDn;
    public final String userRdnAttr;
    public final List<String> userObjectClasses;
    public final String userFilter;              // objectclass filter for searches
    public final List<Mapping> userMappings;     // defaults + config extras

    public final String groupBaseDn;
    public final String groupRdnAttr;
    public final List<String> groupObjectClasses;
    public final String groupFilter;
    public final List<MemberPair> memberPairs;

    public final String guidAttr;
    public final boolean guidBinary;
    public final Active activeStrategy;
    public final String passwordAttr;            // null = unicodePwd (AD semantics)
    public final boolean deleteDisablesOnly;
    public final int memberCap;
    public final int pageSize;
    public final int connectTimeoutMs;
    public final int readTimeoutMs;

    public LdapConfig(JsonObject cfg) {
        this.url = require(cfg, "url");
        this.ldaps = url.toLowerCase().startsWith("ldaps://");
        this.bindDn = require(cfg, "bindDn");
        this.bindPassword = require(cfg, "bindPassword");
        this.insecureSkipTlsVerify = cfg.optBoolean("insecureSkipTlsVerify", false);
        this.flavor = cfg.optString("flavor", null);
        Defaults d = switch (flavor) {
            case "edirectory" -> Defaults.EDIRECTORY;
            case "ad" -> Defaults.AD;
            case "openldap" -> Defaults.OPENLDAP;
            case null -> throw new IllegalArgumentException(
                    "ldap backend requires flavor: edirectory | ad | openldap");
            default -> throw new IllegalArgumentException("unknown ldap flavor '" + flavor + "'");
        };

        JsonObject users = section(cfg, "users");
        this.userBaseDn = require(users, "baseDn");
        this.userRdnAttr = users.optString("rdnAttr", d.userRdnAttr);
        this.userObjectClasses = stringList(users.get("objectClasses"), d.userObjectClasses);
        this.userFilter = users.optString("filter", d.userFilter);
        Map<String, String> mappings = new LinkedHashMap<>(d.userMappings);
        if (users.getIgnoreCase("mappings") instanceof JsonArray arr) {
            for (JsonValue v : arr) {
                JsonObject m = v.asObject();
                mappings.put(require(m, "scim"), require(m, "ldap"));
            }
        }
        List<Mapping> list = new ArrayList<>();
        mappings.forEach((scim, ldap) -> list.add(new Mapping(scim, ldap)));
        this.userMappings = List.copyOf(list);

        JsonObject groups = section(cfg, "groups");
        this.groupBaseDn = groups.optString("baseDn", null);
        this.groupRdnAttr = groups.optString("rdnAttr", "cn");
        this.groupObjectClasses = stringList(groups.get("objectClasses"), d.groupObjectClasses);
        this.groupFilter = groups.optString("filter", d.groupFilter);
        List<MemberPair> pairs = new ArrayList<>();
        if (groups.getIgnoreCase("memberPairs") instanceof JsonArray arr) {
            for (JsonValue v : arr) {
                JsonObject p = v.asObject();
                pairs.add(new MemberPair(require(p, "group"), p.optString("user", null)));
            }
        } else {
            pairs.addAll(d.memberPairs);
        }
        this.memberPairs = List.copyOf(pairs);

        this.guidAttr = cfg.optString("guidAttr", d.guidAttr);
        this.guidBinary = cfg.getIgnoreCase("guidBinary") != null
                ? cfg.optBoolean("guidBinary", false)
                : d.guidBinary && guidAttr.equalsIgnoreCase(d.guidAttr);
        this.activeStrategy = switch (cfg.optString("activeStrategy", d.activeStrategy)) {
            case "userAccountControl" -> Active.USER_ACCOUNT_CONTROL;
            case "loginDisabled" -> Active.LOGIN_DISABLED;
            case "none" -> Active.NONE;
            default -> throw new IllegalArgumentException("activeStrategy must be "
                    + "userAccountControl | loginDisabled | none");
        };
        this.passwordAttr = cfg.optString("passwordAttr", d.passwordAttr);
        String behavior = cfg.optString("deleteBehavior", "delete");
        this.deleteDisablesOnly = switch (behavior) {
            case "delete" -> false;
            case "disable" -> true;
            default -> throw new IllegalArgumentException(
                    "deleteBehavior must be 'delete' or 'disable', got '" + behavior + "'");
        };
        this.memberCap = intOpt(cfg, "memberCap", 1000);
        this.pageSize = intOpt(cfg, "pageSize", 500);
        this.connectTimeoutMs = intOpt(cfg, "connectTimeoutMs", 5000);
        // read timeout bounds any single LDAP operation; raise it for slow/broad subtree searches
        this.readTimeoutMs = intOpt(cfg, "readTimeoutMs", 30000);
    }

    // ---- flavor presets ----

    private record Defaults(String userRdnAttr, List<String> userObjectClasses, String userFilter,
                            Map<String, String> userMappings,
                            List<String> groupObjectClasses, String groupFilter,
                            List<MemberPair> memberPairs,
                            String guidAttr, boolean guidBinary,
                            String activeStrategy, String passwordAttr) {

        static Map<String, String> common(String userNameAttr) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("userName", userNameAttr);
            m.put("name.givenName", "givenName");
            m.put("name.familyName", "sn");
            m.put("displayName", "displayName");
            m.put("title", "title");
            m.put("emails[type eq \"work\"].value", "mail");
            m.put("phoneNumbers[type eq \"work\"].value", "telephoneNumber");
            m.put("phoneNumbers[type eq \"mobile\"].value", "mobile");
            m.put("addresses[type eq \"work\"].streetAddress", "street");
            m.put("addresses[type eq \"work\"].locality", "l");
            m.put("addresses[type eq \"work\"].region", "st");
            m.put("addresses[type eq \"work\"].postalCode", "postalCode");
            m.put(ENT + ":organization", "o");
            m.put(ENT + ":department", "ou");
            return m;
        }

        // eDirectory iManager stores Role-Based Services scope objects as groupOfNames
        // (classes rbsScope / rbsScope2), so a bare (objectClass=groupOfNames) sweeps in
        // that admin plumbing. Exclude both so only real groups are returned at any scope.
        static final String EDIR_GROUP_FILTER =
                "(&(objectClass=groupOfNames)(!(objectClass=rbsScope))(!(objectClass=rbsScope2)))";

        static final Defaults EDIRECTORY = new Defaults(
                "cn", List.of("top", "person", "organizationalPerson", "inetOrgPerson"),
                "(objectClass=inetOrgPerson)", common("cn"),
                List.of("top", "groupOfNames"), EDIR_GROUP_FILTER,
                List.of(new MemberPair("member", "groupMembership"),
                        new MemberPair("equivalentToMe", "securityEquals")),
                "GUID", true, "loginDisabled", "userPassword");

        static final Defaults AD;
        static {
            Map<String, String> m = common("sAMAccountName");
            m.put(ENT + ":department", "department");
            m.put(ENT + ":employeeNumber", "employeeID");
            m.put(ENT + ":organization", "company");
            AD = new Defaults(
                    "cn", List.of("top", "person", "organizationalPerson", "user"),
                    "(&(objectClass=user)(objectCategory=person))", m,
                    List.of("top", "group"), "(objectClass=group)",
                    List.of(new MemberPair("member", null)),
                    "objectGUID", true, "userAccountControl", null /* unicodePwd */);
        }

        static final Defaults OPENLDAP = new Defaults(
                "uid", List.of("top", "person", "organizationalPerson", "inetOrgPerson"),
                "(objectClass=inetOrgPerson)", common("uid"),
                List.of("top", "groupOfNames"), "(objectClass=groupOfNames)",
                List.of(new MemberPair("member", null)),
                "entryUUID", false, "none", "userPassword");
    }

    // ---- helpers ----

    /** Missing sections read as empty; required keys inside them still fail fast. */
    private static JsonObject section(JsonObject cfg, String name) {
        JsonValue v = cfg.getIgnoreCase(name);
        return v instanceof JsonObject o ? o : new JsonObject();
    }

    private static String require(JsonObject o, String name) {
        String v = o.optString(name, null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("ldap backend config requires '" + name + "'");
        }
        return v;
    }

    private static List<String> stringList(JsonValue v, List<String> fallback) {
        if (!(v instanceof JsonArray arr)) return fallback;
        List<String> list = new ArrayList<>();
        for (JsonValue e : arr) list.add(e.asString());
        return List.copyOf(list);
    }

    private static int intOpt(JsonObject o, String name, int fallback) {
        JsonValue v = o.getIgnoreCase(name);
        return v == null ? fallback : v.asNumber().intValue();
    }
}
