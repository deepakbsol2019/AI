package com.book.offset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.book.constants.ContentsHeading;
import com.book.offset.ChapterDetection.Candidate;
import com.book.offset.ChapterDetection.HeadingMatch;
import com.book.offset.ChapterDetection.PrintedAnchor;
import com.book.offset.ChapterDetection.Status;

/**
 * Local, layout-aware detection. This does not call an AI model and does not
 * perform OCR: an image-only/illegible PDF is reported as insufficient data.
 * Text lines are candidate blocks; adjacent title/label lines form a heading.
 */
@Component
public class ChapterOpeningDetector {
    private static final Pattern CHAPTER_LABEL = Pattern.compile(
            "(?i)^(?:chapter|part|unit|lesson|appendix)(?:\\s+(?:[0-9]+|[ivxlcdm]+|[a-z]))?\\s*[.:\\-–—]?$" );
    private static final Pattern COUNTER = Pattern.compile("(?i)^(?:example|exercise|figure|fig\\.?|table|equation)\\s*[0-9]+(?:[.:-][0-9]+)*\\b.*");
    private static final Pattern PAGE_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");
    private static final Pattern EDGE_NUMBER = Pattern.compile("^(?:([1-9][0-9]{0,8})\\s+(.+)|(.+?)\\s+([1-9][0-9]{0,8}))$");
    private static final Pattern ENTRY = Pattern.compile(".*(?:\\.{2,}|…|\\s[1-9][0-9]*(?:\\s*[,–-]\\s*[0-9]+)*)\\s*$");
    private final Settings settings;

    public record Settings(int lookAheadPages, float headingFontRatio, float minimumSeparation,
            float marginFraction, float maximumHeadingGap, int maximumHeadingLines) {
        public Settings {
            if (lookAheadPages < 0 || headingFontRatio <= 1 || minimumSeparation < 0
                    || marginFraction <= 0 || marginFraction >= .5f || maximumHeadingGap <= 0
                    || maximumHeadingLines < 1 || maximumHeadingLines > 6) {
                throw new IllegalArgumentException("Invalid chapter detection settings");
            }
        }
        public static Settings defaults() { return new Settings(2, 1.18f, 10f, .15f, 80f, 4); }
    }

    public ChapterOpeningDetector() { this(Settings.defaults()); }
    public ChapterOpeningDetector(Settings settings) { this.settings = Objects.requireNonNull(settings); }

    @Autowired
    public ChapterOpeningDetector(
            @Value("${app.pdf.chapter.look-ahead-pages:2}") int lookAheadPages,
            @Value("${app.pdf.chapter.heading-font-ratio:1.18}") float headingFontRatio,
            @Value("${app.pdf.chapter.minimum-separation:10}") float minimumSeparation,
            @Value("${app.pdf.chapter.margin-fraction:0.15}") float marginFraction,
            @Value("${app.pdf.chapter.maximum-heading-gap:80}") float maximumHeadingGap,
            @Value("${app.pdf.chapter.maximum-heading-lines:4}") int maximumHeadingLines) {
        this(new Settings(lookAheadPages, headingFontRatio, minimumSeparation,
                marginFraction, maximumHeadingGap, maximumHeadingLines));
    }

    public ChapterDetection detect(byte[] pdfBytes, String targetTitle) {
        return detect(pdfBytes, targetTitle, 1);
    }

