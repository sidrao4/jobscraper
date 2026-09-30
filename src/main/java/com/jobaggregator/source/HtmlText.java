package com.jobaggregator.source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Converts board HTML into plain text while keeping paragraph and list breaks. */
public final class HtmlText {

    private static final String BREAK_MARKER = "\u0001";

    private HtmlText() {
    }

    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        for (Element el : doc.select("br, p, div, li, h1, h2, h3, h4, h5, h6, tr")) {
            el.prepend(BREAK_MARKER);
        }
        return normalizeWhitespace(doc.text().replace(BREAK_MARKER, "\n"));
    }

    /** Greenhouse double-encodes content: the HTML itself arrives entity-escaped. */
    public static String unescapeThenPlainText(String escapedHtml) {
        if (escapedHtml == null) {
            return "";
        }
        return toPlainText(Jsoup.parse(escapedHtml).text());
    }

    public static String normalizeWhitespace(String text) {
        return text.replace(' ', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\n ?", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }
}
