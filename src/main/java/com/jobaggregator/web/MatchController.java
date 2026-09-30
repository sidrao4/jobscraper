package com.jobaggregator.web;

import java.io.IOException;

import com.jobaggregator.match.ResumeMatchService;
import com.jobaggregator.match.ResumeMatchService.MatchResponse;
import com.jobaggregator.match.ResumeTextExtractor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/match")
public class MatchController {

    private final ResumeMatchService matcher;
    private final ResumeTextExtractor extractor;

    public MatchController(ResumeMatchService matcher, ResumeTextExtractor extractor) {
        this.matcher = matcher;
        this.extractor = extractor;
    }

    public record MatchRequest(@NotBlank String resumeText, @Min(1) @Max(500) Integer limit, String location) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public MatchResponse matchText(@Valid @RequestBody MatchRequest request) {
        return matcher.match(request.resumeText(), request.limit() == null ? 25 : request.limit(),
                request.location());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MatchResponse matchFile(@RequestPart("resume") MultipartFile resume,
                                   @RequestParam(defaultValue = "25") int limit,
                                   @RequestParam(required = false) String location) throws IOException {
        String text = extractor.extract(resume);
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not extract any text from the resume");
        }
        return matcher.match(text, Math.clamp(limit, 1, 500), location);
    }
}
