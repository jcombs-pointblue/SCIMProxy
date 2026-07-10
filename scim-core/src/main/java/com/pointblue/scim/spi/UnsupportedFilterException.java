// SPDX-License-Identifier: MIT
// Copyright (c) 2026 PointBlue Technology LLC
package com.pointblue.scim.spi;

/**
 * Thrown by {@link ResourceBackend#search} when this particular filter cannot
 * be translated to the native query language. The core then falls back to
 * fetching unfiltered pages and evaluating the filter in memory (bounded by
 * {@link Capabilities#maxInMemoryScan()}).
 */
public class UnsupportedFilterException extends RuntimeException {
    public UnsupportedFilterException(String message) { super(message); }
}
