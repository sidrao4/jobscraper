package com.jobaggregator.match;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jobaggregator.embedding.EmbeddingClient;
import com.jobaggregator.embedding.EmbeddingClient.TaskType;
import com.jobaggregator.embedding.VectorMath;
import com.jobaggregator.job.JobPosting;
import com.jobaggregator.job.JobPostingRepository;
import com.jobaggregator.job.JobPostingRepository.EmbeddedPosting;

import org.springframework.stereotype.Service;

/** Ranks every embedded canonical posting by cosine similarity to the resume's embedding. */
@Service
public class ResumeMatchService {

    private final JobPostingRepository postings;
    private final EmbeddingClient embeddings;

    public ResumeMatchService(JobPostingRepository postings, EmbeddingClient embeddings) {
        this.postings = postings;
        this.embeddings = embeddings;
    }

    public record MatchResponse(int postingsRanked, List<MatchResult> results) {
    }

    public MatchResponse match(String resumeText, int limit, String locationFilter) {
        if (!embeddings.isEnabled()) {
            throw new IllegalStateException("Matching needs a Gemini API key (set GEMINI_API_KEY)");
        }
        float[] query = embeddings.embed(List.of(resumeText), TaskType.RETRIEVAL_QUERY).getFirst();
        List<EmbeddedPosting> corpus = postings.findEmbeddedCanonical();

        // Rank everything, then only hydrate what survives filtering. Location filtering needs the
        // posting row, so over-fetch candidates when a filter is set.
        int keep = locationFilter == null || locationFilter.isBlank() ? limit : Math.max(limit * 20, 500);
        PriorityQueue<Scored> top = new PriorityQueue<>(Comparator.comparingDouble(Scored::score));
        for (EmbeddedPosting p : corpus) {
            if (p.embedding().length != query.length) {
                continue;
            }
            top.add(new Scored(p.id(), VectorMath.cosine(query, p.embedding())));
            if (top.size() > keep) {
                top.poll();
            }
        }
        List<Scored> ranked = new ArrayList<>(top);
        ranked.sort(Comparator.comparingDouble(Scored::score).reversed());

        Map<Long, JobPosting> rows = postings.findAllById(ranked.stream().map(Scored::id).toList()).stream()
                .collect(Collectors.toMap(JobPosting::id, Function.identity()));
        String needle = locationFilter == null ? null : locationFilter.strip().toLowerCase();
        List<MatchResult> results = new ArrayList<>();
        for (Scored s : ranked) {
            JobPosting p = rows.get(s.id());
            if (p == null) {
                continue;
            }
            if (needle != null && !needle.isEmpty()
                    && (p.location() == null || !p.location().toLowerCase().contains(needle))) {
                continue;
            }
            results.add(new MatchResult(p.id(), Math.round(s.score() * 10000) / 10000.0, p.title(), p.companyName(),
                    p.location(), p.department(), p.url(), p.postedAt()));
            if (results.size() == limit) {
                break;
            }
        }
        return new MatchResponse(corpus.size(), results);
    }

    private record Scored(long id, double score) {
    }
}
