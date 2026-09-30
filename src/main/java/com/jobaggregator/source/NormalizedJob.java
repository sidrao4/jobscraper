package com.jobaggregator.source;

import java.time.Instant;

/** A posting mapped from any job board into one shape, before title normalization and dedup. */
public record NormalizedJob(
        String externalId,
        String title,
        String location,
        String department,
        String url,
        String description,
        Instant postedAt) {
}