    /** The optional source-contents printed start corroborates candidates; it is never a topic/database ID. */
    public ChapterDetection detect(byte[] pdfBytes, String targetTitle, Integer expectedPrintedStart) {
        if (pdfBytes == null || pdfBytes.length == 0 || ChapterTitleMatcher.normalize(targetTitle).isEmpty()) {
            return unconfirmed(Status.insufficient_data, 0, List.of(), List.of(), "PDF bytes and a nonempty saved topic title are required");
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() == 0 || !document.getCurrentAccessPermission().canExtractContent()) {
                return unconfirmed(Status.insufficient_data, document.getNumberOfPages(), List.of(), List.of(), "PDF has no readable pages or disallows text extraction");
            }
            List<Page> pages = extract(document);
            int characters = pages.stream().flatMap(page -> page.lines().stream()).mapToInt(line -> line.text().length()).sum();
            if (characters < 8 || pages.stream().flatMap(page -> page.lines().stream()).anyMatch(line -> line.text().contains("\uFFFD"))) {
                return unconfirmed(Status.insufficient_data, pages.size(), List.of(), List.of(),
                        "Text/layout extraction is absent or contains undecodable characters; layout-preserving OCR is required");
            }
            float bodyFont = bodyFont(pages);
            List<PrintedAnchor> anchors = anchors(document, pages, bodyFont);
            List<Candidate> alternatives = new ArrayList<>();
            List<Validated> validated = new ArrayList<>();
            List<Validated> provisional = new ArrayList<>();
            // Generate layout candidates independently of the requested title.
            // Matching is deliberately downstream of this step.
            List<Group> groups = headingGroups(pages, bodyFont);
            for (Group group : groups) {
                ChapterTitleMatcher.Match match = ChapterTitleMatcher.match(group.title(), targetTitle);
                if (match == ChapterTitleMatcher.Match.none) {
                    continue;
                }
                List<String> reasons = new ArrayList<>(group.evidence());
                String rejection = excludedRole(group, pages, bodyFont);
                if (rejection != null) {
                    alternatives.add(new Candidate(group.heading(), "rejected", List.of(rejection)));
                    continue;
                }
                if (match == ChapterTitleMatcher.Match.ocr && (!group.chapterLabel() || !group.larger())) {
                    reasons.add("Possible OCR title substitution lacks a chapter label and larger title style");
                    provisional.add(new Validated(group, match, reasons));
                    continue;
                }
                reasons.add(match == ChapterTitleMatcher.Match.exact ? "Complete normalized topic title matches exactly"
                        : "A single visually plausible OCR substitution matches; confidence is reduced");
                String context = openingContext(group, pages, bodyFont);
                if (context == null) {
                    reasons.add("Configured look-ahead window has insufficient chapter-body evidence");
                    provisional.add(new Validated(group, match, reasons));
                } else {
                    reasons.add(context);
                    validated.add(new Validated(group, match, reasons));
                }
            }
            validated = bestPerLocation(validated);
            provisional = bestPerLocation(provisional);
            // Avoid reporting a shorter fragment of an already validated block as
            // a separate uncertainty (e.g. CHAPTER / 1 / REAL NUMBERS).
            List<Validated> confirmedLocations = validated;
            provisional = provisional.stream().filter(candidate -> confirmedLocations.stream()
                    .noneMatch(found -> sameLocation(candidate.group(), found.group()))).toList();
            validated.sort(Comparator.comparingInt(candidate -> candidate.group().page().number()));
            if (validated.isEmpty()) {
                for (Validated item : provisional) {
                    alternatives.add(new Candidate(item.group().heading(), "provisional", item.reasons()));
                }
                return unconfirmed(provisional.isEmpty() ? Status.not_found : Status.insufficient_data,
                        pages.size(), anchors, alternatives, provisional.isEmpty()
                                ? "No validated heading matches the complete selected topic title"
                                : "A plausible heading exists, but its opening context could not be confirmed");
            }
            Validated selected = validated.getFirst();
            boolean expectedPrintedAnchor = hasPrintedStart(selected, anchors, expectedPrintedStart);
            boolean competingPrintedAnchor = validated.stream().skip(1)
                    .anyMatch(candidate -> hasPrintedStart(candidate, anchors, expectedPrintedStart))
                    || provisional.stream().anyMatch(candidate -> hasPrintedStart(candidate, anchors, expectedPrintedStart));
            boolean unresolvedEarlier = provisional.stream().anyMatch(candidate -> candidate.group().page().number() <= selected.group().page().number());
            if ((validated.size() > 1 && !expectedPrintedAnchor) || unresolvedEarlier
                    || expectedPrintedAnchor && competingPrintedAnchor) {
                for (Validated item : validated) {
                    alternatives.add(new Candidate(item.group().heading(), "plausible_opening", item.reasons()));
                }
                for (Validated item : provisional) {
                    alternatives.add(new Candidate(item.group().heading(), "provisional", item.reasons()));
                }
                return unconfirmed(Status.ambiguous, pages.size(), anchors, alternatives,
                        "Multiple plausible opening locations remain unresolved; no physical start is confirmed");
            }
            for (int i = 1; i < validated.size(); i++) {
                Validated later = validated.get(i);
                alternatives.add(new Candidate(later.group().heading(), "later_reprise", List.of(
                        "An earlier validated opening is corroborated by source-contents printed page " + expectedPrintedStart)));
            }
            for (Validated item : provisional) {
                alternatives.add(new Candidate(item.group().heading(), "provisional_later", item.reasons()));
            }
            List<String> evidence = new ArrayList<>(selected.reasons());
            evidence.add("Selected the earliest validated main-content opening; physical pages are one-based");
            String label = pageLabel(document, pages, selected.group().page().number(), bodyFont);
            return new ChapterDetection(Status.found, selected.group().page().number() - 1,
                    selected.group().page().number(), label, selected.group().heading(),
                    selected.match() == ChapterTitleMatcher.Match.exact ? "high: exact title, layout and following content agree"
                            : "moderate: OCR substitution with chapter-label, layout and body corroboration",
                    List.copyOf(evidence), List.copyOf(alternatives), pages.size(), anchors);
        } catch (IOException | IllegalArgumentException exception) {
            return unconfirmed(Status.insufficient_data, 0, List.of(), List.of(),
                    "PDF layout could not be read: " + exception.getClass().getSimpleName());
        }
    }

    private List<Page> extract(PDDocument document) throws IOException {
        List<Page> pages = new ArrayList<>();
        for (int number = 1; number <= document.getNumberOfPages(); number++) {
            LayoutStripper stripper = new LayoutStripper();
            stripper.setStartPage(number);
            stripper.setEndPage(number);
            stripper.setSortByPosition(true);
            stripper.getText(document);
            pages.add(new Page(number, document.getPage(number - 1).getCropBox().getWidth(),
                    document.getPage(number - 1).getCropBox().getHeight(), stripper.lines()));
        }
        return pages;
    }

    private static float bodyFont(List<Page> pages) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        pages.stream().flatMap(page -> page.lines().stream()).filter(line -> line.text().length() >= 15)
                .forEach(line -> counts.merge(Math.round(line.fontSize() * 2), line.text().length(), Integer::sum));
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(entry -> entry.getKey() / 2f).orElse(12f);
    }

    private List<Group> headingGroups(List<Page> pages, float bodyFont) {
        List<Group> result = new ArrayList<>();
        for (Page page : pages) {
            for (int first = 0; first < page.lines().size(); first++) {
                Line opening = page.lines().get(first);
                if (!shortTitle(opening.text())) {
                    continue;
                }
                List<Line> joined = new ArrayList<>();
                for (int last = first; last < Math.min(page.lines().size(), first + settings.maximumHeadingLines()); last++) {
                    Line current = page.lines().get(last);
                    if (!shortTitle(current.text()) || last > first && !adjacent(joined.getLast(), current)) {
                        break;
                    }
                    joined.add(current);
                    String original = String.join("\n", joined.stream().map(Line::text).toList());
                    boolean label = hasChapterLabel(page.lines(), first, last);
                    String title = ChapterTitleMatcher.titlePortion(original);
                    if (title.isBlank() || CHAPTER_LABEL.matcher(title).matches() || PAGE_NUMBER.matcher(title).matches()) {
                        continue;
                    }
                    float maximumFont = (float) joined.stream().mapToDouble(Line::fontSize).max().orElse(0);
                    boolean larger = maximumFont >= bodyFont * settings.headingFontRatio();
                    boolean bold = joined.stream().anyMatch(Line::bold);
                    float above = first == 0 ? opening.y() : opening.y() - page.lines().get(first - 1).bottom();
                    float below = last + 1 == page.lines().size() ? page.height() - current.bottom()
                            : page.lines().get(last + 1).y() - current.bottom();
                    boolean separated = above >= settings.minimumSeparation() || below >= settings.minimumSeparation();
                    // Some books use "1 Introduction" instead of "Chapter 1 Introduction".
                    // Interpret the integer as a heading label only with independent layout evidence.
                    String numberedTitle = ChapterTitleMatcher.numberedTitlePortion(title);
                    boolean numberedLabel = larger && separated && !numberedTitle.equals(title);
                    if (numberedLabel) {
                        title = numberedTitle;
                        label = true;
                    }
                    boolean titlePageIsolation = above >= bodyFont * 2 && below >= bodyFont * 2
                            && page.lines().size() <= settings.maximumHeadingLines()
                            && page.lines().stream().mapToInt(line -> words(line.text())).sum() <= 12;
                    boolean runningTitle = separated && !(larger || bold || label || titlePageIsolation)
                            && repeatedMargin(page, heading(page, joined), pages);
                    // A large/bold/topmost line alone does not qualify. It needs
                    // isolation and either distinctive styling or a chapter label.
                    if (!separated || !(larger || bold || label || titlePageIsolation || runningTitle)) {
                        continue;
                    }
                    List<String> evidence = new ArrayList<>();
                    evidence.add("Isolated heading block with whitespace separation (PDF points: above="
                            + Math.round(above) + ", below=" + Math.round(below) + ")");
                    if (larger) evidence.add("Heading font is larger than the document's body-text style");
                    if (bold) evidence.add("Heading has a distinctive bold font");
                    if (label) evidence.add("Adjacent chapter/part/unit label corroborates the title block");
                    if (numberedLabel) evidence.add("An isolated enlarged heading uses an integer chapter label; its title portion is matched separately");
                    if (titlePageIsolation) evidence.add("Sparse standalone title-page layout corroborates isolation");
                    if (joined.size() > 1) evidence.add("Combined " + joined.size() + " adjacent heading lines");
                    result.add(new Group(page, first, last, title, heading(page, joined), label, larger, evidence));
                }
            }
        }
        return result;
    }

    private boolean adjacent(Line previous, Line next) {
        float gap = next.y() - previous.bottom();
        boolean label = CHAPTER_LABEL.matcher(previous.text()).matches() || PAGE_NUMBER.matcher(previous.text()).matches();
        return gap >= -2 && gap <= settings.maximumHeadingGap()
                && (label || Math.max(previous.fontSize(), next.fontSize()) / Math.max(1, Math.min(previous.fontSize(), next.fontSize())) <= 1.35f);
    }

    private static boolean shortTitle(String text) {
        return !text.isBlank() && text.length() <= 180 && words(text) <= 20 && !text.matches(".*[.!?;]\\s*$");
    }

    private static boolean hasChapterLabel(List<Line> lines, int first, int last) {
        String joined = String.join(" ", lines.subList(first, last + 1).stream().map(Line::text).toList());
        if (!ChapterTitleMatcher.titlePortion(joined).equals(joined)) return true;
        if (first > 0 && CHAPTER_LABEL.matcher(lines.get(first - 1).text()).matches()
                && lines.get(first).y() - lines.get(first - 1).bottom() <= 85) return true;
        return first > 1 && PAGE_NUMBER.matcher(lines.get(first - 1).text()).matches()
                && CHAPTER_LABEL.matcher(lines.get(first - 2).text()).matches()
                && lines.get(first).y() - lines.get(first - 1).bottom() <= 85;
    }

    private String excludedRole(Group group, List<Page> pages, float bodyFont) {
        String normalized = group.heading().text().replaceAll("\\s+", " ");
        if (COUNTER.matcher(normalized).matches()) return "Numbered example/exercise/figure/table/equation label, not a chapter opening";
        List<Line> lines = group.page().lines();
        if (group.first() > 0 && COUNTER.matcher(lines.get(group.first() - 1).text()).matches()
                && group.heading().y() - lines.get(group.first() - 1).bottom() <= settings.maximumHeadingGap()) {
            return "Title belongs to an adjacent numbered example/exercise/figure/table/equation block";
        }
        boolean leaders = lines.subList(group.first(), group.last() + 1).stream()
                .anyMatch(line -> line.text().matches(".*(?:\\.{2,}|…).*$"));
        boolean numericEntry = lines.subList(group.first(), group.last() + 1).stream().anyMatch(ChapterOpeningDetector::isContentsEntry);
        long nearbyEntries = lines.subList(Math.max(0, group.first() - 4), Math.min(lines.size(), group.last() + 5))
                .stream().filter(ChapterOpeningDetector::isContentsEntry).count();
        boolean contentsNearby = lines.subList(0, Math.min(lines.size(), group.first() + 1)).stream()
                .anyMatch(line -> ContentsHeading.fromLine(line.text()).isPresent());
        if (leaders || nearbyEntries >= 2 && (contentsNearby || numericEntry)
                && group.heading().fontSize() < bodyFont * 1.5f) {
            return "Contents/index list entry with page-number/list context, not an opening heading";
        }
        if (repeatedMargin(group.page(), group.heading(), pages)) return "Repeated running header/footer with the same position and font style";
        return null;
    }

    private static boolean isContentsEntry(Line line) {
        return !CHAPTER_LABEL.matcher(line.text()).matches()
                && !PAGE_NUMBER.matcher(line.text()).matches() && ENTRY.matcher(line.text()).matches();
    }

    private boolean repeatedMargin(Page page, HeadingMatch heading, List<Page> pages) {
        if (heading.y() > page.height() * settings.marginFraction()
                && heading.y() + heading.height() < page.height() * (1 - settings.marginFraction())) return false;
        String title = ChapterTitleMatcher.normalize(ChapterTitleMatcher.titlePortion(heading.text()));
        return pages.stream().filter(other -> other.number() != page.number()).anyMatch(other -> other.lines().stream()
                .anyMatch(line -> Math.abs(line.y() - heading.y()) <= 8
                        && Math.abs(line.fontSize() - heading.fontSize()) <= Math.max(1, heading.fontSize() * .08)
                        && ChapterTitleMatcher.normalize(stripFolio(line.text())).equals(title)));
    }

    private String openingContext(Group group, List<Page> pages, float bodyFont) {
        int wordCount = 0;
        int mathematics = 0;
        for (int pageIndex = group.page().number() - 1;
                pageIndex < Math.min(pages.size(), group.page().number() + settings.lookAheadPages()); pageIndex++) {
            Page page = pages.get(pageIndex);
            int first = pageIndex == group.page().number() - 1 ? group.last() + 1 : 0;
            for (int index = first; index < page.lines().size(); index++) {
                Line line = page.lines().get(index);
                if (isMargin(page, line) && (folio(line, bodyFont) != null
                        || repeatedMargin(page, heading(page, List.of(line)), pages))) continue;
                if (CHAPTER_LABEL.matcher(line.text()).matches() && line.fontSize() > bodyFont * 1.1f) {
                    // Another explicitly labelled chapter cannot supply this one's body.
                    return null;
                }
                if (COUNTER.matcher(line.text()).matches()) continue;
                if (line.text().matches(".*[=+∑∫√].*") && line.text().matches(".*[\\p{L}0-9].*")) {
                    if (++mathematics >= 2) return "Following mathematical expressions corroborate the chapter's opening material";
                    continue;
                }
                if (ENTRY.matcher(line.text()).matches()) continue;
                if (line.fontSize() <= bodyFont * 1.15f && words(line.text()) >= 5) {
                    wordCount += words(line.text());
                    if (wordCount >= 10) return page.number() == group.page().number()
                            ? "Following body-text blocks confirm chapter content on the opening page"
                            : "Standalone opening-title page is followed by chapter body within " + settings.lookAheadPages() + " page(s)";
                }
            }
        }
        return null;
    }

    private List<PrintedAnchor> anchors(PDDocument document, List<Page> pages, float bodyFont) {
        List<PrintedAnchor> result = new ArrayList<>();
        List<PrintedAnchor> visible = new ArrayList<>();
        String[] labels = labels(document);
        for (Page page : pages) {
            for (Line line : page.lines()) {
                if (isMargin(page, line)) {
                    Integer number = folio(line, bodyFont);
                    if (number != null) visible.add(new PrintedAnchor(page.number(), number, "visible_folio"));
                }
            }
            if (labels != null && page.number() <= labels.length && labels[page.number() - 1] != null
                    && PAGE_NUMBER.matcher(labels[page.number() - 1]).matches()) {
                result.add(new PrintedAnchor(page.number(), Integer.parseInt(labels[page.number() - 1]), "pdf_page_label"));
            }
        }
        // A bare number or a number beside a short margin line could instead be
        // an equation/example counter. Admit visible anchors only when the
        // progression on another nearby page or an explicit PDF label agrees.
        for (PrintedAnchor candidate : visible) {
            boolean corroborated = result.stream().anyMatch(label -> label.pdfPageNumber() == candidate.pdfPageNumber()
                    && label.printedPageNumber() == candidate.printedPageNumber())
                    || visible.stream().anyMatch(other -> other.pdfPageNumber() != candidate.pdfPageNumber()
                            && Math.abs(other.pdfPageNumber() - candidate.pdfPageNumber()) <= 8
                            && other.pdfPageNumber() - candidate.pdfPageNumber()
                                    == other.printedPageNumber() - candidate.printedPageNumber());
            if (corroborated) result.add(candidate);
        }
        return result.stream().distinct().toList();
    }

    private String pageLabel(PDDocument document, List<Page> pages, int pageNumber, float bodyFont) {
        Page page = pages.get(pageNumber - 1);
        for (Line line : page.lines()) {
            Integer number = isMargin(page, line) ? folio(line, bodyFont) : null;
            if (number != null) return number.toString();
        }
        String[] labels = labels(document);
        return labels != null && pageNumber <= labels.length ? labels[pageNumber - 1] : null;
    }

    private static String[] labels(PDDocument document) {
        try {
            return document.getDocumentCatalog().getPageLabels() == null ? null
                    : document.getDocumentCatalog().getPageLabels().getLabelsByPageIndices();
        } catch (IOException exception) {
            return null;
        }
    }

    private boolean isMargin(Page page, Line line) {
        return line.y() <= page.height() * settings.marginFraction()
                || line.bottom() >= page.height() * (1 - settings.marginFraction());
    }

    private static Integer folio(Line line, float bodyFont) {
        if (line.fontSize() > bodyFont * 1.15f || line.text().contains("..") || COUNTER.matcher(line.text()).matches()) return null;
        String text = line.text().trim();
        if (PAGE_NUMBER.matcher(text).matches()) return Integer.valueOf(text);
        Matcher matcher = EDGE_NUMBER.matcher(text);
        if (!matcher.matches()) return null;
        String other = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (other.length() > 100 || other.codePoints().anyMatch(Character::isDigit)
                || other.matches("(?i)(?:chapter|part|unit|lesson|appendix|exercise|example|figure|table|equation)")) return null;
        return Integer.valueOf(matcher.group(1) != null ? matcher.group(1) : matcher.group(4));
    }

    private static String stripFolio(String text) {
        return text.replaceFirst("^[0-9]+\\s+", "").replaceFirst("\\s+[0-9]+$", "");
    }

    private static int words(String text) { return text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length; }

    private static HeadingMatch heading(Page page, List<Line> lines) {
        float x = (float) lines.stream().mapToDouble(Line::x).min().orElse(0);
        float y = (float) lines.stream().mapToDouble(Line::y).min().orElse(0);
        float right = (float) lines.stream().mapToDouble(line -> line.x() + line.width()).max().orElse(0);
        float bottom = (float) lines.stream().mapToDouble(Line::bottom).max().orElse(0);
        Line largest = lines.stream().max(Comparator.comparing(Line::fontSize)).orElseThrow();
        return new HeadingMatch(String.join("\n", lines.stream().map(Line::text).toList()), page.number(),
                x, y, right - x, bottom - y, largest.fontSize(), largest.fontName());
    }

    private static List<Validated> bestPerLocation(List<Validated> values) {
        List<Validated> result = new ArrayList<>();
        values.stream().sorted(Comparator.<Validated>comparingInt(value -> value.group().chapterLabel() ? 1 : 0)
                .thenComparingInt(value -> value.group().heading().text().length()).reversed()).forEach(value -> {
                    if (result.stream().noneMatch(existing -> sameLocation(value.group(), existing.group()))) result.add(value);
                });
        return result;
    }

    private static boolean sameLocation(Group a, Group b) {
        return a.page().number() == b.page().number() && a.first() <= b.last() && b.first() <= a.last();
    }

    private static boolean hasPrintedStart(Validated candidate, List<PrintedAnchor> anchors, Integer expectedPrintedStart) {
        return expectedPrintedStart != null && expectedPrintedStart > 0
                && anchors.stream().anyMatch(anchor -> anchor.pdfPageNumber() == candidate.group().page().number()
                        && anchor.printedPageNumber() == expectedPrintedStart);
    }

    private static ChapterDetection unconfirmed(Status status, int pages, List<PrintedAnchor> anchors,
            List<Candidate> alternatives, String reason) {
        return new ChapterDetection(status, null, null, null, null, "unconfirmed: " + reason,
                List.of(reason), List.copyOf(alternatives), pages, List.copyOf(anchors));
    }

    private record Page(int number, float width, float height, List<Line> lines) { }
    private record Line(String text, float x, float y, float width, float height,
            float fontSize, String fontName, boolean bold) {
        float bottom() { return y + height; }
    }
    private record Group(Page page, int first, int last, String title, HeadingMatch heading,
            boolean chapterLabel, boolean larger, List<String> evidence) { }
    private record Validated(Group group, ChapterTitleMatcher.Match match, List<String> reasons) { }
    private record Fragment(String text, float x, float y, float width, float height, float size, String font) { }

    private static final class LayoutStripper extends PDFTextStripper {
        private final List<Fragment> fragments = new ArrayList<>();
        LayoutStripper() throws IOException { }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (!positions.isEmpty() && !text.isBlank()) {
                TextPosition first = positions.getFirst();
                float x = (float) positions.stream().mapToDouble(TextPosition::getXDirAdj).min().orElse(0);
                float right = (float) positions.stream().mapToDouble(position -> position.getXDirAdj() + position.getWidthDirAdj()).max().orElse(x);
                float size = (float) positions.stream().mapToDouble(TextPosition::getFontSizeInPt).average().orElse(0);
                float height = (float) positions.stream().mapToDouble(TextPosition::getHeightDir).max().orElse(0);
                String font = first.getFont() == null ? null : first.getFont().getName();
                fragments.add(new Fragment(text, x, first.getYDirAdj() - height, right - x, height, size, font));
            }
            super.writeString(text, positions);
        }

        List<Line> lines() {
            List<List<Fragment>> grouped = new ArrayList<>();
            for (Fragment fragment : fragments.stream().sorted(Comparator.comparing(Fragment::y).thenComparing(Fragment::x)).toList()) {
                List<Fragment> sameLine = grouped.stream().filter(line -> Math.abs(line.getFirst().y() - fragment.y()) < 3f)
                        .findFirst().orElseGet(() -> { List<Fragment> line = new ArrayList<>(); grouped.add(line); return line; });
                sameLine.add(fragment);
            }
            List<Line> lines = new ArrayList<>();
            for (List<Fragment> group : grouped) {
                group.sort(Comparator.comparing(Fragment::x));
                float x = group.getFirst().x();
                float right = (float) group.stream().mapToDouble(fragment -> fragment.x() + fragment.width()).max().orElse(x);
                float size = (float) group.stream().mapToDouble(Fragment::size).average().orElse(0);
                float height = (float) group.stream().mapToDouble(Fragment::height).max().orElse(0);
                Fragment largest = group.stream().max(Comparator.comparing(Fragment::size)).orElseThrow();
                String font = largest.font();
                lines.add(new Line(String.join(" ", group.stream().map(Fragment::text).toList()).replaceAll("\\s+", " ").trim(),
                        x, group.getFirst().y(), right - x, height, size, font,
                        font != null && font.toLowerCase(Locale.ROOT).matches(".*(?:bold|black|demi|heavy|[.\\-_]bx?[0-9]+).*")));
            }
            return List.copyOf(lines);
        }
    }
}
