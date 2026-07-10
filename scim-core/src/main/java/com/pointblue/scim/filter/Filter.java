// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.filter;

import com.pointblue.scim.json.JsonValue;
import com.pointblue.scim.model.AttributePath;

/** Immutable filter AST (RFC 7644 §3.4.2.2). */
public sealed interface Filter {

    /** attrPath op literal, e.g. {@code userName eq "jdoe"} */
    record Compare(AttributePath path, Op op, JsonValue literal) implements Filter {}

    /** attrPath pr */
    record Present(AttributePath path) implements Filter {}

    /** attrPath[innerFilter], e.g. {@code emails[type eq "work"]} */
    record ValuePath(AttributePath path, Filter inner) implements Filter {}

    record And(Filter left, Filter right) implements Filter {}
    record Or(Filter left, Filter right) implements Filter {}
    record Not(Filter inner) implements Filter {}
}
