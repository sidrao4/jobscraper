package com.jobaggregator.match;

import java.time.Instant;

public record MatchResult(
        long id,
        double score,
        String title,
        String company,
        String location,
        String department,
        String url,
        Instant postedAt) {
}
