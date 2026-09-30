package com.jobaggregator.job;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.jobaggregator.source.NormalizedJob;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobPostingRepository {

    private static final String SELECT = """
            SELECT p.*, c.name AS company_name
            FROM job_postings p JOIN companies c ON c.id = p.company_id
            """;

    private final JdbcClient jdbc;

    public JobPostingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts or refreshes a posting. Returns true when the posting was new.
     * A changed description invalidates the stored embedding via content_hash.
     */
    public boolean upsert(long companyId, NormalizedJob job, String normalizedTitle, String contentHash) {
        return jdbc.sql("""
                INSERT INTO job_postings (company_id, external_id, title, normalized_title, location, department,
                                          url, description, content_hash, posted_at)
                VALUES (:companyId, :externalId, :title, :normalizedTitle, :location, :department,
                        :url, :description, :contentHash, :postedAt)
                ON CONFLICT (company_id, external_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    normalized_title = EXCLUDED.normalized_title,
                    location = EXCLUDED.location,
                    department = EXCLUDED.department,
                    url = EXCLUDED.url,
                    description = EXCLUDED.description,
                    content_hash = EXCLUDED.content_hash,
                    posted_at = COALESCE(job_postings.posted_at, EXCLUDED.posted_at),
                    last_seen_at = now(),
                    closed_at = NULL
                RETURNING (xmax = 0) AS inserted
                """)
                .param("companyId", companyId)
                .param("externalId", job.externalId())
                .param("title", job.title())
                .param("normalizedTitle", normalizedTitle)
                .param("location", job.location())
                .param("department", job.department())
                .param("url", job.url())
                .param("description", job.description())
                .param("contentHash", contentHash)
                .param("postedAt", job.postedAt() == null ? null : Timestamp.from(job.postedAt()))
                .query(Boolean.class)
                .single();
    }

    /** Closes postings for a company that were not present in the latest successful poll. */
    public int closeMissing(long companyId, List<String> seenExternalIds) {
        return jdbc.sql("""
                UPDATE job_postings SET closed_at = now(), canonical_id = NULL
                WHERE company_id = :companyId AND closed_at IS NULL
                  AND NOT (external_id = ANY (:seen))
                """)
                .param("companyId", companyId)
                .param("seen", seenExternalIds.toArray(String[]::new))
                .update();
    }

    public Optional<JobPosting> findById(long id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(JobPostingRepository::map).optional();
    }

    public List<JobPosting> findDuplicatesOf(long canonicalId) {
        return jdbc.sql(SELECT + " WHERE p.canonical_id = :id ORDER BY p.id")
                .param("id", canonicalId)
                .query(JobPostingRepository::map)
                .list();
    }

    public List<JobPosting> search(String query, Long companyId, String location, boolean includeDuplicates,
                                   int limit, int offset) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE p.closed_at IS NULL");
        if (!includeDuplicates) {
            sql.append(" AND p.canonical_id IS NULL");
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (p.title ILIKE :q OR p.normalized_title ILIKE :q)");
        }
        if (companyId != null) {
            sql.append(" AND p.company_id = :companyId");
        }
        if (location != null && !location.isBlank()) {
            sql.append(" AND p.location ILIKE :location");
        }
        sql.append(" ORDER BY p.posted_at DESC NULLS LAST, p.id LIMIT :limit OFFSET :offset");
        var spec = jdbc.sql(sql.toString()).param("limit", limit).param("offset", offset);
        if (query != null && !query.isBlank()) {
            spec = spec.param("q", "%" + query.strip() + "%");
        }
        if (companyId != null) {
            spec = spec.param("companyId", companyId);
        }
        if (location != null && !location.isBlank()) {
            spec = spec.param("location", "%" + location.strip() + "%");
        }
        return spec.query(JobPostingRepository::map).list();
    }

    // --- dedup support ---

    public record DedupCandidate(long id, long companyId, String normalizedTitle, String description,
                                 Instant postedAt) {
    }

    /** Open postings whose (company, normalized title) group has more than one member. */
    public List<DedupCandidate> findDedupCandidates() {
        return jdbc.sql("""
                SELECT p.id, p.company_id, p.normalized_title, p.description, p.posted_at
                FROM job_postings p
                JOIN (SELECT company_id, normalized_title FROM job_postings
                      WHERE closed_at IS NULL
                      GROUP BY company_id, normalized_title HAVING count(*) > 1) g
                  ON g.company_id = p.company_id AND g.normalized_title = p.normalized_title
                WHERE p.closed_at IS NULL
                ORDER BY p.company_id, p.normalized_title, p.posted_at NULLS LAST, p.id
                """)
                .query((rs, i) -> new DedupCandidate(
                        rs.getLong("id"), rs.getLong("company_id"), rs.getString("normalized_title"),
                        rs.getString("description"), toInstant(rs.getTimestamp("posted_at"))))
                .list();
    }

    /**
     * MD5s of description lines that a company repeats across at least {@code minTitles} different roles:
     * about-us, benefits, EEO, and region-specific pay text. Two postings of the same role can differ
     * only in these lines, so dedup ignores them.
     */
    public Map<Long, Set<String>> findBoilerplateLineHashes(int minTitles) {
        Map<Long, Set<String>> out = new HashMap<>();
        jdbc.sql("""
                SELECT p.company_id, md5(btrim(line)) AS line_hash
                FROM job_postings p, unnest(string_to_array(p.description, E'\\n')) AS line
                WHERE p.closed_at IS NULL AND btrim(line) <> ''
                GROUP BY p.company_id, md5(btrim(line))
                HAVING count(DISTINCT p.normalized_title) >= :minTitles
                """)
                .param("minTitles", minTitles)
                .query(rs -> {
                    out.computeIfAbsent(rs.getLong("company_id"), k -> new HashSet<>()).add(rs.getString("line_hash"));
                });
        return out;
    }

    /** Replaces all canonical links in one transaction. Keys are duplicate ids, values their canonical. */
    @Transactional
    public void replaceCanonicalLinks(Map<Long, Long> duplicateToCanonical) {
        jdbc.sql("UPDATE job_postings SET canonical_id = NULL WHERE canonical_id IS NOT NULL").update();
        if (duplicateToCanonical.isEmpty()) {
            return;
        }
        Long[] dupIds = duplicateToCanonical.keySet().toArray(Long[]::new);
        Long[] canonIds = new Long[dupIds.length];
        for (int i = 0; i < dupIds.length; i++) {
            canonIds[i] = duplicateToCanonical.get(dupIds[i]);
        }
        jdbc.sql("""
                UPDATE job_postings p SET canonical_id = m.canonical_id
                FROM unnest(:dupIds::bigint[], :canonIds::bigint[]) AS m(id, canonical_id)
                WHERE p.id = m.id
                """)
                .param("dupIds", dupIds)
                .param("canonIds", canonIds)
                .update();
    }

    // --- embedding support ---

    public record EmbeddingWork(long id, String text, String contentHash) {
    }

    /** Open canonical postings with no embedding, or whose content changed since it was computed. */
    public List<EmbeddingWork> findNeedingEmbedding(int limit) {
        return jdbc.sql("""
                SELECT p.id, p.content_hash, c.name AS company_name, p.title, p.location, p.department, p.description
                FROM job_postings p JOIN companies c ON c.id = p.company_id
                WHERE p.closed_at IS NULL AND p.canonical_id IS NULL
                  AND (p.embedding IS NULL OR p.embedding_hash IS DISTINCT FROM p.content_hash)
                ORDER BY p.id
                LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, i) -> new EmbeddingWork(rs.getLong("id"), embeddingText(rs), rs.getString("content_hash")))
                .list();
    }

    public void saveEmbedding(long id, float[] vector, String contentHash) {
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            boxed[i] = vector[i];
        }
        jdbc.sql("UPDATE job_postings SET embedding = :vec::real[], embedding_hash = :hash WHERE id = :id")
                .param("vec", boxed)
                .param("hash", contentHash)
                .param("id", id)
                .update();
    }

    public record EmbeddedPosting(long id, float[] embedding) {
    }

    public List<EmbeddedPosting> findEmbeddedCanonical() {
        return jdbc.sql("""
                SELECT id, embedding FROM job_postings
                WHERE closed_at IS NULL AND canonical_id IS NULL AND embedding IS NOT NULL
                """)
                .query((rs, i) -> new EmbeddedPosting(rs.getLong("id"), toFloats(rs.getArray("embedding"))))
                .list();
    }

    public List<JobPosting> findAllById(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE p.id = ANY (:ids)")
                .param("ids", ids.toArray(Long[]::new))
                .query(JobPostingRepository::map)
                .list();
    }

    // --- stats ---

    public record Stats(long openPostings, long canonicalPostings, long duplicatePostings, long embeddedPostings,
                        long closedPostings) {
    }

    public Stats stats() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE closed_at IS NULL)                                   AS open,
                       count(*) FILTER (WHERE closed_at IS NULL AND canonical_id IS NULL)          AS canonical,
                       count(*) FILTER (WHERE closed_at IS NULL AND canonical_id IS NOT NULL)      AS duplicates,
                       count(*) FILTER (WHERE closed_at IS NULL AND canonical_id IS NULL
                                        AND embedding IS NOT NULL)                                 AS embedded,
                       count(*) FILTER (WHERE closed_at IS NOT NULL)                               AS closed
                FROM job_postings
                """)
                .query((rs, i) -> new Stats(rs.getLong("open"), rs.getLong("canonical"), rs.getLong("duplicates"),
                        rs.getLong("embedded"), rs.getLong("closed")))
                .single();
    }

    static String embeddingText(ResultSet rs) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append(rs.getString("title")).append(" at ").append(rs.getString("company_name"));
        if (rs.getString("location") != null) {
            sb.append("\nLocation: ").append(rs.getString("location"));
        }
        if (rs.getString("department") != null) {
            sb.append("\nTeam: ").append(rs.getString("department"));
        }
        sb.append("\n\n").append(rs.getString("description"));
        return sb.toString();
    }

    private static float[] toFloats(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        float[] out = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = ((Number) values[i]).floatValue();
        }
        return out;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static JobPosting map(ResultSet rs, int row) throws SQLException {
        long canonical = rs.getLong("canonical_id");
        Long canonicalId = rs.wasNull() ? null : canonical;
        return new JobPosting(
                rs.getLong("id"),
                rs.getLong("company_id"),
                rs.getString("company_name"),
                rs.getString("external_id"),
                rs.getString("title"),
                rs.getString("normalized_title"),
                rs.getString("location"),
                rs.getString("department"),
                rs.getString("url"),
                rs.getString("description"),
                toInstant(rs.getTimestamp("posted_at")),
                toInstant(rs.getTimestamp("first_seen_at")),
                toInstant(rs.getTimestamp("last_seen_at")),
                toInstant(rs.getTimestamp("closed_at")),
                canonicalId);
    }
}
