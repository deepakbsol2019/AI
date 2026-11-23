package com.book.extraction;

import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.book.constants.ContentsHeading;

/**
 * Request-local contents boundary detection. It only accepts an actual section
 * heading after a contents/index heading; ordinary contents entries are retained.
 */
final class ContentsBoundaryDetector {
    private static final String PAGE = "(?:[0-9]+|[ivxlcdm]+)";
    private static final Pattern PAGE_LABEL = Pattern.compile(
            "(?i)^(?:page\\s+)?" + PAGE + "$");
    private static final Pattern LEGACY_INDEX_HEADER = Pattern.compile(
            "(?i)^(?:" + PAGE + "\\s+)?index"
                    + "\\s*[:：]?\\s*(?:\\(?continued\\)?)?(?:\\s+" + PAGE + ")?$");
    private static final Pattern DOT_LEADERS = Pattern.compile("(?:\\.\\s*){2,}|[…⋯]");
    private static final Pattern ENDING_PAGE_REFERENCE = Pattern.compile(
            "(?i)^.*\\S[\\s.]+(?:[0-9]+(?:[-–—][0-9]+)?|[ivxlcdm]+)$");
    private static final Pattern CHAPTER_MARKER = Pattern.compile(
            "(?i)^(?:chapter|part|unit|lesson)\\s+(?:[0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten)"
                    + "(?:\\s*[:.\\-–—]\\s*|\\s+.*)?$");
    private static final Pattern KNOWN_SECTION = Pattern.compile(
            "(?i)^(?:preface|foreword|introduction|acknowledg(?:e)?ments?|dedication|prologue|epilogue"
                    + "|about\\s+the\\s+authors?|about\\s+this\\s+book|how\\s+to\\s+use\\s+this\\s+book)"
                    + "(?:\\s*[:\\-–—]\\s*.*)?$");
    private boolean contentsSeen;
    private boolean complete;

    record PageScan(String textBeforeBoundary, boolean boundaryFound, boolean contentsSeen) { }

    PageScan scanPage(int pageNumber, String pageText, Collection<String> knownTopicNames) {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("PDF page numbers must be positive.");
        }
        if (complete) {
            return new PageScan("", true, contentsSeen);
        }
        if (pageText == null || pageText.isBlank()) {
            return new PageScan("", false, contentsSeen);
        }
        boolean contentsAlreadySeen = contentsSeen;
        Set<String> topics = knownTopicNames == null ? Set.of() : knownTopicNames.stream()
                .filter(name -> name != null && !name.isBlank()).map(ContentsBoundaryDetector::topicKey)
                .collect(Collectors.toSet());
        List<String> lines = pageText.lines().toList();
        StringBuilder accepted = new StringBuilder();
        int significantLines = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = clean(lines.get(i));
            if (isContentsHeader(line)) {
                contentsSeen = true;
            }
            else if (contentsSeen && isSectionHeading(line, lines, i, significantLines, topics)) {
                complete = true;
                String prefix = accepted.toString();
                // A folio number before a new page heading is not another contents batch.
                return new PageScan(hasMeaningfulText(prefix) ? prefix.stripTrailing() : "", true, true);
            }
            accepted.append(lines.get(i)).append('\n');
            if (!line.isBlank() && !isPageLabel(line)) {
                significantLines++;
            }
        }
        String retained = accepted.toString().stripTrailing();
        if (contentsAlreadySeen && !hasMeaningfulText(retained)) {
            retained = "";
        }
        return new PageScan(retained, false, contentsSeen);
    }

    private static boolean hasMeaningfulText(String text) {
        return text.lines().map(ContentsBoundaryDetector::clean)
                .anyMatch(value -> !value.isBlank() && !isPageLabel(value) && !isContentsHeader(value));
    }

    private static boolean isSectionHeading(String line, List<String> lines, int index,
            int significantLines, Set<String> topics) {
        if (line.isBlank() || isPageLabel(line) || DOT_LEADERS.matcher(line).find()) {
            return false;
        }
        String next = nextTextLine(lines, index);
        // A wrapped contents title may put its reference on the following line.
        if (isPageLabel(next)) {
            return false;
        }
        String withoutLeadingFolio = line.replaceFirst("(?i)^" + PAGE + "\\s+", "");
        if (ENDING_PAGE_REFERENCE.matcher(line).matches()) {
            // A standalone "Chapter 1" is a body heading, not a TOC page reference.
            if (line.matches("(?i)^(?:chapter|part|unit|lesson)\\s+" + PAGE + "\\s*[:.]?$")) {
                return !next.isBlank() && !looksLikeContentsEntry(next);
            }
            // Running section headings sometimes include the folio on the right.
            // Only accept one at the top of a prose page, never among TOC rows.
            String withoutTrailingFolio = line.replaceFirst("(?i)\\s+" + PAGE + "$", "");
            return significantLines < 3 && KNOWN_SECTION.matcher(withoutTrailingFolio).matches()
                    && isProse(next);
        }
        if (KNOWN_SECTION.matcher(withoutLeadingFolio).matches()
                || CHAPTER_MARKER.matcher(line).matches()
                || topics.contains(topicKey(line))) {
            return true;
        }
        // Generic unfamiliar headings need supporting prose; a short TOC fragment
        // alone is insufficient evidence of a section change.
        return significantLines < 6 && line.length() <= 100 && line.split("\\s+").length <= 12
                && Character.isUpperCase(line.codePointAt(0)) && isProse(next);
    }

    private static boolean looksLikeContentsEntry(String line) {
        return DOT_LEADERS.matcher(line).find() || ENDING_PAGE_REFERENCE.matcher(line).matches();
    }

    private static boolean isProse(String line) {
        return !line.isBlank() && !looksLikeContentsEntry(line)
                && (line.split("\\s+").length >= 12 || line.length() >= 65 && line.endsWith("."));
    }

    private static String nextTextLine(List<String> lines, int index) {
        for (int i = index + 1; i < lines.size(); i++) {
            String value = clean(lines.get(i));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String topicKey(String value) {
        return clean(value).toLowerCase(Locale.ROOT)
                .replaceFirst("^(?:(?:chapter|part|unit|lesson)\\s+)?(?:[0-9]+(?:\\.[0-9]+)*|[ivxlcdm]+)[.:]?\\s+", "")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String clean(String line) {
        return Normalizer.normalize(line, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean isPageLabel(String line) {
        return PAGE_LABEL.matcher(line).matches();
    }

    private static boolean isContentsHeader(String line) {
        return ContentsHeading.fromLine(line).isPresent() || LEGACY_INDEX_HEADER.matcher(line).matches();
    }
}
