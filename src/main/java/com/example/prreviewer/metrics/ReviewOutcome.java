package com.example.prreviewer.metrics;

/**
 * Terminal outcome of a review job, used as a low-cardinality metric tag on
 * {@code reviews.processed}. Fixed, small set — never derived from repo/PR data.
 */
public enum ReviewOutcome {
    DONE("done"),
    FAILED("failed"),
    SKIPPED_DUP("skipped_dup"),
    SKIPPED_STALE("skipped_stale"),
    RATE_LIMITED("rate_limited");

    private final String tag;

    ReviewOutcome(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
