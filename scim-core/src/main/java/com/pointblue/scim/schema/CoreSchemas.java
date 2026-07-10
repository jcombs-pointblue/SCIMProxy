// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.schema;

import java.util.List;

import static com.pointblue.scim.schema.Attribute.*;
import static com.pointblue.scim.schema.Attribute.AttributeType.*;
import static com.pointblue.scim.schema.Attribute.Mutability.*;
import static com.pointblue.scim.schema.Attribute.Returned.*;
import static com.pointblue.scim.schema.Attribute.Uniqueness.*;

/** Built-in RFC 7643 schemas, defined programmatically. */
public final class CoreSchemas {

    public static final String USER_URN = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP_URN = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String ENTERPRISE_USER_URN = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";

    public static final String LIST_RESPONSE_URN = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String PATCH_OP_URN = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String SEARCH_REQUEST_URN = "urn:ietf:params:scim:api:messages:2.0:SearchRequest";
    public static final String ERROR_URN = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String BULK_REQUEST_URN = "urn:ietf:params:scim:api:messages:2.0:BulkRequest";
    public static final String BULK_RESPONSE_URN = "urn:ietf:params:scim:api:messages:2.0:BulkResponse";

    private CoreSchemas() {}

    private static Attribute multiValued(String name, String... canonicalTypes) {
        return complex(name).multiValued().sub(
                string("value"),
                string("display"),
                string("type").canonical(canonicalTypes),
                bool("primary")).build();
    }

    public static Schema user() {
        return new Schema(USER_URN, "User", "User Account", List.of(
                string("userName").required().uniqueness(SERVER).build(),
                complex("name").sub(
                        string("formatted"), string("familyName"), string("givenName"),
                        string("middleName"), string("honorificPrefix"), string("honorificSuffix")).build(),
                string("displayName").build(),
                string("nickName").build(),
                reference("profileUrl", "external").build(),
                string("title").build(),
                string("userType").build(),
                string("preferredLanguage").build(),
                string("locale").build(),
                string("timezone").build(),
                bool("active").build(),
                string("password").writeOnly().build(),
                multiValued("emails", "work", "home", "other"),
                multiValued("phoneNumbers", "work", "home", "mobile", "fax", "pager", "other"),
                multiValued("ims", "aim", "gtalk", "icq", "xmpp", "msn", "skype", "qq", "yahoo"),
                multiValued("photos", "photo", "thumbnail"),
                complex("addresses").multiValued().sub(
                        string("formatted"), string("streetAddress"), string("locality"),
                        string("region"), string("postalCode"), string("country"),
                        string("type").canonical("work", "home", "other"), bool("primary")).build(),
                complex("groups").multiValued().readOnly().sub(
                        string("value").readOnly(),
                        reference("$ref", "User", "Group").readOnly(),
                        string("display").readOnly(),
                        string("type").canonical("direct", "indirect").readOnly()).build(),
                multiValued("entitlements"),
                multiValued("roles"),
                multiValued("x509Certificates")));
    }

    public static Schema group() {
        return new Schema(GROUP_URN, "Group", "Group", List.of(
                string("displayName").required().build(),
                complex("members").multiValued().sub(
                        string("value").immutable(),
                        reference("$ref", "User", "Group").immutable(),
                        string("display").readOnly(),
                        string("type").canonical("User", "Group").immutable()).build()));
    }

    public static Schema enterpriseUser() {
        return new Schema(ENTERPRISE_USER_URN, "EnterpriseUser", "Enterprise User", List.of(
                string("employeeNumber").build(),
                string("costCenter").build(),
                string("organization").build(),
                string("division").build(),
                string("department").build(),
                complex("manager").sub(
                        string("value"),
                        reference("$ref", "User"),
                        string("displayName").readOnly()).build()));
    }
}
