package org.retailbank360.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Builds a bounded {@link PageRequest} from untrusted query parameters.
 *
 * <p>Listing endpoints take {@code page} and {@code size} from the caller. Without a cap, a single
 * request for {@code size=1000000} would pull an entire table into memory and serialise it - an
 * accidental denial of service that needs no malice to trigger. The size is clamped rather than
 * rejected, so a caller asking for too much simply gets the maximum.</p>
 */
public final class PageRequests {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    private PageRequests() {
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.of(Math.max(0, page), clampSize(size));
    }

    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(0, page), clampSize(size), sort);
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
