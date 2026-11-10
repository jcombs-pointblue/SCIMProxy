// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap;

import com.pointblue.scim.filter.FilterParser;
import com.pointblue.scim.model.AttributePath;
import com.pointblue.scim.schema.CoreSchemas;
import com.pointblue.scim.spi.BackendException;
import com.pointblue.scim.spi.UnsupportedFilterException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SCIM filter -> LDAP filter translation, independent of a directory. */
class LdapFiltersTest {

    private static final LdapMapper MAPPER = new LdapMapper(List.of(
            new LdapConfig.Mapping("userName", "uid"),
            new LdapConfig.Mapping("emails[type eq \"work\"].value", "mail"),
            new LdapConfig.Mapping("emails[type eq \"home\"].value", "otherMail"),
            new LdapConfig.Mapping("phoneNumbers[type eq \"work\"].value", "telephoneNumber"),
            new LdapConfig.Mapping("addresses[type eq \"work\"].locality", "l"),
            new LdapConfig.Mapping("addresses[type eq \"work\"].region", "st")));

    private static LdapFilters.Resolver resolver(LdapFilters.TimeSyntax syntax) {
        return new LdapFilters.Resolver() {
            @Override public LdapFilters.Target exact(AttributePath p) {
                if (p.attr().equalsIgnoreCase("meta") && p.subAttr() != null) {
                    String a = p.subAttr().equalsIgnoreCase("created") ? "whenCreated" : "whenChanged";
                    return LdapFilters.Target.time(a, syntax);
                }
                return MAPPER.exact(p);
            }
            @Override public List<Map<String, LdapFilters.Target>> elements(AttributePath p) {
                return MAPPER.elements(p);
            }
        };
    }

    private static String ldap(String scim) {
        return ldap(scim, LdapFilters.TimeSyntax.WHOLE_SECONDS);
    }

    private static String ldap(String scim, LdapFilters.TimeSyntax syntax) {
        return LdapFilters.toLdap(FilterParser.parse(scim), resolver(syntax));
    }

    // ---- meta timestamps ----

    @Test
    void metaTimestampsBecomeGeneralizedTime() {
        assertEquals("(whenCreated>=20251116014742.0Z)",
                ldap("meta.created ge \"2025-11-16T01:47:42Z\"", LdapFilters.TimeSyntax.FRACTION));
        assertEquals("(whenChanged<=20251116014742Z)",
                ldap("meta.lastModified le \"2025-11-16T01:47:42Z\""));
        assertEquals("(&(whenCreated>=20251116014742Z)(!(whenCreated=20251116014742Z)))",
                ldap("meta.created gt \"2025-11-16T01:47:42Z\""));
        assertEquals("(whenCreated=*)", ldap("meta.created pr"));
    }

    @Test
    void metaLiteralIsNormalizedToUtc() {
        assertEquals("(whenCreated>=20251116064742Z)",
                ldap("meta.created ge \"2025-11-16T01:47:42-05:00\""));
        assertEquals("(whenCreated>=20251116000000Z)", ldap("meta.created ge \"2025-11-16\""));
    }

    @Test
    void fractionalLiteralRoundsToKeepComparisonExact() {
        // directory timestamps are whole seconds: ge 42.5 == ge 43, le 42.5 == le 42
        assertEquals("(whenCreated>=20251116014743Z)", ldap("meta.created ge \"2025-11-16T01:47:42.5Z\""));
        assertEquals("(whenCreated<=20251116014742Z)", ldap("meta.created le \"2025-11-16T01:47:42.5Z\""));
        assertEquals("(&(whenCreated>=20251116014742Z)(!(whenCreated=20251116014742Z)))",
                ldap("meta.created gt \"2025-11-16T01:47:42.5Z\""));
        assertEquals("(&(whenCreated<=20251116014743Z)(!(whenCreated=20251116014743Z)))",
                ldap("meta.created lt \"2025-11-16T01:47:42.5Z\""));
        assertEquals(LdapFilters.FALSE, ldap("meta.created eq \"2025-11-16T01:47:42.5Z\""));
    }

