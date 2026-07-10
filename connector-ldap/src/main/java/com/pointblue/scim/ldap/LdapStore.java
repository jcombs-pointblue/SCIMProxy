package com.pointblue.scim.ldap;

import com.pointblue.scim.spi.BackendException;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.Control;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;
import javax.naming.ldap.Rdn;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Low-level directory operations shared by the user and group backends. */
final class LdapStore {

    /** One directory entry: DN, opaque id, and first-class multi-valued attributes. */
    record Entry(String dn, String guid, Map<String, List<String>> attrs) {
        String first(String attr) {
            for (Map.Entry<String, List<String>> e : attrs.entrySet()) {
                if (e.getKey().equalsIgnoreCase(attr) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
        List<String> all(String attr) {
            for (Map.Entry<String, List<String>> e : attrs.entrySet()) {
                if (e.getKey().equalsIgnoreCase(attr)) return e.getValue();
            }
            return List.of();
        }
        Map<String, String> firstValues() {
            Map<String, String> m = new LinkedHashMap<>();
            attrs.forEach((k, v) -> { if (!v.isEmpty()) m.put(k, v.get(0)); });
            return m;
        }
    }

    private static final DateTimeFormatter GENERALIZED =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final LdapConnections connections;
    private final LdapConfig config;

    LdapStore(LdapConnections connections, LdapConfig config) {
        this.connections = connections;
        this.config = config;
    }

    interface ContextWork<T> {
        T run(LdapContext ctx) throws NamingException, IOException;
    }

    <T> T withContext(ContextWork<T> work) {
        LdapContext ctx = connections.open();
        try {
            return work.run(ctx);
        } catch (NameNotFoundException e) {
            throw new BackendException(404, null, "not found in directory: " + LdapConnections.message(e));
        } catch (NamingException | IOException e) {
            throw new BackendException(502, null, "LDAP error: " + e.getMessage());
        } finally {
            try { ctx.close(); } catch (NamingException ignored) { }
        }
    }

    /** Paged subtree search collecting entries until the requested window is covered. */
    record Page(List<Entry> entries, long total) {}

    Page search(String baseDn, String filter, String[] returnAttrs, int startIndex, int count) {
        int needed = Math.max(startIndex - 1 + count, 1);
        return withContext(ctx -> {
            List<Entry> collected = new ArrayList<>();
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setReturningAttributes(returnAttrs);
            byte[] cookie = null;
            boolean exhausted = false;
            do {
                ctx.setRequestControls(new Control[] {
                        new PagedResultsControl(config.pageSize, cookie, Control.CRITICAL)});
                NamingEnumeration<javax.naming.directory.SearchResult> results =
                        ctx.search(baseDn, filter, sc);
                while (results.hasMore()) {
                    javax.naming.directory.SearchResult r = results.next();
                    collected.add(toEntry(r.getNameInNamespace(), r.getAttributes()));
                }
                cookie = null;
                Control[] resp = ctx.getResponseControls();
                if (resp != null) {
                    for (Control c : resp) {
                        if (c instanceof PagedResultsResponseControl prc) cookie = prc.getCookie();
                    }
                }
                if (cookie == null || cookie.length == 0) { exhausted = true; break; }
            } while (collected.size() < needed);
            long total = exhausted ? collected.size() : -1;
            int from = Math.min(startIndex - 1, collected.size());
            int to = Math.min(from + count, collected.size());
            return new Page(List.copyOf(collected.subList(from, to)), total);
        });
    }

    Entry findByGuid(String guid, String baseDn, String objectFilter, String[] returnAttrs) {
        String filter = "(&" + objectFilter + "(" + config.guidAttr + "=" + guidFilterValue(guid) + "))";
        Page page = search(baseDn, filter, returnAttrs, 1, 2);
        if (page.entries().isEmpty()) throw new BackendException(404, null, "no entry with id " + guid);
        return page.entries().get(0);
    }

    Entry lookup(String dn, String[] returnAttrs) {
        return withContext(ctx -> toEntry(dn, ctx.getAttributes(dn, returnAttrs)));
    }

    void create(String dn, Attributes attrs) {
        withContext(ctx -> {
            try {
                ctx.createSubcontext(dn, attrs).close();
            } catch (javax.naming.NameAlreadyBoundException e) {
                throw BackendException.conflict("entry already exists: " + dn);
            }
            return null;
        });
    }

    void modify(String dn, List<ModificationItem> mods) {
        if (mods.isEmpty()) return;
        withContext(ctx -> {
            ctx.modifyAttributes(dn, mods.toArray(new ModificationItem[0]));
            return null;
        });
    }

    /** Applies one modification, ignoring already-present / already-absent errors. */
    void modifyIdempotent(String dn, ModificationItem mod) {
        withContext(ctx -> {
            try {
                ctx.modifyAttributes(dn, new ModificationItem[] {mod});
            } catch (javax.naming.directory.AttributeInUseException
                     | javax.naming.directory.NoSuchAttributeException ignored) {
                // value already there / already gone: fine for membership idempotency
            }
            return null;
        });
    }

    String rename(String oldDn, String newRdnAttr, String newRdnValue) {
        String parent = oldDn.substring(oldDn.indexOf(',') + 1);
        String newDn = newRdnAttr + "=" + Rdn.escapeValue(newRdnValue) + "," + parent;
        if (newDn.equalsIgnoreCase(oldDn)) return oldDn;
        withContext(ctx -> {
            ctx.rename(oldDn, newDn);
            return null;
        });
        return newDn;
    }

    void destroy(String dn) {
        withContext(ctx -> {
            ctx.destroySubcontext(dn);
            return null;
        });
    }

    // ---- entry/value plumbing ----

    private Entry toEntry(String dn, Attributes attributes) throws NamingException {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        String guid = null;
        NamingEnumeration<? extends javax.naming.directory.Attribute> all = attributes.getAll();
        while (all.hasMore()) {
            javax.naming.directory.Attribute a = all.next();
            List<String> values = new ArrayList<>();
            NamingEnumeration<?> vals = a.getAll();
            while (vals.hasMore()) {
                Object v = vals.next();
                values.add(v instanceof byte[] bytes ? hex(bytes) : String.valueOf(v));
            }
            if (a.getID().equalsIgnoreCase(config.guidAttr) && !values.isEmpty()) {
                guid = values.get(0);
            }
            attrs.put(a.getID(), List.copyOf(values));
        }
        return new Entry(dn, guid, attrs);
    }

    /** Filter-safe representation of an id: escaped bytes for binary GUIDs, escaped string otherwise. */
    private String guidFilterValue(String guid) {
        if (config.guidBinary) {
            if (!guid.matches("[0-9a-fA-F]+") || guid.length() % 2 != 0) {
                throw new BackendException(400, "invalidValue", "malformed resource id");
            }
            byte[] bytes = new byte[guid.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(guid.substring(i * 2, i * 2 + 2), 16);
            }
            return LdapFilters.escapeBytes(bytes);
        }
        return LdapFilters.escape(guid);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** LDAP generalized time (20260710120000Z / whenCreated) -> ISO 8601 instant, or null. */
    static String toIso(String generalizedTime) {
        if (generalizedTime == null || generalizedTime.length() < 14) return null;
        try {
            return LocalDateTime.parse(generalizedTime.substring(0, 14), GENERALIZED)
                    .toInstant(ZoneOffset.UTC).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
