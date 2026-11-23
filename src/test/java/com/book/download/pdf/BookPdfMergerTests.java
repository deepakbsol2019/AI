package com.book.download.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookPdfMergerTests {
    @TempDir Path directory;
    private final BookPdfMerger merger = new BookPdfMerger();

    @Test
    void clonesFrontMatterAndAllSourcePagesInOrder() throws Exception {
        Path graphs = fixture("graphs.pdf", "Graphs page one", "Graphs page two");
        Path searching = fixture("searching.pdf", "Searching page");
        byte[] result = merger.merge(pdf("Cover marker", "Contents marker"),
                List.of(source(graphs, false), source(searching, false)));
        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(5);
            assertThat(new PDFTextStripper().getText(document)).containsSubsequence(
                    "Cover marker", "Contents marker", "Graphs page one", "Graphs page two", "Searching page");
        }
        // Source handles must be closed and sources left intact after merging.
        Files.move(graphs, directory.resolve("graphs-after-merge.pdf"));
        try (PDDocument original = Loader.loadPDF(searching.toFile())) {
            assertThat(original.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void acceptsOnlyExplicitlyAllowedZeroPageOffsetAndStillRequiresOutputPages() throws Exception {
        Path empty = fixture("empty-offset.pdf");
        Path topic = fixture("topic.pdf", "Original topic");
        byte[] result = merger.merge(null, List.of(source(empty, true), source(topic, false)));
        try (PDDocument document = Loader.loadPDF(result)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(new PDFTextStripper().getText(document)).contains("Original topic");
        }
        assertThatThrownBy(() -> merger.merge(null, List.of(source(empty, false))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("has no pages");
        assertThatThrownBy(() -> merger.merge(null, List.of(source(empty, true))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no PDF pages");
        assertThatThrownBy(() -> merger.merge(null, List.of()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no PDF pages");
    }

    @Test
    void corruptOrMissingSourceFailsInsteadOfReturningEarlierPages() throws Exception {
        Path valid = fixture("valid.pdf", "Should never be returned alone");
        Path corrupt = Files.writeString(directory.resolve("corrupt.pdf"), "This is not a PDF");
        for (Path invalid : List.of(corrupt, directory.resolve("missing.pdf"))) {
            assertThatThrownBy(() -> merger.merge(null, List.of(source(valid, false), source(invalid, false))))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Could not read or merge");
        }
    }

    @Test
    void invalidGeneratedPdfFailsInsteadOfReturningOnlyTopics() throws Exception {
        Path topic = fixture("topic.pdf", "Topic marker");
        assertThatThrownBy(() -> merger.merge(new byte[]{1, 2, 3}, List.of(source(topic, false))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Could not merge");
    }

    private BookPdfMerger.SourcePdf source(Path path, boolean allowEmpty) {
        return new BookPdfMerger.SourcePdf(path, allowEmpty);
    }

    private Path fixture(String name, String... markers) throws Exception {
        return Files.write(directory.resolve(name), pdf(markers));
    }

    private byte[] pdf(String... markers) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String marker : markers) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    content.newLineAtOffset(55, 700);
                    content.showText(marker);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
