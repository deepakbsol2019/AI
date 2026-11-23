package com.book.extraction;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Finds a visible final Arabic page number without equating PDF positions with
 * printed pages. Unnumbered trailing pages do not manufacture another page number.
 */
final class PrintedPageNumberResolver {
    private static final int MAX_TRAILING_PAGES = 8;
    private static final Pattern INTEGER = Pattern.compile("[1-9][0-9]{0,8}");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^([1-9][0-9]{0,8})\\s+(.+)$");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("^(.+?)\\s+([1-9][0-9]{0,8})$");
    private static final Pattern NON_FOLIO_LABEL = Pattern.compile(
            "(?i)^(?:chapter|part|unit|lesson|section|figure|fig\\.?|table|exercise|example|equation|appendix)$");

    private PrintedPageNumberResolver() { }

    static Integer findLastPrintedPage(PDDocument document) throws IOException {
        if (document.getNumberOfPages() < 2) {
            return null;
        }
        String[] labels = document.getDocumentCatalog().getPageLabels() == null ? null
                : document.getDocumentCatalog().getPageLabels().getLabelsByPageIndices();
        List<NumberedPage> numberedPages = new ArrayList<>();
        int first = Math.max(1, document.getNumberOfPages() - MAX_TRAILING_PAGES + 1);
        for (int page = document.getNumberOfPages(); page >= first; page--) {
            MarginStripper stripper = new MarginStripper(document.getPage(page - 1).getCropBox().getHeight());
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            stripper.setSortByPosition(true);
            stripper.getText(document);
            Set<Integer> candidates = new LinkedHashSet<>();
            for (String line : stripper.marginLines()) {
                Integer candidate = folio(line);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
            // Explicit PDF labels can corroborate margin text, but a generated
            // label on an unnumbered back-cover/author page is not printed evidence.
            if (labels != null && page <= labels.length && labels[page - 1] != null
                    && INTEGER.matcher(labels[page - 1]).matches()) {
                Integer label = Integer.valueOf(labels[page - 1]);
                if (candidates.contains(label)) {
                    candidates = new LinkedHashSet<>(Set.of(label));
                }
            }
            for (NumberedPage later : numberedPages) {
                int physicalDistance = later.physicalPage() - page;
                for (Integer current : candidates) {
                    if (later.numbers().contains(current + physicalDistance)) {
                        return current + physicalDistance;
                    }
                }
            }
            if (!candidates.isEmpty()) {
                numberedPages.add(new NumberedPage(page, candidates));
            }
        }
        return null;
    }

    private static Integer folio(String text) {
        String line = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        if (INTEGER.matcher(line).matches()) {
            return Integer.valueOf(line);
        }
        Matcher leading = LEADING_NUMBER.matcher(line);
        if (leading.matches() && isRunningHeading(leading.group(2))) {
            return Integer.valueOf(leading.group(1));
        }
        Matcher trailing = TRAILING_NUMBER.matcher(line);
        if (trailing.matches() && isRunningHeading(trailing.group(1))) {
            return Integer.valueOf(trailing.group(2));
        }
        return null;
    }

    private static boolean isRunningHeading(String text) {
        return !text.isBlank() && text.length() <= 100 && text.split("\\s+").length <= 12
                && !NON_FOLIO_LABEL.matcher(text).matches()
                && text.codePoints().noneMatch(Character::isDigit)
                && !text.endsWith(".") && !text.contains("=");
    }

    private record NumberedPage(int physicalPage, Set<Integer> numbers) { }
    private record Fragment(float x, float y, String text) { }

    private static final class MarginStripper extends PDFTextStripper {
        private final float height;
        private final List<Fragment> fragments = new ArrayList<>();

        private MarginStripper(float height) throws IOException {
            this.height = height;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (!positions.isEmpty()) {
                TextPosition position = positions.getFirst();
                float y = position.getYDirAdj();
                // Some book layouts place running heads about 100pt below the
                // top of a Letter page (Think Java), outside a 10% margin.
                if (y <= height * 0.15f || y >= height * 0.85f) {
                    fragments.add(new Fragment(position.getXDirAdj(), y, text));
                }
            }
            super.writeString(text, positions);
        }

        private List<String> marginLines() {
            List<List<Fragment>> lines = new ArrayList<>();
            for (Fragment fragment : fragments) {
                List<Fragment> line = lines.stream()
                        .filter(candidate -> Math.abs(candidate.getFirst().y() - fragment.y()) < 2.5f)
                        .findFirst().orElseGet(() -> {
                            List<Fragment> created = new ArrayList<>();
                            lines.add(created);
                            return created;
                        });
                line.add(fragment);
            }
            return lines.stream().map(line -> {
                line.sort(Comparator.comparing(Fragment::x));
                return String.join(" ", line.stream().map(Fragment::text).toList());
            }).toList();
        }
    }
}
