package com.book.extraction;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrintedPageNumberResolverTests {
    @Test
    void resolvesConsecutivePrintedFooters() throws Exception {
        try (PDDocument document = pages(List.of("98", "99", "100"), false)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isEqualTo(100);
        }
    }

    @Test
    void resolvesAlternatingRunningHeadersWithPageNumbers() throws Exception {
        try (PDDocument document = pages(List.of("347 INDEX", "INDEX 348", "349 INDEX"), true)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isEqualTo(349);
        }
    }

    @Test
    void resolvesBookRunningHeadsInsideFifteenPercentPageMargin() throws Exception {
        // Think Java's running head baseline is approximately 691pt from bottom.
        try (PDDocument document = pages(List.of("348 INDEX", "INDEX 349"), 691)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isEqualTo(349);
        }
    }

    @Test
    void resolvesFootersSetAboveTheOutermostTenPercent() throws Exception {
        try (PDDocument document = pages(List.of("348", "349"), 92)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isEqualTo(349);
        }
    }

    @Test
    void doesNotInventNumbersForUnnumberedOrBlankTrailingPages() throws Exception {
        try (PDDocument document = pages(List.of("348", "349", "", ""), true)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isEqualTo(349);
        }
    }

    @Test
    void noVisibleNumberingReturnsUnknownInsteadOfPdfLength() throws Exception {
        try (PDDocument document = pages(List.of("", "", ""), false)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isNull();
        }
    }

    @Test
    void chapterAndFigureCountersAreNotPrintedFolios() throws Exception {
        try (PDDocument document = pages(List.of("Chapter 1", "Chapter 2", "Figure 3"), true)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isNull();
        }
    }

    @Test
    void repeatedPageLikeNumberWithoutProgressionIsInsufficient() throws Exception {
        try (PDDocument document = pages(List.of("42", "42", "42"), false)) {
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isNull();
        }
    }

    @Test
    void metadataDoesNotCreateAVisibleNumberOnTrailingAuthorPage() throws Exception {
        try (PDDocument document = pages(List.of("348 INDEX", "INDEX 349", ""), true)) {
            PDPageLabels labels = new PDPageLabels(document);
            PDPageLabelRange range = new PDPageLabelRange();
            range.setStyle(PDPageLabelRange.STYLE_DECIMAL);
            range.setStart(348);
            labels.setLabelItem(0, range);
            document.getDocumentCatalog().setPageLabels(labels);
            assertThat(PrintedPageNumberResolver.findLastPrintedPage(document)).isEqualTo(349);
        }
    }

    private static PDDocument pages(List<String> labels, boolean header) throws IOException {
        return pages(labels, header ? 750 : 30);
    }

    private static PDDocument pages(List<String> labels, float labelY) throws IOException {
        PDDocument document = new PDDocument();
        for (String label : labels) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 400);
                stream.showText("Ordinary body text without a printed page reference.");
                stream.endText();
                if (!label.isBlank()) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(50, labelY);
                    stream.showText(label);
                    stream.endText();
                }
            }
        }
        return document;
    }
}
