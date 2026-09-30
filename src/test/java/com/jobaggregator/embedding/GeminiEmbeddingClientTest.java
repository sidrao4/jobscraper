package com.jobaggregator.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import com.jobaggregator.config.AppProperties;
import com.jobaggregator.embedding.EmbeddingClient.TaskType;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiEmbeddingClientTest {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents";

    private final AppProperties props = new AppProperties(
            new AppProperties.Ingest("0 0 * * * *", false, 1, Duration.ofSeconds(5)),
            new AppProperties.Dedup(0.7, 4, 3),
            new AppProperties.Gemini("test-key", "https://generativelanguage.googleapis.com/v1beta",
                    "gemini-embedding-001", 3, 100, 0, 10));

    @Test
    void sendsBatchRequestAndParsesVectors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().json("""
                        {"requests": [
                          {"model": "models/gemini-embedding-001", "content": {"parts": [{"text": "short"}]},
                           "taskType": "RETRIEVAL_DOCUMENT", "outputDimensionality": 3},
                          {"model": "models/gemini-embedding-001", "content": {"parts": [{"text": "truncated!"}]},
                           "taskType": "RETRIEVAL_DOCUMENT", "outputDimensionality": 3}
                        ]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {"embeddings": [{"values": [0.1, 0.2, 0.3]}, {"values": [-1, 0, 1]}]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = new GeminiEmbeddingClient(builder.build(), props)
                .embed(List.of("short", "truncated! beyond limit"), TaskType.RETRIEVAL_DOCUMENT);

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(-1f, 0f, 1f);
        server.verify();
    }

    @Test
    void retriesOnRateLimit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess("{\"embeddings\": [{\"values\": [1, 2, 3]}]}", MediaType.APPLICATION_JSON));

        List<float[]> vectors = new GeminiEmbeddingClient(builder.build(), props)
                .embed(List.of("q"), TaskType.RETRIEVAL_QUERY);

        assertThat(vectors.getFirst()).containsExactly(1f, 2f, 3f);
        server.verify();
    }
}
