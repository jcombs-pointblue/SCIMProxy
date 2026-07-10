package com.pointblue.scim.spi;

import com.pointblue.scim.filter.Filter;

/**
 * A page request. startIndex is 1-based per RFC 7644. filter is null when
 * unfiltered (or when the core has decided to filter in memory).
 */
public record SearchRequest(Filter filter, int startIndex, int count) {

    public SearchRequest {
        if (startIndex < 1) startIndex = 1;
        if (count < 0) count = 0;
    }
}
