package com.book.extraction;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.book.constants.ContentsHeading;

/**
 * Reads printed references from contents text already trimmed at the next section.
 * State belongs to one uploaded PDF, so parent and wrapped-entry context survives
 * page/batch boundaries. A child's reference never replaces its parent's start.
 */
final class ContentsPageReferenceParser {
    private static final Pattern REFERENCE = Pattern.compile(
            "^(.+?)\\s+(\\d+)(?:\\s*[-\\u2013\\u2014]\\s*(\\d+))?\\s*$");
    private static final Pattern SINGLE_REFERENCE = Pattern.compile(
            "^(\\d+)(?:\\s*[-\\u2013\\u2014]\\s*(\\d+))?$");
    private static final Pattern PREFIXED = Pattern.compile(
            "^(?i:chapter|part|unit|lesson|appendix)\\s+"
                    + "([0-9]+(?:\\.[0-9]+)*|[A-Za-z](?:\\.[0-9]+)*|[IVXLCDMivxlcdm]+)"
                    + "[.:)]?\\s+(.+)$");
    private static final Pattern NUMBERED = Pattern.compile(
            "^([0-9]+(?:\\.[0-9]+)*|[A-Z](?:\\.[0-9]+)*|[IVXLCDM]+)[.:)]?\\s+(.+)$");
    private static final Pattern PAGE_MARKER = Pattern.compile("^--- PDF PAGE [0-9]+ ---$");
    private static final Pattern ROMAN_FOLIO = Pattern.compile("(?i)^(?:page\\s+)?[ivxlcdm]+$");
    private static final Pattern ARABIC_FOLIO = Pattern.compile("(?i)^page\\s+[0-9]+$");
    private static final Pattern INDEX_HEADER = Pattern.compile(
            "(?i)^(?:[0-9]+\\s+|[ivxlcdm]+\\s+)?index(?:\\s*[:\\uFF1A])?(?:\\s+\\(?continued\\)?)?(?:\\s+[ivxlcdm]+)?$");
    private static final Pattern FRONT_MATTER = Pattern.compile(
            "(?i)^(?:preface|foreword|acknowledg(?:e)?ments?|copyright|dedication)$");
    private final Map<String, Parent> parents = new LinkedHashMap<>();
    private final Map<String, List<String>> orphanChildren = new LinkedHashMap<>();
    private boolean contentsSeen;
    private String pending;
    private int pendingIndent;
    private String currentParentKey;

    record Entry(String topicNumber, String topicName, Integer printedStartPage,
            Integer explicitPrintedEndPage, String details) { }

    void accept(String contentsText) {
        if (contentsText == null || contentsText.isBlank()) {
            return;
        }
        for (String raw : contentsText.lines().toList()) {
            String line = clean(raw);
            if (line.isBlank() || PAGE_MARKER.matcher(line).matches()) {
                continue;
            }
            if (ContentsHeading.fromLine(line).isPresent() || INDEX_HEADER.matcher(line).matches()) {
                contentsSeen = true;
                continue;
            }
            if (!contentsSeen || ROMAN_FOLIO.matcher(line).matches() || ARABIC_FOLIO.matcher(line).matches()) {
                continue;
            }
            int indent = indentation(raw);
            Matcher single = SINGLE_REFERENCE.matcher(line);
            if (single.matches()) {
                if (pending != null) {
                    parseAndRegister(pending + " " + line, pendingIndent);
                    pending = null;
                }
                continue;
            }
            Parsed direct = parse(line);
            if (direct != null && direct.number != null) {
                // A fresh numbered entry cannot be a continuation of an unrelated
                // title left on the previous line or at the previous page footer.
                register(direct, indent);
                pending = null;
            }
            else if (pending != null) {
                String joined = pending + " " + line;
                if (parseAndRegister(joined, pendingIndent)) {
                    pending = null;
                }
                else if (isWrappedTitle(joined)) {
                    pending = joined;
                }
                else {
                    pending = null;
                }
            }
            else if (direct != null) {
                register(direct, indent);
            }
            else if (isWrappedTitle(line)) {
                pending = line;
                pendingIndent = indent;
            }
        }
    }

