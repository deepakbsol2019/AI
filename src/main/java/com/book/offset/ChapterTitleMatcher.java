package com.book.offset;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Matches a complete title portion, never a substring of a paragraph. */
final class ChapterTitleMatcher {
    private static final Pattern LABEL = Pattern.compile(
            "(?i)^(?:chapter|part|unit|lesson|appendix)\\s+(?:[0-9]+|[ivxlcdm]+|[a-z])(?:[\\s.:\\-–—]+|$)");
    private static final Pattern NUMBERED_LABEL = Pattern.compile("^[1-9][0-9]{0,3}[.)]?\\s+(\\S.*)$");

    private ChapterTitleMatcher() { }

    static String titlePortion(String text) {
        return LABEL.matcher(text.replaceAll("\\s+", " ").trim()).replaceFirst("").trim();
    }

    /** Called only after layout confirms a separated, enlarged heading, never on arbitrary PDF text. */
    static String numberedTitlePortion(String text) {
        String joined = text.replaceAll("\\s+", " ").trim();
        var numbered = NUMBERED_LABEL.matcher(joined);
        return numbered.matches() ? numbered.group(1) : joined;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace('\u00a0', ' ')
                .replaceAll("[‘’`´]", "'").replaceAll("[‐‑‒–—−]", "-")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
    }

    static Match match(String title, String target) {
        String actual = normalize(title);
        String expected = normalize(target);
        if (!expected.isEmpty() && actual.equals(expected)) {
            return Match.exact;
        }
        // Only a single visually plausible OCR substitution is permitted. Generic
        // edit-distance matching would confuse a different chapter with this one.
        if (expected.length() >= 8 && actual.length() == expected.length()) {
            int differences = 0;
            for (int i = 0; i < actual.length(); i++) {
                char a = actual.charAt(i);
                char b = expected.charAt(i);
                if (a != b) {
                    if (!ocrEquivalent(a, b) || ++differences > 1) {
                        return Match.none;
                    }
                }
            }
            if (differences == 1) {
                return Match.ocr;
            }
        }
        return Match.none;
    }

    private static boolean ocrEquivalent(char a, char b) {
        return "0o".indexOf(a) >= 0 && "0o".indexOf(b) >= 0
                || "1il".indexOf(a) >= 0 && "1il".indexOf(b) >= 0
                || "5s".indexOf(a) >= 0 && "5s".indexOf(b) >= 0
                || "8b".indexOf(a) >= 0 && "8b".indexOf(b) >= 0;
    }

    enum Match { exact, ocr, none }
}
