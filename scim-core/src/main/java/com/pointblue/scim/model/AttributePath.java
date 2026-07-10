// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.model;

import com.pointblue.scim.filter.Filter;

/**
 * A parsed RFC 7644 attribute path:
 * {@code [schemaUrn:]attr[valueFilter][.subAttr]}
 * e.g. {@code userName}, {@code name.givenName},
 * {@code emails[type eq "work"].value},
 * {@code urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager}.
 *
 * @param schemaUrn   schema URI prefix, or null for the resource's base schema
 * @param attr        top-level attribute name
 * @param valueFilter filter over elements of a multi-valued attribute, or null
 * @param subAttr     sub-attribute of a complex attribute, or null
 */
public record AttributePath(String schemaUrn, String attr, Filter valueFilter, String subAttr) {

    public static AttributePath of(String attr) {
        return new AttributePath(null, attr, null, null);
    }

    public static AttributePath of(String attr, String subAttr) {
        return new AttributePath(null, attr, null, subAttr);
    }

    public boolean hasValueFilter() { return valueFilter != null; }
    public boolean hasSubAttr() { return subAttr != null; }

    /** Path without the value filter (for schema lookup): attr or attr.subAttr with urn. */
    public String display() {
        StringBuilder sb = new StringBuilder();
        if (schemaUrn != null) sb.append(schemaUrn).append(':');
        sb.append(attr);
        if (valueFilter != null) sb.append("[...]");
        if (subAttr != null) sb.append('.').append(subAttr);
        return sb.toString();
    }

    @Override public String toString() { return display(); }
}
