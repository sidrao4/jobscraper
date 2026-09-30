package com.jobaggregator.web;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.jobaggregator.company.Company;
import com.jobaggregator.company.CompanyRepository;
import com.jobaggregator.company.Source;
import com.jobaggregator.ingest.IngestionRun;
import com.jobaggregator.ingest.IngestionRunRepository;
import com.jobaggregator.ingest.IngestionService;
import com.jobaggregator.job.JobPostingRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AdminController {

    private final IngestionService ingestion;
    private final IngestionRunRepository runs;
    private final JobPostingRepository postings;
    private final CompanyRepository companies;

    public AdminController(IngestionService ingestion, IngestionRunRepository runs, JobPostingRepository postings,
                           CompanyRepository companies) {
        this.ingestion = ingestion;
        this.runs = runs;
        this.postings = postings;
        this.companies = companies;
    }

    /** Kicks off an ingestion run in the background. */
    @PostMapping("/ingest")
    public ResponseEntity<String> ingest() {
        if (ingestion.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("An ingestion run is already in progress");
        }
        CompletableFuture.runAsync(ingestion::runOnce, Thread.ofVirtual()::start);
        return ResponseEntity.accepted().body("Ingestion started; poll GET /api/stats for progress");
    }

    public record StatsResponse(boolean ingestionRunning, int companies, long openPostings, long canonicalPostings,
                                long duplicatePostings, double duplicateRate, long embeddedPostings,
                                long closedPostings, List<IngestionRun> recentRuns) {
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        JobPostingRepository.Stats s = postings.stats();
        double rate = s.openPostings() == 0 ? 0 : (double) s.duplicatePostings() / s.openPostings();
        return new StatsResponse(ingestion.isRunning(), companies.findAll().size(), s.openPostings(),
                s.canonicalPostings(), s.duplicatePostings(), Math.round(rate * 1000) / 1000.0,
                s.embeddedPostings(), s.closedPostings(), runs.recent(5));
    }

    public record NewCompany(@NotBlank String name, @NotNull Source source, @NotBlank String boardToken) {
    }

    @PostMapping("/companies")
    public Company addCompany(@Valid @RequestBody NewCompany company) {
        return companies.create(company.name(), company.source(), company.boardToken().strip());
    }
}
