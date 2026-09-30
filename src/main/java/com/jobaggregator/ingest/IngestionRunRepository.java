package com.jobaggregator.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRunRepository {

    private final JdbcClient jdbc;

    public IngestionRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long start() {
        return jdbc.sql("INSERT INTO ingestion_runs DEFAULT VALUES RETURNING id").query(Long.class).single();
    }

    public void finish(long id, int polled, int failed, int seen, int created, int closed, int duplicates,
                       int embedded) {
        jdbc.sql("""
                UPDATE ingestion_runs SET finished_at = now(), companies_polled = :polled,
                    companies_failed = :failed, postings_seen = :seen, postings_new = :created,
                    postings_closed = :closed, duplicates_found = :duplicates, postings_embedded = :embedded
                WHERE id = :id
                """)
                .param("id", id)
                .param("polled", polled)
                .param("failed", failed)
                .param("seen", seen)
                .param("created", created)
                .param("closed", closed)
                .param("duplicates", duplicates)
                .param("embedded", embedded)
                .update();
    }

    public List<IngestionRun> recent(int limit) {
        return jdbc.sql("SELECT * FROM ingestion_runs ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query(IngestionRunRepository::map)
                .list();
    }

    private static IngestionRun map(ResultSet rs, int row) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new IngestionRun(
                rs.getLong("id"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getInt("companies_polled"),
                rs.getInt("companies_failed"),
                rs.getInt("postings_seen"),
                rs.getInt("postings_new"),
                rs.getInt("postings_closed"),
                rs.getInt("duplicates_found"),
                rs.getInt("postings_embedded"));
    }
}
