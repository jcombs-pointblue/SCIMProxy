// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim;

/**
 * Protocol-level error carrying the HTTP status and RFC 7644 scimType.
 * The HTTP layer renders it as an urn:ietf:params:scim:api:messages:2.0:Error body.
 */
public class ScimException extends RuntimeException {

    private final int status;
    private final String scimType; // nullable

    public ScimException(int status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    public int status() { return status; }
    public String scimType() { return scimType; }

    public static ScimException invalidFilter(String detail) { return new ScimException(400, "invalidFilter", detail); }
    public static ScimException invalidPath(String detail)   { return new ScimException(400, "invalidPath", detail); }
    public static ScimException invalidValue(String detail)  { return new ScimException(400, "invalidValue", detail); }
    public static ScimException invalidSyntax(String detail) { return new ScimException(400, "invalidSyntax", detail); }
    public static ScimException noTarget(String detail)      { return new ScimException(400, "noTarget", detail); }
    public static ScimException mutability(String detail)    { return new ScimException(400, "mutability", detail); }
    public static ScimException uniqueness(String detail)    { return new ScimException(409, "uniqueness", detail); }
    public static ScimException notFound(String detail)      { return new ScimException(404, null, detail); }
}
