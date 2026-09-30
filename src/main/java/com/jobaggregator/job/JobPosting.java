package com.jobaggregator.job;

import java.time.Instant;

public record JobPosting(
        long id,
        long companyId,
        String companyName,
        String externalId,
        String title,
        String normalizedTitle,
        String location,
        String department,
        String url,
        String description,
        Instant postedAt,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant closedAt,
        Long canonicalId) {
}
