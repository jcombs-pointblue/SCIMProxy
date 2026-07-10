// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.server.endpoints;

import com.pointblue.scim.json.Json;
import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.schema.SchemaRegistry;
import com.pointblue.scim.spi.Capabilities;
import com.pointblue.scim.spi.ResourceBackend;
import com.pointblue.scim.spi.SearchRequest;
import com.pointblue.scim.spi.SearchResult;
import com.pointblue.scim.spi.UnsupportedFilterException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a backend throws UnsupportedFilterException for a specific filter, the
 * endpoint must fetch unfiltered pages and evaluate the filter in memory.
 */
class FilterFallbackTest {

    private static final class StubBackend implements ResourceBackend {
        final List<JsonObject> data = new ArrayList<>();
        int unfilteredSearches;

        @Override public JsonObject create(JsonObject r) { throw new UnsupportedOperationException(); }
        @Override public JsonObject get(String id) { throw new UnsupportedOperationException(); }
        @Override public JsonObject replace(String id, JsonObject r) { throw new UnsupportedOperationException(); }
        @Override public void delete(String id) { throw new UnsupportedOperationException(); }

        @Override
        public SearchResult search(SearchRequest request) {
            if (request.filter() != null) {
                throw new UnsupportedFilterException("cannot push down");
            }
            unfilteredSearches++;
            int from = Math.min(request.startIndex() - 1, data.size());
            int to = Math.min(from + request.count(), data.size());
            return new SearchResult(List.copyOf(data.subList(from, to)), data.size());
        }
    }

    @Test
    void fallsBackToInMemoryEvaluation() {
        SchemaRegistry registry = SchemaRegistry.withDefaults();
        StubBackend backend = new StubBackend();
        for (int i = 0; i < 7; i++) {
            backend.data.add(Json.parseObject(
                    "{\"id\":\"u" + i + "\",\"userName\":\"user" + i + "\",\"meta\":{}}"));
        }
        ResourceEndpoint endpoint = new ResourceEndpoint(registry, registry.resourceType("User"),
                backend, new Capabilities(false, true, false, 3, 100), "http://test", 100);

        Response r = endpoint.list(Map.of("filter", "userName eq \"user5\""));
        assertEquals(200, r.status());
        JsonObject body = r.body();
        assertEquals(1, body.get("totalResults").asNumber().intValue());
        assertEquals("user5", body.get("Resources").asArray()
                .get(0).asObject().optString("userName", null));
        assertTrue(backend.unfilteredSearches >= 2, "scanned in pages of maxPageSize");
    }
}
