package com.jobaggregator.dedup;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Jaccard similarity over hashed word shingles, a cheap near-duplicate test for posting text. */
public final class DescriptionSimilarity {

    private DescriptionSimilarity() {
    }

    public static Set<Long> shingles(String text, int size) {
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}+#]+");
        Set<Long> out = new HashSet<>();
        int n = 0;
        String[] clean = new String[words.length];
        for (String w : words) {
            if (!w.isEmpty()) {
                clean[n++] = w;
            }
        }
        if (n == 0) {
            return out;
        }
        if (n < size) {
            out.add(hash(clean, 0, n));
            return out;
        }
        for (int i = 0; i + size <= n; i++) {
            out.add(hash(clean, i, i + size));
        }
        return out;
    }

    public static double jaccard(Set<Long> a, Set<Long> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<Long> small = a.size() <= b.size() ? a : b;
        Set<Long> large = small == a ? b : a;
        int intersection = 0;
        for (Long h : small) {
            if (large.contains(h)) {
                intersection++;
            }
        }
        return (double) intersection / (a.size() + b.size() - intersection);
    }

    private static long hash(String[] words, int from, int to) {
        // 64-bit FNV-1a keeps collisions negligible at posting scale.
        long h = 0xcbf29ce484222325L;
        for (int i = from; i < to; i++) {
            for (int c = 0; c < words[i].length(); c++) {
                h ^= words[i].charAt(c);
                h *= 0x100000001b3L;
            }
            h ^= ' ';
            h *= 0x100000001b3L;
        }
        return h;
    }
}
