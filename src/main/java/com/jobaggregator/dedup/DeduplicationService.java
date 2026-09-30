package com.jobaggregator.dedup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.jobaggregator.config.AppProperties;
import com.jobaggregator.job.JobPostingRepository;
import com.jobaggregator.job.JobPostingRepository.DedupCandidate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Collapses reposts: within a (company, normalized title) group, a posting whose description is
 * at least {@code similarityThreshold} similar to an earlier posting becomes its duplicate.
 * Company boilerplate (benefits, EEO, regional pay text) is removed before comparing, so the
 * score reflects the role itself. The earliest-posted member of each cluster stays canonical.
 */
@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    /** Below this much role-specific text, compare full descriptions instead of near-empty remainders. */
    private static final int MIN_ROLE_TEXT_CHARS = 300;

    private final JobPostingRepository postings;
    private final AppProperties.Dedup config;

    public DeduplicationService(JobPostingRepository postings, AppProperties props) {
        this.postings = postings;
        this.config = props.dedup();
    }

    /** Recomputes all duplicate links from scratch and returns how many postings are duplicates. */
    public int deduplicate() {
        List<DedupCandidate> candidates = postings.findDedupCandidates();
        Map<Long, Set<String>> boilerplate = postings.findBoilerplateLineHashes(config.boilerplateMinTitles());
        Map<Long, Long> links = new HashMap<>();

        List<DedupCandidate> group = new ArrayList<>();
        for (DedupCandidate c : candidates) {
            if (!group.isEmpty() && !sameGroup(group.getFirst(), c)) {
                links.putAll(clusterGroup(group, boilerplate.getOrDefault(group.getFirst().companyId(), Set.of())));
                group.clear();
            }
            group.add(c);
        }
        if (!group.isEmpty()) {
            links.putAll(clusterGroup(group, boilerplate.getOrDefault(group.getFirst().companyId(), Set.of())));
        }

        postings.replaceCanonicalLinks(links);
        log.info("Dedup: {} candidates in shared-title groups, {} marked duplicate", candidates.size(), links.size());
        return links.size();
    }

    /** Candidates arrive oldest-first, so each posting is compared against earlier canonicals only. */
    Map<Long, Long> clusterGroup(List<DedupCandidate> group, Set<String> boilerplateHashes) {
        Map<Long, Long> links = new HashMap<>();
        List<Long> canonicalIds = new ArrayList<>();
        List<Set<Long>> canonicalShingles = new ArrayList<>();

        for (DedupCandidate posting : group) {
            String roleText = stripBoilerplate(posting.description(), boilerplateHashes);
            if (roleText.length() < MIN_ROLE_TEXT_CHARS) {
                roleText = posting.description();
            }
            Set<Long> shingles = DescriptionSimilarity.shingles(roleText, config.shingleSize());
            Long match = null;
            for (int i = 0; i < canonicalIds.size(); i++) {
                if (DescriptionSimilarity.jaccard(shingles, canonicalShingles.get(i)) >= config.similarityThreshold()) {
                    match = canonicalIds.get(i);
                    break;
                }
            }
            if (match != null) {
                links.put(posting.id(), match);
            } else {
                canonicalIds.add(posting.id());
                canonicalShingles.add(shingles);
            }
        }
        return links;
    }

    static String stripBoilerplate(String description, Set<String> boilerplateHashes) {
        if (boilerplateHashes.isEmpty()) {
            return description;
        }
        StringBuilder kept = new StringBuilder();
        for (String line : description.split("\n")) {
            // Match Postgres btrim(), which trims only spaces, so hashes line up with the SQL side.
            String trimmed = line.replaceAll("^ +| +$", "");
            if (!trimmed.isEmpty() && !boilerplateHashes.contains(md5(trimmed))) {
                kept.append(trimmed).append('\n');
            }
        }
        return kept.toString();
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean sameGroup(DedupCandidate a, DedupCandidate b) {
        return a.companyId() == b.companyId() && Objects.equals(a.normalizedTitle(), b.normalizedTitle());
    }
}
