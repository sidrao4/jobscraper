package com.jobaggregator.company;

import java.time.Instant;

public record Company(
        long id,
        String name,
        Source source,
        String boardToken,
        boolean enabled,
        Instant lastPolledAt,
        String lastPollError) {
}
