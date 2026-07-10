// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.spi;

import com.pointblue.scim.json.JsonObject;

import java.util.List;

/**
 * One page of results.
 *
 * @param resources    resources in SCIM shape
 * @param totalResults total matching resources, or -1 when the backend cannot
 *                     supply it cheaply
 */
public record SearchResult(List<JsonObject> resources, long totalResults) {

    public static SearchResult of(List<JsonObject> resources) {
        return new SearchResult(resources, resources.size());
    }
}
