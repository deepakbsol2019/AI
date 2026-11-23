package com.book.offset;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

import com.book.offset.BookPageOffsetResult.Issue;
import com.book.offset.SavedBookTopicSelector.Selection;

/** Verifies printed numbering independently from the number of physical pages before the first chapter. */
@Component
public class PageOffsetAssumptionValidator {

    public record Validation(Integer offsetNumber, Integer printedPageOffset, List<Issue> issues) {
        public Validation { issues = List.copyOf(issues); }
        public Validation(Integer offsetNumber, List<Issue> issues) {
            this(offsetNumber, offsetNumber, issues);
        }
    }

    public Validation validate(Selection selection, ChapterDetection detection) {
        List<Issue> issues = new ArrayList<>();
        Long topicId = selection.firstTopic().getTopicId();
        if (detection.status() != ChapterDetection.Status.found || detection.pdfPageIndex() == null
                || detection.pdfPageNumber() == null) {
            issues.add(new Issue(topicId, "unconfirmed_opening", "A confirmed chapter opening is required."));
            return new Validation(null, null, issues);
        }
        int index = detection.pdfPageIndex();
        int page = detection.pdfPageNumber();
        if (index < 0 || page < 1 || (long) index + 1 != page || page > detection.totalPdfPages()) {
            issues.add(new Issue(topicId, "invalid_detection_position", "Inconsistent physical PDF positions."));
        }
        if (detection.totalPdfPages() <= 0 || selection.totalPages() <= 0) {
            issues.add(new Issue(null, "invalid_pdf_page_count", "A positive PDF page count is required."));
        }
        if (selection.totalPages() != detection.totalPdfPages()) {
            issues.add(new Issue(null, "pdf_page_count_mismatch", "The PDF page count differs from the saved book."));
        }
        Integer firstPrintedPage = selection.firstTopic().getPrintedStartPage();
        if (firstPrintedPage == null || firstPrintedPage <= 0) {
            issues.add(new Issue(topicId, "invalid_first_printed_page",
                    "The selected first topic '" + selection.firstTopic().getTopicName()
                            + "' requires a positive, verified original printed start page."));
        }
        if (!issues.isEmpty()) {
            return new Validation(null, null, issues);
        }
        int printedPageOffset = page - firstPrintedPage;
        String label = detection.printedPageLabel();
        if (label != null && !label.isBlank() && !matchesPrintedPage(label, firstPrintedPage)) {
            issues.add(new Issue(topicId, "opening_printed_label_mismatch",
                    "The detected opening has printed page label '" + label
                            + "', which does not confirm the selected topic's printed start " + firstPrintedPage + "."));
        }
        long lastRelevantPage = selection.realTopics().stream()
                .filter(topic -> topic.getPrintedEndPage() != null)
                .mapToLong(topic -> (long) topic.getPrintedEndPage() + printedPageOffset)
                .max().orElse(detection.totalPdfPages());
        lastRelevantPage = Math.min(lastRelevantPage, detection.totalPdfPages());
        int checked = 0;
        int mismatches = 0;
        ChapterDetection.PrintedAnchor firstMismatch = null;
        for (ChapterDetection.PrintedAnchor anchor : detection.anchors()) {
            if (anchor.pdfPageNumber() < page || anchor.pdfPageNumber() > lastRelevantPage
                    || anchor.printedPageNumber() <= 0) {
                continue;
            }
            checked++;
            long impliedOffset = (long) anchor.pdfPageNumber() - anchor.printedPageNumber();
            if (impliedOffset != printedPageOffset) {
                mismatches++;
                if (firstMismatch == null) firstMismatch = anchor;
            }
        }
        if (firstMismatch != null) {
            long impliedOffset = (long) firstMismatch.pdfPageNumber() - firstMismatch.printedPageNumber();
            issues.add(new Issue(topicId, "printed_anchor_mismatch",
                    "Physical page " + firstMismatch.pdfPageNumber() + " has printed page "
                            + firstMismatch.printedPageNumber() + " (" + firstMismatch.source()
                            + "), implying a printed-page offset of " + impliedOffset
                            + " rather than the chapter anchor's printed-page offset " + printedPageOffset
                            + ". The separate pre-chapter prefix length is " + index + ". " + mismatches
                            + " printed-page anchor(s) disagree; no page update was attempted."));
        }
        if (checked == 0) {
            issues.add(new Issue(topicId, "unverified_printed_anchor",
                    "No readable printed-page anchor confirms the selected topic's printed start "
                            + firstPrintedPage + " at physical page " + page + "."));
        }
        return new Validation(issues.isEmpty() ? index : null,
                issues.isEmpty() ? printedPageOffset : null, issues);
    }

    private static boolean matchesPrintedPage(String label, int expected) {
        String number = label.strip();
        if (!number.matches("[0-9]+")) return false;
        try {
            return Integer.parseInt(number) == expected;
        }
        catch (NumberFormatException ignored) {
            return false;
        }
    }
}
