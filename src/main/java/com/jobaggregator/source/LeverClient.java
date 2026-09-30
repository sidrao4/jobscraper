package com.jobaggregator.source;

import java.time.Instant;
import java.util.List;

import com.jobaggregator.company.Company;
import com.jobaggregator.company.Source;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Polls the public Lever Postings API: https://github.com/lever/postings-api */
@Component
public class LeverClient implements JobBoardClient {

    static final String BASE_URL = "https://api.lever.co/v0/postings";

    private final RestClient http;

    public LeverClient(RestClient http) {
        this.http = http;
    }

    @Override
    public Source source() {
        return Source.LEVER;
    }

    @Override
    public List<NormalizedJob> fetch(Company company) {
        List<Posting> postings = http.get()
                .uri(BASE_URL + "/{slug}?mode=json", company.boardToken())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Posting>>() {
                });
        if (postings == null) {
            return List.of();
        }
        return postings.stream().map(LeverClient::toNormalized).toList();
    }

    static NormalizedJob toNormalized(Posting p) {
        // Lever splits the description into an intro, titled bullet lists, and a closing section.
        StringBuilder description = new StringBuilder(nullToEmpty(p.descriptionPlain()));
        if (p.lists() != null) {
            for (ListSection section : p.lists()) {
                description.append("\n\n").append(nullToEmpty(section.text()))
                        .append('\n').append(HtmlText.toPlainText(section.content()));
            }
        }
        if (p.additionalPlain() != null) {
            description.append("\n\n").append(p.additionalPlain());
        }
        Categories c = p.categories();
        return new NormalizedJob(
                p.id(),
                p.text().strip(),
                c == null ? null : c.location(),
                c == null ? null : (c.team() != null ? c.team() : c.department()),
                p.hostedUrl(),
                HtmlText.normalizeWhitespace(description.toString()),
                p.createdAt() == null ? null : Instant.ofEpochMilli(p.createdAt()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    record Posting(
            String id,
            String text,
            String hostedUrl,
            Long createdAt,
            Categories categories,
            String descriptionPlain,
            List<ListSection> lists,
            String additionalPlain) {
    }

    record Categories(String location, String team, String department, String commitment) {
    }

    record ListSection(String text, String content) {
    }
}
