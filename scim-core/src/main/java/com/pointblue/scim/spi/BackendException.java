package com.pointblue.scim.spi;

import com.pointblue.scim.ScimException;

/**
 * The only exception connectors may throw. Extends ScimException so the HTTP
 * layer renders it uniformly; connectors translate native errors (e.g. Okta
 * API responses) into an appropriate status/scimType at the SPI boundary.
 */
public class BackendException extends ScimException {

    public BackendException(int status, String scimType, String detail) {
        super(status, scimType, detail);
    }

    public static BackendException notFound(String type, String id) {
        return new BackendException(404, null, type + " " + id + " not found");
    }

    public static BackendException conflict(String detail) {
        return new BackendException(409, "uniqueness", detail);
    }

    public static BackendException unavailable(String detail) {
        return new BackendException(503, null, detail);
    }
}
