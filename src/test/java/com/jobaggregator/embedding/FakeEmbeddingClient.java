package com.jobaggregator.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic bag-of-words embedding so ranking can be asserted without calling Gemini. */
public class FakeEmbeddingClient implements EmbeddingClient {

    public static final int DIMENSIONS = 256;

    public int calls;
    public int textsEmbedded;

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<float[]> embed(List<String> texts, TaskType taskType) {
        calls++;
        textsEmbedded += texts.size();
        List<float[]> out = new ArrayList<>();
        for (String text : texts) {
            float[] v = new float[DIMENSIONS];
            for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+")) {
                if (word.length() > 2) {
                    v[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1;
                }
            }
            out.add(v);
        }
        return out;
    }
}
