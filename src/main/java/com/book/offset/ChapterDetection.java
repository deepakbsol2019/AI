package com.book.offset;

import java.util.List;

/** Physical positions are separate from printed folios. Confidence is a heuristic category, not a probability. */
public record ChapterDetection(Status status, Integer pdfPageIndex, Integer pdfPageNumber,
        String printedPageLabel, HeadingMatch matchedHeading, String confidence,
        List<String> evidence, List<Candidate> alternatives, int totalPdfPages,
        List<PrintedAnchor> anchors) {
    public enum Status { found, ambiguous, not_found, insufficient_data }

    /** Bounding box coordinates are PDF points, with y measured down from the page's top. */
    public record HeadingMatch(String text, int pdfPageNumber, float x, float y,
            float width, float height, float fontSize, String fontName) { }

    public record Candidate(HeadingMatch heading, String outcome, List<String> reasons) { }

    /** source distinguishes visible_folio from explicit PDF page-label metadata. */
    public record PrintedAnchor(int pdfPageNumber, int printedPageNumber, String source) { }
}
