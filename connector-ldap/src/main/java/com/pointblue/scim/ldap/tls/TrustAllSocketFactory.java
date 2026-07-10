// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.ldap.tls;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

/**
 * SSLSocketFactory that accepts any server certificate. Referenced by class
 * name via the java.naming.ldap.factory.socket JNDI property when
 * insecureSkipTlsVerify is enabled. Intended for lab/POC directories with
 * self-signed certificates — never production.
 */
public final class TrustAllSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TrustAllSocketFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            this.delegate = ctx.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot initialize trust-all TLS context", e);
        }
    }

    /** JNDI instantiates the factory through this method. */
    public static SocketFactory getDefault() {
        return new TrustAllSocketFactory();
    }

    // Note: this factory only bypasses certificate-chain validation. Hostname/IP
    // endpoint identification is a separate check that JNDI's LDAP provider forces
    // on after obtaining the socket, so it cannot be turned off here — it is
    // disabled via the com.sun.jndi.ldap.object.disableEndpointIdentification
    // system property, set by LdapConnections in insecure mode.

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }

    @Override public Socket createSocket() throws IOException {
        return delegate.createSocket();
    }
}
