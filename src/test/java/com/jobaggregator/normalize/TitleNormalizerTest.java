package com.jobaggregator.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TitleNormalizerTest {

    private final TitleNormalizer normalizer = new TitleNormalizer();

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "Sr. Software Engineer - Remote (US);            senior software engineer",
            "Senior Software Engineer;                       senior software engineer",
            "SENIOR SOFTWARE ENGINEER, REMOTE;               senior software engineer",
            "Software Engineer II, Backend;                  software engineer 2 backend",
            "Staff ML Engineer [Req #12345];                 staff machine learning engineer",
            "Engineering Manager, Payments — Hybrid;         engineering manager payments",
            "SWE Intern | Summer 2027;                       software engineer intern summer 2027",
            "C++ Developer;                                  c++ developer",
            "Front-End Engineer;                             frontend engineer",
            "Full Stack Developer;                           fullstack developer",
            "Sr Product Mgr - EMEA;                          senior product manager",
            "Software Engineer Remote;                       software engineer",
            "Senior Engineer, Backend (Card Acquisition);    senior engineer backend card acquisition",
            "[Job-31419] Mid-level Data Developer;           midlevel data developer",
            "BDR - UK;                                       business development representative",
            "Head of Customer Success & Support;             head of customer success and support",
    })
    void normalizes(String raw, String expected) {
        assertThat(normalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    void keepsSeniorityLevelsDistinct() {
        assertThat(normalizer.normalize("Software Engineer II"))
                .isNotEqualTo(normalizer.normalize("Software Engineer III"));
    }

    @Test
    void dropsSegmentsThatRepeatThePostingLocation() {
        assertThat(normalizer.normalize("Account Executive - New York", "New York, NY"))
                .isEqualTo("account executive");
        assertThat(normalizer.normalize("Account Executive, Toronto", "Toronto, Canada"))
                .isEqualTo(normalizer.normalize("Account Executive - Austin", "Austin, TX"));
    }

    @Test
    void dropsLeadingLocationPrefix() {
        assertThat(normalizer.normalize("[London] Applied AI Architect (Remote)", "London, UK"))
                .isEqualTo("applied ai architect");
        assertThat(normalizer.normalize("Remote - Customer Success Manager"))
                .isEqualTo("customer success manager");
    }

    @Test
    void keepsSegmentsThatAreNotLocations() {
        assertThat(normalizer.normalize("Account Executive, Enterprise", "New York, NY"))
                .isEqualTo("account executive enterprise");
    }

    @Test
    void foldsAccents() {
        assertThat(normalizer.normalize("Développeur Senior")).isEqualTo("developpeur senior");
    }
}
