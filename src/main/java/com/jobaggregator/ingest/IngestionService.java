package com.jobaggregator.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

import com.jobaggregator.company.Company;
import com.jobaggregator.company.CompanyRepository;
import com.jobaggregator.company.Source;
import com.jobaggregator.config.AppProperties;
import com.jobaggregator.dedup.DeduplicationService;
import com.jobaggregator.embedding.EmbeddingService;
import com.jobaggregator.job.JobPostingRepository;
import com.jobaggregator.normalize.TitleNormalizer;
import com.jobaggregator.source.JobBoardClient;
import com.jobaggregator.source.NormalizedJob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One ingestion run: poll every enabled board concurrently, upsert what's open, close what
 * disappeared, then recompute duplicates and embed new canonical postings.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final CompanyRepository companies;
    private final JobPostingRepository postings;
    private final IngestionRunRepository runs;
    private final TitleNormalizer titleNormalizer;
    private final DeduplicationService dedup;
    private final EmbeddingService embeddings;
    private final List<JobBoardClient> clients;
    private final int concurrency;
    private final ReentrantLock running = new ReentrantLock();

    public IngestionService(CompanyRepository companies, JobPostingRepository postings, IngestionRunRepository runs,
                            TitleNormalizer titleNormalizer, DeduplicationService dedup, EmbeddingService embeddings,
                            List<JobBoardClient> boardClients, AppProperties props) {
        this.companies = companies;
        this.postings = postings;
        this.runs = runs;
        this.titleNormalizer = titleNormalizer;
        this.dedup = dedup;
        this.embeddings = embeddings;
        this.clients = boardClients;
        this.concurrency = props.ingest().concurrency();
    }

    public boolean isRunning() {
        return running.isLocked();
    }

    /** Returns the finished run, or null if another run was already in progress. */
    public IngestionRun runOnce() {
        if (!running.tryLock()) {
            log.info("Ingestion already running; skipping");
            return null;
        }
        try {
            return doRun();
        } finally {
            running.unlock();
        }
    }

    private IngestionRun doRun() {
        long runId = runs.start();
        List<Company> enabled = companies.findEnabled();
        log.info("Ingestion run {} polling {} boards", runId, enabled.size());

        List<PollResult> results = pollAll(enabled);
        int failed = 0;
        int seen = 0;
        int created = 0;
        int closed = 0;
        for (PollResult r : results) {
            if (r.error() != null) {
                failed++;
                continue;
            }
            seen += r.seen();
            created += r.created();
            closed += r.closed();
        }

        int duplicates = dedup.deduplicate();
        int embedded = 0;
        try {
            embedded = embeddings.embedPending();
        } catch (RuntimeException e) {
            log.error("Embedding step failed; postings will be retried next run", e);
        }

        runs.finish(runId, enabled.size() - failed, failed, seen, created, closed, duplicates, embedded);
        log.info("Ingestion run {} done: {} boards ({} failed), {} postings seen, {} new, {} closed, {} duplicates, {} embedded",
                runId, enabled.size(), failed, seen, created, closed, duplicates, embedded);
        return runs.recent(1).getFirst();
    }

    private List<PollResult> pollAll(List<Company> targets) {
        // Virtual threads for the I/O, with a semaphore so we stay polite to the board APIs.
        Semaphore permits = new Semaphore(concurrency);
        Map<Company, Future<PollResult>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Company company : targets) {
                futures.put(company, executor.submit(() -> {
                    permits.acquire();
                    try {
                        return pollCompany(company);
                    } finally {
                        permits.release();
                    }
                }));
            }
        }
        List<PollResult> results = new ArrayList<>();
        futures.forEach((company, future) -> {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(PollResult.failed(e.toString()));
            } catch (ExecutionException e) {
                results.add(PollResult.failed(e.getCause().toString()));
            }
        });
        return results;
    }

    PollResult pollCompany(Company company) {
        List<NormalizedJob> jobs;
        try {
            jobs = clientFor(company.source()).fetch(company);
        } catch (RuntimeException e) {
            // A failed poll must not close that company's postings.
            log.warn("Polling {} ({} {}) failed: {}", company.name(), company.source(), company.boardToken(),
                    e.getMessage());
            companies.recordPoll(company.id(), truncate(e.getMessage()));
            return PollResult.failed(e.getMessage());
        }

        int created = 0;
        List<String> seenIds = new ArrayList<>(jobs.size());
        for (NormalizedJob job : jobs) {
            String normalizedTitle = titleNormalizer.normalize(job.title(), job.location());
            if (postings.upsert(company.id(), job, normalizedTitle, contentHash(job))) {
                created++;
            }
            seenIds.add(job.externalId());
        }
        int closed = postings.closeMissing(company.id(), seenIds);
        companies.recordPoll(company.id(), null);
        log.debug("{}: {} open postings, {} new, {} closed", company.name(), jobs.size(), created, closed);
        return new PollResult(jobs.size(), created, closed, null);
    }

    private JobBoardClient clientFor(Source source) {
        return clients.stream()
                .filter(c -> c.source() == source)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No client for " + source));
    }

    static String contentHash(NormalizedJob job) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {job.title(), job.location(), job.department(), job.description()}) {
                sha.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "unknown error";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    record PollResult(int seen, int created, int closed, String error) {
        static PollResult failed(String error) {
            return new PollResult(0, 0, 0, error == null ? "unknown error" : error);
        }
    }
}