    List<Entry> entries() {
        return parents.values().stream().map(parent -> new Entry(parent.number, parent.name,
                parent.start, parent.end, parent.details.isEmpty() ? null : String.join(", ", parent.details.values())))
                .toList();
    }

    private boolean parseAndRegister(String line, int indent) {
        Parsed parsed = parse(line);
        if (parsed == null) {
            return false;
        }
        register(parsed, indent);
        return true;
    }

    private static Parsed parse(String line) {
        Matcher reference = REFERENCE.matcher(line);
        if (!reference.matches()) {
            return null;
        }
        Integer start = positiveInt(reference.group(2));
        Integer end = reference.group(3) == null ? null : positiveInt(reference.group(3));
        if (start == null || end != null && end < start) {
            return null;
        }
        String title = reference.group(1).replaceAll("(?:\\.\\s*){2,}|[\\u2026\\u22EF]+", " ")
                .replaceAll("[\\s.]+$", "").trim();
        Matcher numbered = PREFIXED.matcher(title);
        if (!numbered.matches()) {
            numbered = NUMBERED.matcher(title);
        }
        String number = null;
        if (numbered.matches()) {
            number = numbered.group(1);
            title = numbered.group(2).trim();
        }
        if (title.isBlank() || title.length() > 240 || !title.codePoints().anyMatch(Character::isLetter)
                || FRONT_MATTER.matcher(title).matches() || title.split("\\s+").length > 30) {
            return null;
        }
        return new Parsed(number, title, start, end);
    }

    private void register(Parsed entry, int indent) {
        if (entry.number != null && entry.number.contains(".")) {
            String root = normalizeNumber(entry.number.substring(0, entry.number.indexOf('.')));
            String parentKey = "number:" + root;
            Parent parent = parents.get(parentKey);
            if (parent != null) {
                parent.addDetail(entry.name);
            }
            else {
                orphanChildren.computeIfAbsent(root, ignored -> new ArrayList<>()).add(entry.name);
            }
            return;
        }
        // Indentation is useful where the PDF extractor retains it. Numbered child
        // rows are authoritative regardless of indentation.
        Parent current = currentParentKey == null ? null : parents.get(currentParentKey);
        if (entry.number == null && current != null && indent > current.indent) {
            current.addDetail(entry.name);
            return;
        }
        String number = entry.number == null ? null : normalizeNumber(entry.number);
        String key = number == null ? "name:" + normalizeName(entry.name) : "number:" + number;
        Parent parent = parents.get(key);
        if (parent == null) {
            parent = new Parent(number, entry.name, entry.start, entry.end, indent);
            parents.put(key, parent);
        }
        else {
            if (entry.name.length() > parent.name.length()) {
                parent.name = entry.name;
            }
            if (parent.end == null) {
                parent.end = entry.end;
            }
        }
        if (number != null) {
            Collection<String> children = orphanChildren.remove(number);
            if (children != null) {
                children.forEach(parent::addDetail);
            }
        }
        currentParentKey = key;
    }

    private static boolean isWrappedTitle(String text) {
        return text.length() <= 240 && text.split("\\s+").length <= 30
                && text.codePoints().anyMatch(Character::isLetter)
                && !text.endsWith(".")
                && !FRONT_MATTER.matcher(text).matches();
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private static Integer positiveInt(String value) {
        try {
            int result = Integer.parseInt(value);
            return result > 0 ? result : null;
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replace('\u00a0', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static String normalizeName(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String normalizeNumber(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private record Parsed(String number, String name, Integer start, Integer end) { }

    private static final class Parent {
        private final String number;
        private String name;
        private final Integer start;
        private Integer end;
        private final int indent;
        private final Map<String, String> details = new LinkedHashMap<>();

        private Parent(String number, String name, Integer start, Integer end, int indent) {
            this.number = number;
            this.name = name;
            this.start = start;
            this.end = end;
            this.indent = indent;
        }

        private void addDetail(String detail) {
            details.putIfAbsent(normalizeName(detail), detail);
        }
    }
}
