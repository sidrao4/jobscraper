package com.jobaggregator.company;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CompanyRepository {

    private final JdbcClient jdbc;

    public CompanyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Company> findAll() {
        return jdbc.sql("SELECT * FROM companies ORDER BY name").query(CompanyRepository::map).list();
    }

    public List<Company> findEnabled() {
        return jdbc.sql("SELECT * FROM companies WHERE enabled ORDER BY name").query(CompanyRepository::map).list();
    }

    public Company create(String name, Source source, String boardToken) {
        return jdbc.sql("""
                INSERT INTO companies (name, source, board_token) VALUES (:name, :source, :token)
                ON CONFLICT (source, board_token) DO UPDATE SET name = EXCLUDED.name, enabled = TRUE
                RETURNING *
                """)
                .param("name", name)
                .param("source", source.name())
                .param("token", boardToken)
                .query(CompanyRepository::map)
                .single();
    }

    public void recordPoll(long companyId, String error) {
        jdbc.sql("UPDATE companies SET last_polled_at = now(), last_poll_error = :error WHERE id = :id")
                .param("error", error)
                .param("id", companyId)
                .update();
    }

    private static Company map(ResultSet rs, int row) throws SQLException {
        Timestamp polled = rs.getTimestamp("last_polled_at");
        return new Company(
                rs.getLong("id"),
                rs.getString("name"),
                Source.valueOf(rs.getString("source")),
                rs.getString("board_token"),
                rs.getBoolean("enabled"),
                polled == null ? null : polled.toInstant(),
                rs.getString("last_poll_error"));
    }
}
