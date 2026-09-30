package com.jobaggregator.normalize;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Reduces a raw posting title to a canonical key so the same role posted with different
 * formatting ("Sr. Software Eng - Remote (US)" vs "Senior Software Engineer") groups together.
 * Seniority levels are kept: "Engineer II" and "Engineer III" are different roles.
 */
@Component
public class TitleNormalizer {

    private static final Pattern BRACKETED = Pattern.compile("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}");
    private static final Pattern REQUISITION = Pattern.compile(
            "(?i)(\\bjob\\s*[-#:]?\\s*\\d{3,}\\b|\\breq(uisition)?\\.?\\s*(id|#|no\\.?)?\\s*[:#]?\\s*[a-z]?-?\\d{3,}\\b|#\\s*\\d{3,}\\b|\\b[jr]-?\\d{4,}\\b)");
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("\\s+[-–—|/]\\s+|\\s*[|•·]\\s*|,\\s+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9+#]+");

    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("sr", "senior"),
            Map.entry("snr", "senior"),
            Map.entry("jr", "junior"),
            Map.entry("eng", "engineer"),
            Map.entry("engr", "engineer"),
            Map.entry("swe", "software engineer"),
            Map.entry("sde", "software engineer"),
            Map.entry("mgr", "manager"),
            Map.entry("mngr", "manager"),
            Map.entry("dir", "director"),
            Map.entry("vp", "vice president"),
            Map.entry("svp", "senior vice president"),
            Map.entry("evp", "executive vice president"),
            Map.entry("assoc", "associate"),
            Map.entry("admin", "administrator"),
            Map.entry("ops", "operations"),
            Map.entry("mktg", "marketing"),
            Map.entry("bdr", "business development representative"),
            Map.entry("sdr", "sales development representative"),
            Map.entry("ae", "account executive"),
            Map.entry("tpm", "technical program manager"),
            Map.entry("acct", "account"),
            Map.entry("exec", "executive"),
            Map.entry("ml", "machine learning"),
            Map.entry("i", "1"),
            Map.entry("ii", "2"),
            Map.entry("iii", "3"),
            Map.entry("iv", "4"),
            Map.entry("v", "5"),
            Map.entry("&", "and"));

    /** Words that describe where or how a job is worked rather than what it is. */
    private static final Set<String> WORK_ARRANGEMENT = Set.of(
            "remote", "hybrid", "onsite", "on", "site", "in", "office", "anywhere", "distributed",
            "us", "usa", "u", "s", "uk", "eu", "emea", "apac", "latam", "amer", "americas", "na",
            "north", "america", "europe", "asia", "global", "worldwide", "united", "states", "kingdom",
            "canada", "based", "only", "time", "zone", "zones", "est", "pst", "cst", "et", "pt", "ct",
            "east", "west", "coast", "central", "and", "or", "the", "multiple", "locations", "location",
            "full", "part", "contract", "contractor", "temporary", "temp", "fixed", "term");

    private static final Set<String> TRAILING_ARRANGEMENT = Set.of("remote", "hybrid", "onsite");

    public String normalize(String title) {
        return normalize(title, null);
    }

    /**
     * @param location the posting's own location string; segments of the title that merely repeat
     *                 it (e.g. "Account Executive - New York") are dropped.
     */
    public String normalize(String title, String location) {
        if (title == null || title.isBlank()) {
            return "";
        }
        Set<String> locationWords = new HashSet<>(WORK_ARRANGEMENT);
        if (location != null) {
            locationWords.addAll(words(fold(location)));
        }

        String t = fold(title);
        t = REQUISITION.matcher(t).replaceAll(" ");
        // "(Remote)" and "[London]" are dropped; "(Card Acquisition)" names a team, so it stays as a segment.
        t = BRACKETED.matcher(t).replaceAll(m -> {
            List<String> inner = words(m.group().substring(1, m.group().length() - 1));
            return inner.isEmpty() || locationWords.containsAll(inner) ? " " : ", " + String.join(" ", inner) + ", ";
        });

        // Drop segments made only of location/arrangement words ("- Remote", "[London]"), unless
        // that would leave nothing, in which case the first segment is the role.
        List<String> segments = new ArrayList<>();
        for (String segment : SEGMENT_SEPARATOR.split(t)) {
            if (!words(segment).isEmpty()) {
                segments.add(segment);
            }
        }
        List<String> kept = new ArrayList<>();
        for (String segment : segments) {
            if (!locationWords.containsAll(words(segment))) {
                kept.add(segment);
            }
        }
        if (kept.isEmpty() && !segments.isEmpty()) {
            kept.add(segments.getFirst());
        }

        List<String> out = new ArrayList<>();
        for (String word : words(String.join(" ", kept))) {
            String expanded = ABBREVIATIONS.getOrDefault(word, word);
            out.addAll(Arrays.asList(expanded.split(" ")));
        }
        // Trailing arrangement words left inside the role segment ("Software Engineer Remote").
        while (out.size() > 1 && TRAILING_ARRANGEMENT.contains(out.getLast())) {
            out.removeLast();
        }
        return String.join(" ", out)
                .replace("front end", "frontend")
                .replace("back end", "backend")
                .replace("full stack", "fullstack");
    }

    private static String fold(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFKD);
        return decomposed.replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace("&", " & ")
                .replace(".", " ");
    }

    private static List<String> words(String s) {
        List<String> result = new ArrayList<>();
        for (String w : s.split("\\s+")) {
            if (w.equals("&")) {
                result.add(w);
                continue;
            }
            String cleaned = NON_WORD.matcher(w).replaceAll("");
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        return result;
    }
}
