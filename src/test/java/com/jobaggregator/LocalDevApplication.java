package com.jobaggregator;

import java.nio.file.Files;
import java.nio.file.Path;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the app against an embedded PostgreSQL whose data persists in {@code target/dev-postgres},
 * for local development without Docker: {@code ./mvnw spring-boot:test-run}
 */
public class LocalDevApplication {

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of("target", "dev-postgres").toAbsolutePath();
        Files.createDirectories(dataDir);
        EmbeddedPostgres pg = EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .setPort(54329)
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));
        System.setProperty("spring.datasource.url", pg.getJdbcUrl("postgres", "postgres"));
        System.setProperty("spring.datasource.username", "postgres");
        System.setProperty("spring.datasource.password", "postgres");
        JobAggregatorApplication.main(args);
    }
}
