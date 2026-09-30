package com.jobaggregator.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.jobaggregator.PostgresTestSupport;
import com.jobaggregator.company.Source;
import com.jobaggregator.embedding.EmbeddingClient;
import com.jobaggregator.embedding.FakeEmbeddingClient;
import com.jobaggregator.job.JobPostingRepository;
import com.jobaggregator.source.GreenhouseClient;
import com.jobaggregator.source.LeverClient;
import com.jobaggregator.source.NormalizedJob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class IngestionIntegrationTest extends PostgresTestSupport {

    private static final String BACKEND = """
            Build payment APIs in Java and Spring Boot. Own backend services end to end on PostgreSQL and Kafka.
            You will design distributed systems, write clean code, and mentor other backend engineers on the team.
            """;
    private static final String BACKEND_OTHER_TEAM = """
            Our infrastructure group runs Kubernetes clusters and Terraform across three clouds.
            You will automate provisioning, improve observability, and join the on-call rotation for the platform.
            """;
    private static final String DESIGN = """
            Craft delightful mobile experiences in Figma. Run user research sessions, prototype new flows,
            and partner with product managers to ship polished interfaces for millions of customers.
            """;
    private static final String DATA = """
            Build batch and streaming data pipelines with Airflow, Spark, and dbt. Model the warehouse,
            keep data quality high, and help analysts answer questions quickly with trustworthy tables.
            """;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeEmbeddingClient fakeEmbeddingClient() {
            return new FakeEmbeddingClient();
        }
    }

    @MockitoBean
    GreenhouseClient greenhouse;

    @MockitoBean
    LeverClient lever;

    @Autowired
    IngestionService ingestion;

    @Autowired
    JobPostingRepository postings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EmbeddingClient embeddingClient;

    @Autowired
    MockMvc mvc;

    private long stripeId;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM job_postings").update();
        jdbc.sql("DELETE FROM ingestion_runs").update();
        jdbc.sql("UPDATE companies SET enabled = FALSE").update();
        stripeId = jdbc.sql("UPDATE companies SET enabled = TRUE WHERE board_token = 'stripe' RETURNING id")
                .query(Long.class).single();
        jdbc.sql("UPDATE companies SET enabled = TRUE WHERE board_token = 'palantir'").update();
        given(greenhouse.source()).willReturn(Source.GREENHOUSE);
        given(lever.source()).willReturn(Source.LEVER);
    }

    @Test
    void ingestsDeduplicatesEmbedsAndMatches() throws Exception {
        List<NormalizedJob> greenhouseJobs = new ArrayList<>(List.of(
                job("gh-1", "Senior Backend Engineer - New York", "New York, NY", BACKEND, "2026-09-01T00:00:00Z"),
                // Same role reposted for another location with a trailing line added: a duplicate.
                job("gh-2", "Sr. Backend Engineer (Remote)", "Remote", BACKEND + "\nThis role is remote friendly.",
                        "2026-09-10T00:00:00Z"),
                // Same normalized title, unrelated description: a genuinely different opening.
                job("gh-3", "Senior Backend Engineer", "Austin, TX", BACKEND_OTHER_TEAM, "2026-09-05T00:00:00Z"),
                job("gh-4", "Product Designer", "New York, NY", DESIGN, "2026-09-02T00:00:00Z")));
        given(greenhouse.fetch(any())).willAnswer(inv -> List.copyOf(greenhouseJobs));
        given(lever.fetch(any())).willReturn(List.of(
                job("lv-1", "Data Engineer", "Denver, CO", DATA, "2026-09-03T00:00:00Z")));

        IngestionRun first = ingestion.runOnce();

        assertThat(first.companiesPolled()).isEqualTo(2);
        assertThat(first.postingsSeen()).isEqualTo(5);
        assertThat(first.postingsNew()).isEqualTo(5);
        assertThat(first.duplicatesFound()).isEqualTo(1);
        assertThat(first.postingsEmbedded()).isEqualTo(4);

        long gh1 = idOf("gh-1");
        long gh2 = idOf("gh-2");
        assertThat(postings.findById(gh2).orElseThrow().canonicalId()).isEqualTo(gh1);
        assertThat(postings.findById(idOf("gh-3")).orElseThrow().canonicalId()).isNull();
        assertThat(postings.findById(gh1).orElseThrow().normalizedTitle()).isEqualTo("senior backend engineer");

        // Resume matching ranks the backend role first and never returns the duplicate.
        mvc.perform(post("/api/match").contentType(MediaType.APPLICATION_JSON).content("""
                        {"resumeText": "Backend engineer: Java, Spring Boot, PostgreSQL, Kafka, payment APIs", "limit": 10}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postingsRanked").value(4))
                .andExpect(jsonPath("$.results.length()").value(4))
                .andExpect(jsonPath("$.results[0].id").value(gh1))
                .andExpect(jsonPath("$.results[?(@.id == " + gh2 + ")]").isEmpty());

        mvc.perform(get("/api/jobs/" + gh1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates[0].id").value(gh2));

        // Second poll: the designer role disappeared, nothing else changed.
        greenhouseJobs.removeIf(j -> j.externalId().equals("gh-4"));
        int embeddedBefore = ((FakeEmbeddingClient) embeddingClient).textsEmbedded;
        IngestionRun second = ingestion.runOnce();

        assertThat(second.postingsNew()).isZero();
        assertThat(second.postingsClosed()).isEqualTo(1);
        assertThat(second.postingsEmbedded()).isZero();
        assertThat(((FakeEmbeddingClient) embeddingClient).textsEmbedded).isEqualTo(embeddedBefore);
        assertThat(postings.findById(idOf("gh-4")).orElseThrow().closedAt()).isNotNull();

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openPostings").value(4))
                .andExpect(jsonPath("$.duplicatePostings").value(1))
                .andExpect(jsonPath("$.duplicateRate").value(0.25));
    }

    @Test
    void failedPollDoesNotCloseExistingPostings() {
        given(greenhouse.fetch(any())).willReturn(List.of(
                job("gh-1", "Backend Engineer", "New York, NY", BACKEND, "2026-09-01T00:00:00Z")));
        given(lever.fetch(any())).willReturn(List.of());
        ingestion.runOnce();

        given(greenhouse.fetch(any())).willThrow(new IllegalStateException("board unavailable"));
        IngestionRun run = ingestion.runOnce();

        assertThat(run.companiesFailed()).isEqualTo(1);
        assertThat(postings.findById(idOf("gh-1")).orElseThrow().closedAt()).isNull();
        String error = jdbc.sql("SELECT last_poll_error FROM companies WHERE id = :id")
                .param("id", stripeId).query(String.class).single();
        assertThat(error).contains("board unavailable");
    }

    @Test
    void companyBoilerplateNeitherHidesRepostsNorCreatesFalseMerges() {
        String benefits = """
                At Stripe we offer medical, dental, and vision coverage, a generous learning stipend, and flexible time off.
                We are an equal opportunity employer and value diversity at our company in every form.
                Our mission is to increase the GDP of the internet, and we are hiring people who share it.
                We sponsor visas, offer relocation support, and provide a home office budget for every employee.
                """;
        String dublinPay = "Base pay range for Dublin: EUR 90,000 - 120,000 plus equity and a quarterly bonus program.\n";
        String londonPay = "Base pay range for London: GBP 80,000 - 110,000 plus equity and an annual performance bonus.\n";
        given(lever.fetch(any())).willReturn(List.of());
        given(greenhouse.fetch(any())).willReturn(List.of(
                // Same role in two cities: only the regional pay line differs.
                job("a1", "Backend Engineer", "Dublin", BACKEND + dublinPay + benefits, "2026-09-01T00:00:00Z"),
                job("a2", "Backend Engineer", "London", BACKEND + londonPay + benefits, "2026-09-02T00:00:00Z"),
                // Same title, different team: shared boilerplate would push raw similarity over the threshold.
                job("a3", "Backend Engineer", "Dublin", BACKEND_OTHER_TEAM + dublinPay + benefits,
                        "2026-09-03T00:00:00Z"),
                // Other roles carrying the same boilerplate and pay lines, so they are recognized as boilerplate.
                job("b1", "Product Designer", "Dublin", DESIGN + dublinPay + benefits, "2026-09-01T00:00:00Z"),
                job("b2", "Data Engineer", "London", DATA + londonPay + benefits, "2026-09-01T00:00:00Z"),
                job("b3", "Recruiter", "Dublin", "Hire great people.\n" + dublinPay + benefits, "2026-09-01T00:00:00Z"),
                job("b4", "Accountant", "London", "Close the books.\n" + londonPay + benefits, "2026-09-01T00:00:00Z")));

        ingestion.runOnce();

        assertThat(postings.findById(idOf("a2")).orElseThrow().canonicalId()).isEqualTo(idOf("a1"));
        assertThat(postings.findById(idOf("a3")).orElseThrow().canonicalId()).isNull();
    }

    private long idOf(String externalId) {
        return jdbc.sql("SELECT id FROM job_postings WHERE external_id = :e").param("e", externalId)
                .query(Long.class).single();
    }

    private static NormalizedJob job(String id, String title, String location, String description, String posted) {
        return new NormalizedJob(id, title, location, "Engineering", "https://example.com/jobs/" + id, description,
                Instant.parse(posted));
    }
}
