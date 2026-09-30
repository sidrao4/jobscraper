package com.jobaggregator.source;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jobaggregator.company.Company;
import com.jobaggregator.company.Source;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Polls the public Greenhouse Job Board API: https://developers.greenhouse.io/job-board.html */
@Component
public class GreenhouseClient implements JobBoardClient {

    static final String BASE_URL = "https://boards-api.greenhouse.io/v1/boards";

    private final RestClient http;

    public GreenhouseClient(RestClient http) {
        this.http = http;
    }

    @Override
    public Source source() {
        return Source.GREENHOUSE;
    }

    @Override
    public List<NormalizedJob> fetch(Company company) {
        Response body = http.get()
                .uri(BASE_URL + "/{token}/jobs?content=true", company.boardToken())
                .retrieve()
                .body(Response.class);
        if (body == null || body.jobs() == null) {
            return List.of();
        }
        return body.jobs().stream().map(GreenhouseClient::toNormalized).toList();
    }

    static NormalizedJob toNormalized(Job job) {
        String department = job.departments() == null || job.departments().isEmpty()
                ? null
                : job.departments().getFirst().name();
        String posted = job.firstPublished() != null ? job.firstPublished() : job.updatedAt();
        return new NormalizedJob(
                String.valueOf(job.id()),
                job.title().strip(),
                job.location() == null ? null : job.location().name(),
                department,
                job.absoluteUrl(),
                HtmlText.unescapeThenPlainText(job.content()),
                posted == null ? null : OffsetDateTime.parse(posted).toInstant());
    }

    record Response(List<Job> jobs) {
    }

    record Job(
            long id,
            String title,
            @JsonProperty("absolute_url") String absoluteUrl,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("first_published") String firstPublished,
            Location location,
            List<Department> departments,
            String content) {
    }

    record Location(String name) {
    }

    record Department(String name) {
    }
}
