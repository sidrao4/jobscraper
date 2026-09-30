package com.jobaggregator.ingest;

import java.time.Instant;

public record IngestionRun(
        long id,
        Instant startedAt,
        Instant finishedAt,
        int companiesPolled,
        int companiesFailed,
        int postingsSeen,
        int postingsNew,
        int postingsClosed,
        int duplicatesFound,
        int postingsEmbedded) {
}
