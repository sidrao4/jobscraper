package com.jobaggregator.web;

import java.time.Instant;
import java.util.List;

import com.jobaggregator.company.Company;
import com.jobaggregator.company.CompanyRepository;
import com.jobaggregator.job.JobPosting;
import com.jobaggregator.job.JobPostingRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class JobController {

    private final JobPostingRepository postings;
    private final CompanyRepository companies;

    public JobController(JobPostingRepository postings, CompanyRepository companies) {
        this.postings = postings;
        this.companies = companies;
    }

    @GetMapping("/jobs")
    public List<JobSummary> search(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) Long companyId,
                                   @RequestParam(required = false) String location,
                                   @RequestParam(defaultValue = "false") boolean includeDuplicates,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset) {
        return postings.search(q, companyId, location, includeDuplicates, Math.clamp(limit, 1, 500),
                        Math.max(offset, 0))
                .stream().map(JobSummary::of).toList();
    }

    public record JobDetail(JobPosting posting, List<JobSummary> duplicates) {
    }

    @GetMapping("/jobs/{id}")
    public JobDetail get(@PathVariable long id) {
        JobPosting posting = postings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No posting " + id));
        long canonical = posting.canonicalId() != null ? posting.canonicalId() : posting.id();
        return new JobDetail(posting, postings.findDuplicatesOf(canonical).stream().map(JobSummary::of).toList());
    }

    @GetMapping("/companies")
    public List<Company> companies() {
        return companies.findAll();
    }

    public record JobSummary(long id, String title, String normalizedTitle, String company, String location,
                             String department, String url, Instant postedAt, Long canonicalId) {
        static JobSummary of(JobPosting p) {
            return new JobSummary(p.id(), p.title(), p.normalizedTitle(), p.companyName(), p.location(),
                    p.department(), p.url(), p.postedAt(), p.canonicalId());
        }
    }
}
