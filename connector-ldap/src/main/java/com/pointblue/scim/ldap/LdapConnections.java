package com.pointblue.scim.ldap;

import com.pointblue.scim.ldap.tls.TrustAllSocketFactory;
import com.pointblue.scim.spi.BackendException;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.util.Hashtable;

/**
 * Builds JNDI environments and contexts for the configured directory.
 * A fresh context per operation keeps thread-safety trivial; provisioning
 * traffic is low-volume enough that pooling isn't worth the complexity yet
 * (JNDI's built-in connection pool is enabled for plain and ssl).
 */
final class LdapConnections {

    private final Hashtable<String, Object> env;

    LdapConnections(LdapConfig config) {
        this.env = buildEnv(config);
    }

    static Hashtable<String, Object> buildEnv(LdapConfig config) {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, config.url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, config.bindDn);
        env.put(Context.SECURITY_CREDENTIALS, config.bindPassword);
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(config.connectTimeoutMs));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(config.readTimeoutMs));
        env.put("com.sun.jndi.ldap.connect.pool", "true");
        // GUID-bearing attributes must come back as byte[] (string GUIDs like entryUUID must not)
        String binary = "objectGUID GUID objectSid";
        if (config.guidBinary) binary += " " + config.guidAttr;
        env.put("java.naming.ldap.attributes.binary", binary);
        if (config.ldaps && config.insecureSkipTlsVerify) {
            env.put("java.naming.ldap.factory.socket", TrustAllSocketFactory.class.getName());
            // trust-all implies accepting a mismatched hostname too
            env.put("com.sun.jndi.ldap.object.disableEndpointIdentification", "true");
            // the custom socket factory is incompatible with JNDI's pool
            env.put("com.sun.jndi.ldap.connect.pool", "false");
        }
        return env;
    }

    LdapContext open() {
        try {
            return new InitialLdapContext(env, null);
        } catch (NamingException e) {
            throw BackendException.unavailable("cannot connect/bind to LDAP: " + message(e));
        }
    }

    static String message(NamingException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
