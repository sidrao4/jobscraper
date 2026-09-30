package com.jobaggregator.embedding;

import java.util.List;

public interface EmbeddingClient {

    enum TaskType {
        /** Job postings being indexed. */
        RETRIEVAL_DOCUMENT,
        /** The resume used to search them. */
        RETRIEVAL_QUERY
    }

    boolean isEnabled();

    List<float[]> embed(List<String> texts, TaskType taskType);
}
