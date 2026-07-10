package com.pointblue.scim.spi;

import com.pointblue.scim.json.JsonObject;
import com.pointblue.scim.patch.PatchOp;

import java.util.List;

/**
 * CRUD + search for one resource type against the native backend API.
 *
 * Contract: resources cross this boundary in SCIM JSON shape (extension
 * attributes under their URN keys). The backend owns {@code id} and
 * {@code meta.created}/{@code meta.lastModified}/{@code meta.resourceType};
 * the core owns validation, {@code meta.location}, and attribute projection.
 * Implementations throw only {@link BackendException}.
 */
public interface ResourceBackend {

    JsonObject create(JsonObject resource);

    JsonObject get(String id);

    JsonObject replace(String id, JsonObject resource);

    void delete(String id);

    SearchResult search(SearchRequest request);

    /**
     * Native partial update. Only called when
     * {@link Capabilities#nativePatch()} is true; otherwise the core applies
     * the ops itself and calls {@link #replace}.
     */
    default JsonObject patch(String id, List<PatchOp> ops) {
        throw new UnsupportedOperationException("native patch not supported");
    }
}
