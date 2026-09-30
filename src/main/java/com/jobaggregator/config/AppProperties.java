package com.jobaggregator.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app")
public record AppProperties(Ingest ingest, Dedup dedup, Gemini gemini) {

    public record Ingest(
            @DefaultValue("0 0 */6 * * *") String cron,
            @DefaultValue("true") boolean pollOnStartup,
            @DefaultValue("8") int concurrency,
            @DefaultValue("30s") Duration requestTimeout) {
    }

    public record Dedup(
            /* Jaccard similarity of description shingles at or above which two same-title postings are merged. */
            @DefaultValue("0.7") double similarityThreshold,
            @DefaultValue("4") int shingleSize,
            /* A description line repeated across this many distinct roles at one company is boilerplate. */
            @DefaultValue("3") int boilerplateMinTitles) {
    }

    public record Gemini(
            String apiKey,
            @DefaultValue("https://generativelanguage.googleapis.com/v1beta") String baseUrl,
            @DefaultValue("gemini-embedding-001") String model,
            @DefaultValue("768") int dimensions,
            @DefaultValue("100") int batchSize,
            /* Upper bound on postings embedded per ingestion run, to stay inside API quotas. 0 = unlimited. */
            @DefaultValue("0") int maxPerRun,
            @DefaultValue("7000") int maxInputChars) {

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