    @Test
    void metaRejectsNonDateLiteralsAndSubstringOps() {
        BackendException e = assertThrows(BackendException.class, () -> ldap("meta.created ge \"yesterday\""));
        assertEquals(400, e.status());
        assertThrows(UnsupportedFilterException.class, () -> ldap("meta.created sw \"2025\""));
    }

    // ---- multi-valued attributes ----

    @Test
    void emailsValueReadsEveryMappedElement() {
        assertEquals("(|(mail=a@x.com)(otherMail=a@x.com))", ldap("emails.value eq \"a@x.com\""));
        assertEquals("(|(mail=a@x.com)(otherMail=a@x.com))", ldap("emails eq \"a@x.com\""));
        assertEquals("(|(mail=*@x.com)(otherMail=*@x.com))", ldap("emails ew \"@x.com\""));
        assertEquals("(|(mail=*)(otherMail=*))", ldap("emails pr"));
        assertEquals("(telephoneNumber=555)", ldap("phoneNumbers.value eq \"555\""));
        assertEquals("(l=Paris)", ldap("addresses.locality eq \"Paris\""));
    }

    @Test
    void valuePathFoldsTheMappedType() {
        assertEquals("(mail=a@x.com)", ldap("emails[type eq \"work\" and value eq \"a@x.com\"]"));
        assertEquals("(|(mail=a@x.com)(otherMail=a@x.com))", ldap("emails[value eq \"a@x.com\"]"));
        assertEquals("(mail=*)", ldap("emails[type eq \"work\"]"));
        assertEquals("(&(|(l=*)(st=*))(st=CA))", ldap("addresses[type eq \"work\" and region eq \"CA\"]"));
        // no element has type "other": matches nothing
        assertEquals(LdapFilters.FALSE, ldap("emails[type eq \"other\"]"));
        assertEquals("(&(uid=jd)(mail=*))", ldap("userName eq \"jd\" and emails[type eq \"work\"]"));
        assertEquals("(uid=jd)", ldap("userName eq \"jd\" or emails[type eq \"other\"]"));
    }

    @Test
    void unmappableStillSignalsFallback() {
        assertThrows(UnsupportedFilterException.class, () -> ldap("emails[primary eq true]"));
        assertThrows(UnsupportedFilterException.class, () -> ldap("ims.value eq \"x\""));
        assertThrows(UnsupportedFilterException.class, () -> ldap("nickName eq \"x\""));
    }

    @Test
    void valuesAreStillEscaped() {
        assertEquals("(|(mail=\\2a\\29)(otherMail=\\2a\\29))", ldap("emails.value eq \"*)\""));
    }

    @Test
    void configPicksFlavorTimestampAttributes() {
        AttributePath created = FilterParser.parsePath("meta.created");
        LdapConfig ad = new LdapConfig(com.pointblue.scim.json.Json.parseObject("""
                {"url":"ldap://x","bindDn":"a","bindPassword":"b","flavor":"ad","users":{"baseDn":"o=x"}}"""));
        assertEquals("whenCreated", ad.metaTarget(created, CoreSchemas.USER_URN).ldapAttr());
        assertEquals(LdapFilters.TimeSyntax.FRACTION, ad.timeSyntax);
        LdapConfig edir = new LdapConfig(com.pointblue.scim.json.Json.parseObject("""
                {"url":"ldap://x","bindDn":"a","bindPassword":"b","flavor":"edirectory","users":{"baseDn":"o=x"}}"""));
        assertEquals("createTimestamp", edir.metaTarget(created, CoreSchemas.USER_URN).ldapAttr());
        assertEquals("modifyTimestamp",
                edir.metaTarget(FilterParser.parsePath("meta.lastModified"), CoreSchemas.USER_URN).ldapAttr());
        assertNull(edir.metaTarget(FilterParser.parsePath("meta.resourceType"), CoreSchemas.USER_URN));
    }
}
