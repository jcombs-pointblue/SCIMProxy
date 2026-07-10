package com.pointblue.scim.ldap;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.ldap.tls.TrustAllSocketFactory;
import org.junit.jupiter.api.Test;

import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the JNDI environment wiring for LDAPS and the trust-all toggle. */
class TlsConfigTest {

    private LdapConfig config(String url, boolean insecure) {
        JsonObject cfg = Json.parseObject("""
                {"type":"ldap","flavor":"edirectory",
                 "url":"%s","bindDn":"cn=admin,o=t","bindPassword":"pw",
                 "insecureSkipTlsVerify":%s,
                 "users":{"baseDn":"ou=users,o=t"}}""".formatted(url, insecure));
        return new LdapConfig(cfg);
    }

    @Test
    void ldapsWithSkipVerifyInstallsTrustAllFactory() {
        Hashtable<String, Object> env = LdapConnections.buildEnv(
                config("ldaps://dir.example.com:636", true));
        assertEquals(TrustAllSocketFactory.class.getName(), env.get("java.naming.ldap.factory.socket"));
        assertEquals("false", env.get("com.sun.jndi.ldap.connect.pool"),
                "custom socket factory must disable the JNDI pool");
    }

    @Test
    void insecureLdapsDisablesEndpointIdentificationSystemProperty() {
        String key = "com.sun.jndi.ldap.object.disableEndpointIdentification";
        String prior = System.getProperty(key);
        System.clearProperty(key);
        try {
            new LdapConnections(config("ldaps://dir.example.com:636", true));
            assertEquals("true", System.getProperty(key),
                    "insecure LDAPS must disable hostname/IP identification (the only mechanism JNDI honors)");
        } finally {
            if (prior == null) System.clearProperty(key); else System.setProperty(key, prior);
        }
    }

    @Test
    void ldapsWithValidationLeavesDefaultTls() {
        Hashtable<String, Object> env = LdapConnections.buildEnv(
                config("ldaps://dir.example.com:636", false));
        assertNull(env.get("java.naming.ldap.factory.socket"));
        assertNull(env.get("com.sun.jndi.ldap.object.disableEndpointIdentification"));
    }

    @Test
    void plainLdapIgnoresSkipVerify() {
        Hashtable<String, Object> env = LdapConnections.buildEnv(
                config("ldap://dir.example.com:389", true));
        assertNull(env.get("java.naming.ldap.factory.socket"));
    }

    @Test
    void trustAllFactoryIsInstantiableViaJndiContract() {
        assertNotNull(TrustAllSocketFactory.getDefault());
        assertTrue(new TrustAllSocketFactory().getDefaultCipherSuites().length > 0);
    }


    @Test
    void timeoutsDefaultAndOverride() {
        Hashtable<String, Object> def = LdapConnections.buildEnv(
                config("ldaps://dir.example.com:636", false));
        assertEquals("5000", def.get("com.sun.jndi.ldap.connect.timeout"));
        assertEquals("30000", def.get("com.sun.jndi.ldap.read.timeout"));

        JsonObject cfg = Json.parseObject("""
                {"type":"ldap","flavor":"edirectory",
                 "url":"ldaps://dir.example.com:636","bindDn":"cn=a,o=t","bindPassword":"pw",
                 "connectTimeoutMs":8000,"readTimeoutMs":120000,
                 "users":{"baseDn":"ou=users,o=t"}}""");
        Hashtable<String, Object> env = LdapConnections.buildEnv(new LdapConfig(cfg));
        assertEquals("8000", env.get("com.sun.jndi.ldap.connect.timeout"));
        assertEquals("120000", env.get("com.sun.jndi.ldap.read.timeout"));
    }
}
