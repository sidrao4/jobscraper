package com.jobaggregator.embedding;

import java.util.List;

import com.jobaggregator.config.AppProperties;
import com.jobaggregator.embedding.EmbeddingClient.TaskType;
import com.jobaggregator.job.JobPostingRepository;
import com.jobaggregator.job.JobPostingRepository.EmbeddingWork;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Embeds canonical postings that are new or whose content changed. Duplicates are never embedded. */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final JobPostingRepository postings;
    private final EmbeddingClient client;
    private final AppProperties.Gemini config;

    public EmbeddingService(JobPostingRepository postings, EmbeddingClient client, AppProperties props) {
        this.postings = postings;
        this.client = client;
        this.config = props.gemini();
    }

    public int embedPending() {
        if (!client.isEnabled()) {
            log.info("Embeddings disabled: no Gemini API key configured");
            return 0;
        }
        int budget = config.maxPerRun() > 0 ? config.maxPerRun() : Integer.MAX_VALUE;
        int done = 0;
        while (done < budget) {
            List<EmbeddingWork> batch = postings.findNeedingEmbedding(Math.min(config.batchSize(), budget - done));
            if (batch.isEmpty()) {
                break;
            }
            List<float[]> vectors = client.embed(batch.stream().map(EmbeddingWork::text).toList(),
                    TaskType.RETRIEVAL_DOCUMENT);
            for (int i = 0; i < batch.size(); i++) {
                postings.saveEmbedding(batch.get(i).id(), vectors.get(i), batch.get(i).contentHash());
            }
            done += batch.size();
            log.info("Embedded {} postings so far", done);
        }
        return done;
    }
}
