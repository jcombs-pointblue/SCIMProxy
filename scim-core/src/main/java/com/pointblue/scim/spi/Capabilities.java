package com.pointblue.scim.spi;

/**
 * What the active backend can do natively. Drives ServiceProviderConfig
 * generation and the core's fallback decisions.
 *
 * @param nativePatch     backend implements {@link ResourceBackend#patch}; otherwise the
 *                        core emulates PATCH via get-apply-replace
 * @param filterable      backend applies {@link SearchRequest#filter()} itself; otherwise
 *                        the core fetches pages and evaluates the filter in memory
 * @param etagSupported   backend supplies a stable meta.version
 * @param maxPageSize     largest page the backend will return
 * @param maxInMemoryScan cap on resources the core may fetch for in-memory filtering
 */
public record Capabilities(
        boolean nativePatch,
        boolean filterable,
        boolean etagSupported,
        int maxPageSize,
        int maxInMemoryScan) {

    public static Capabilities inMemoryDefaults() {
        return new Capabilities(false, true, false, 200, 10_000);
    }
}
