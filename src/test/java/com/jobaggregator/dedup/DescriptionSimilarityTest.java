package com.jobaggregator.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class DescriptionSimilarityTest {

    private static final String BASE = """
            We are looking for a backend engineer to build payment APIs in Java and Spring Boot.
            You will own services end to end, work with PostgreSQL and Kafka, and mentor engineers.
            Requirements: 5+ years of experience building distributed systems at scale.
            """;

    @Test
    void identicalTextIsFullySimilar() {
        assertThat(sim(BASE, BASE)).isEqualTo(1.0);
    }

    @Test
    void smallEditsStayAboveThreshold() {
        String repost = BASE.replace("5+ years", "5+ years") + " Location: Austin, TX.";
        assertThat(sim(BASE, repost)).isGreaterThan(0.8);
    }

    @Test
    void differentRolesAreDissimilar() {
        String other = """
                Join our design team to craft delightful mobile experiences in Figma.
                You will run user research, prototype flows, and partner with product managers.
                """;
        assertThat(sim(BASE, other)).isLessThan(0.05);
    }

    @Test
    void caseAndPunctuationDoNotMatter() {
        assertThat(sim(BASE, BASE.toUpperCase().replace(".", " ; "))).isEqualTo(1.0);
    }

    private static double sim(String a, String b) {
        Set<Long> sa = DescriptionSimilarity.shingles(a, 5);
        Set<Long> sb = DescriptionSimilarity.shingles(b, 5);
        return DescriptionSimilarity.jaccard(sa, sb);
    }
}
