package com.jobaggregator.embedding;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.jobaggregator.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/** Calls the Gemini API's batchEmbedContents endpoint. */
@Component
public class GeminiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);
    private static final int MAX_ATTEMPTS = 5;

    private final RestClient http;
    private final AppProperties.Gemini config;

    public GeminiEmbeddingClient(RestClient http, AppProperties props) {
        this.http = http;
        this.config = props.gemini();
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    @Override
    public List<float[]> embed(List<String> texts, TaskType taskType) {
        if (!isEnabled()) {
            throw new IllegalStateException("Gemini API key not configured (set GEMINI_API_KEY)");
        }
        String model = "models/" + config.model();
        List<Request> requests = texts.stream()
                .map(t -> new Request(model, new Content(List.of(new Part(truncate(t)))), taskType.name(),
                        config.dimensions()))
                .toList();

        BatchResponse response = callWithRetry(new BatchRequest(requests));
        if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
            throw new IllegalStateException("Gemini returned an unexpected number of embeddings");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (Embedding e : response.embeddings()) {
            float[] v = new float[e.values().size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = e.values().get(i).floatValue();
            }
            out.add(v);
        }
        return out;
    }

    private BatchResponse callWithRetry(BatchRequest body) {
        Duration backoff = Duration.ofSeconds(2);
        for (int attempt = 1; ; attempt++) {
            try {
                return http.post()
                        .uri(config.baseUrl() + "/models/{model}:batchEmbedContents", config.model())
                        .header("x-goog-api-key", config.apiKey())
                        .body(body)
                        .retrieve()
                        .body(BatchResponse.class);
            } catch (HttpClientErrorException | HttpServerErrorException e) {
                if (!isRetryable(e.getStatusCode()) || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Gemini returned {}, retrying in {}s (attempt {}/{})",
                        e.getStatusCode().value(), backoff.toSeconds(), attempt, MAX_ATTEMPTS);
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    private static boolean isRetryable(HttpStatusCode status) {
        return status.value() == 429 || status.is5xxServerError();
    }

    private String truncate(String text) {
        return text.length() <= config.maxInputChars() ? text : text.substring(0, config.maxInputChars());
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", e);
        }
    }

    record BatchRequest(List<Request> requests) {
    }

    record Request(String model, Content content, String taskType, int outputDimensionality) {
    }

    record Content(List<Part> parts) {
    }

    record Part(String text) {
    }

    record BatchResponse(List<Embedding> embeddings) {
    }

    record Embedding(List<Double> values) {
    }
}
