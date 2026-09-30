package com.jobaggregator.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import com.jobaggregator.company.Company;
import com.jobaggregator.company.Source;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JobBoardClientTest {

    @Test
    void parsesGreenhouseBoard() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/greenhouse.json"), MediaType.APPLICATION_JSON));

        List<NormalizedJob> jobs = new GreenhouseClient(builder.build())
                .fetch(new Company(1, "Acme", Source.GREENHOUSE, "acme", true, null, null));

        assertThat(jobs).hasSize(1);
        NormalizedJob job = jobs.getFirst();
        assertThat(job.externalId()).isEqualTo("5426468004");
        assertThat(job.title()).isEqualTo("Senior Backend Engineer");
        assertThat(job.location()).isEqualTo("San Francisco, CA");
        assertThat(job.department()).isEqualTo("Engineering");
        assertThat(job.postedAt()).isEqualTo(Instant.parse("2025-01-28T23:57:29Z"));
        // Entity-escaped HTML is decoded and stripped, keeping line structure.
        assertThat(job.description()).isEqualTo("About the role\nBuild APIs & services.\nJava\nPostgres");
        server.verify();
    }

    @Test
    void parsesLeverBoard() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.lever.co/v0/postings/acme?mode=json"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/lever.json"), MediaType.APPLICATION_JSON));

        List<NormalizedJob> jobs = new LeverClient(builder.build())
                .fetch(new Company(2, "Acme", Source.LEVER, "acme", true, null, null));

        assertThat(jobs).hasSize(1);
        NormalizedJob job = jobs.getFirst();
        assertThat(job.externalId()).isEqualTo("5becd4e1-3474-4f36-b5dd-4b2cd0eb1179");
        assertThat(job.title()).isEqualTo("Data Engineer");
        assertThat(job.location()).isEqualTo("Remote - US");
        assertThat(job.department()).isEqualTo("Data Platform");
        assertThat(job.url()).isEqualTo("https://jobs.lever.co/acme/5becd4e1-3474-4f36-b5dd-4b2cd0eb1179");
        assertThat(job.postedAt()).isEqualTo(Instant.ofEpochMilli(1771264785944L));
        assertThat(job.description()).isEqualTo(
                "We move data.\n\nWhat you'll do\nBuild pipelines\nOwn Airflow\n\nBenefits galore.");
        server.verify();
    }
}
