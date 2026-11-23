package com.book.upload.topic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicPdfFileStoreTests {
    @TempDir Path root;

    @Test
    void extractsTheInclusiveOneBasedRangeWithoutChangingTheSource() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 3);
        Path operation = store.createOperationDirectory(7L, "Book");
        assertThat(operation).hasParent(root).hasFileName("Book");
        byte[] source = pdf(4);
        byte[] unchanged = source.clone();

        var saved = store.extractAndSave(source, 12L, "A title with / unsafe : characters", 2, 3, operation);

        assertThat(saved.topicId()).isEqualTo(12L);
        Path file = Path.of(saved.pdfPath());
        assertThat(file).hasParent(operation).hasFileName("A title with _ unsafe _ characters.pdf");
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(document)).contains("Source page 2", "Source page 3")
                    .doesNotContain("Source page 1", "Source page 4");
        }
        assertThat(source).containsExactly(unchanged);
        try (var paths = Files.list(operation)) {
            assertThat(paths.toList()).containsExactly(file);
        }
        store.cleanupOperation(operation);
        assertThat(operation).doesNotExist();
    }

    @Test
    void retriesOnlyPdfAndFileIoAndRemovesEveryFailedAttemptFile() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 3) {
            @Override
            protected void writeTopicPdf(byte[] source, int start, int end, Path output) throws IOException {
                if (attempts.incrementAndGet() < 3) {
                    Files.writeString(output, "partial failed PDF");
                    throw new IOException("Transient local save failure");
                }
                super.writeTopicPdf(source, start, end, output);
            }
        };
        Path operation = store.createOperationDirectory(7L, "Book");

        var saved = store.extractAndSave(pdf(2), 12L, "Opening", 1, 2, operation);

        assertThat(attempts).hasValue(3);
        try (var paths = Files.list(operation)) {
            assertThat(paths.toList()).containsExactly(Path.of(saved.pdfPath()));
        }
        try (PDDocument result = Loader.loadPDF(Path.of(saved.pdfPath()).toFile())) {
            assertThat(result.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    void exhaustedRetriesPropagateAndLeaveNoPartialOrFinalPdf() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 3) {
            @Override
            protected void writeTopicPdf(byte[] source, int start, int end, Path output) throws IOException {
                attempts.incrementAndGet();
                Files.writeString(output, "partial");
                throw new IOException("Disk failure");
            }
        };
        Path operation = store.createOperationDirectory(7L, "Book");
        byte[] pdf = pdf(2);

        assertThatThrownBy(() -> store.extractAndSave(pdf, 12L, "Opening", 1, 2, operation))
                .isInstanceOf(TopicPdfFileStore.TopicPdfStorageException.class)
                .hasMessageContaining("3 attempt(s)");

        assertThat(attempts).hasValue(3);
        try (var paths = Files.list(operation)) {
            assertThat(paths.toList()).isEmpty();
        }
        store.cleanupOperation(operation);
        store.cleanupOperation(operation);
        assertThat(operation).doesNotExist();
    }

    @Test
    void aRangeOutsideTheSourceIsNotRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 3) {
            @Override
            protected void writeTopicPdf(byte[] source, int start, int end, Path output) throws IOException {
                attempts.incrementAndGet();
                super.writeTopicPdf(source, start, end, output);
            }
        };
        Path operation = store.createOperationDirectory(7L, "Book");
        byte[] pdf = pdf(2);

        assertThatThrownBy(() -> store.extractAndSave(pdf, 12L, "Opening", 1, 3, operation))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts).hasValue(1);
        try (var paths = Files.list(operation)) {
            assertThat(paths.toList()).isEmpty();
        }
    }

    @Test
    void malformedRangeFailsBeforeAnyExtractionOrFileCreation() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 3) {
            @Override
            protected void writeTopicPdf(byte[] source, int start, int end, Path output) throws IOException {
                attempts.incrementAndGet();
            }
        };
        Path operation = store.createOperationDirectory(7L, "Book");
        byte[] pdf = pdf(2);

        assertThatThrownBy(() -> store.extractAndSave(pdf, 12L, "Opening", 0, 2, operation))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts).hasValue(0);
        try (var paths = Files.list(operation)) {
            assertThat(paths.toList()).isEmpty();
        }
    }

    @Test
    void cleanupRemovesOnlyThisOperationsFilesAndLeavesOtherBooksAndOperationsAlone() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);
        Path first = store.createOperationDirectory(7L, "Book");
        Path sibling = store.createOperationDirectory(7L, "Sibling Book");
        Path otherBook = store.createOperationDirectory(8L, "Other Book");
        byte[] source = pdf(2);
        store.extractAndSave(source, 12L, "First", 1, 1, first);
        var siblingFile = store.extractAndSave(source, 13L, "Sibling", 2, 2, sibling);
        var otherFile = store.extractAndSave(source, 14L, "Other", 1, 2, otherBook);

        store.cleanupOperation(first);

        assertThat(first).doesNotExist();
        assertThat(Path.of(siblingFile.pdfPath())).isRegularFile();
        assertThat(Path.of(otherFile.pdfPath())).isRegularFile();
        assertThatThrownBy(() -> store.cleanupOperation(root)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void committedFilesAreRetainedAndCannotBeCleanedAsAnActiveOperation() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);
        Path operation = store.createOperationDirectory(7L, "Book");
        var saved = store.extractAndSave(pdf(1), 12L, "Opening", 1, 1, operation);

        store.retainOperation(operation);

        assertThat(Path.of(saved.pdfPath())).isRegularFile();
        assertThatThrownBy(() -> store.cleanupOperation(operation)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleanupRefusesToRecursivelyDeleteAnUnexpectedFile() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);
        Path operation = store.createOperationDirectory(7L, "Book");
        var saved = store.extractAndSave(pdf(1), 12L, "Opening", 1, 1, operation);
        Path unrelated = Files.writeString(operation.resolve("unrelated.txt"), "leave me alone");

        assertThatThrownBy(() -> store.cleanupOperation(operation))
                .isInstanceOf(TopicPdfFileStore.TopicPdfStorageException.class);

        assertThat(Path.of(saved.pdfPath())).doesNotExist();
        assertThat(unrelated).hasContent("leave me alone");
        Files.delete(unrelated);
        store.cleanupOperation(operation);
        assertThat(operation).doesNotExist();
    }

    @Test
    void duplicateTopicDispatchCannotOverwriteItsSavedFile() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);
        Path operation = store.createOperationDirectory(7L, "Book");
        byte[] source = pdf(2);
        var saved = store.extractAndSave(source, 12L, "Opening", 1, 1, operation);
        byte[] original = Files.readAllBytes(Path.of(saved.pdfPath()));

        assertThatThrownBy(() -> store.extractAndSave(source, 12L, "Opening", 2, 2, operation))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readAllBytes(Path.of(saved.pdfPath()))).containsExactly(original);
    }

    @Test
    void offsetPrefixUsesItsTopicNameAndIncludesAllFrontPages() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);
        Path operation = store.createOperationDirectory(7L, "My Book");

        var saved = store.extractOffsetAndSave(pdf(4), 99L, "My Book_offset", 2, operation);

        assertThat(Path.of(saved.pdfPath())).hasParent(root.resolve("My Book")).hasFileName("My Book_offset.pdf");
        try (PDDocument document = Loader.loadPDF(Path.of(saved.pdfPath()).toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
            assertThat(new PDFTextStripper().getText(document)).contains("Source page 1", "Source page 2")
                    .doesNotContain("Source page 3", "Source page 4");
        }
    }

    @Test
    void zeroOffsetCreatesAnEmptyPdfThatDoesNotAddPagesWhenMerged() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);
        Path operation = store.createOperationDirectory(7L, "No Prefix Book");
        byte[] source = pdf(2);

        var prefix = store.extractOffsetAndSave(source, 99L, "No Prefix Book_offset", 0, operation);
        var chapter = store.extractAndSave(source, 12L, "Opening", 1, 2, operation);

        try (PDDocument offsetPdf = Loader.loadPDF(Path.of(prefix.pdfPath()).toFile());
                PDDocument chapterPdf = Loader.loadPDF(Path.of(chapter.pdfPath()).toFile());
                PDDocument merged = new PDDocument()) {
            assertThat(offsetPdf.getNumberOfPages()).isZero();
            var merger = new org.apache.pdfbox.multipdf.PDFMergerUtility();
            merger.appendDocument(merged, offsetPdf);
            merger.appendDocument(merged, chapterPdf);
            assertThat(merged.getNumberOfPages()).isEqualTo(2);
        }
        assertThatThrownBy(() -> store.extractAndSave(source, 13L, "Ordinary invalid topic", 1, 0, operation))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowsNamesAreSafeWithoutChangingOrdinaryTopicNames() {
        assertThat(TopicPdfFileStore.bookFolderName("Think Java")).isEqualTo("Think Java");
        assertThat(TopicPdfFileStore.topicFileName("Real Numbers")).isEqualTo("Real Numbers.pdf");
        assertThat(TopicPdfFileStore.bookFolderName("Book: One/Two")).isEqualTo("Book_ One_Two");
        assertThat(TopicPdfFileStore.topicFileName("CON")).isEqualTo("_CON.pdf");
        assertThat(TopicPdfFileStore.topicFileName("NUL.txt")).isEqualTo("_NUL.txt.pdf");
        assertThat(TopicPdfFileStore.topicFileName("Topic... ")).isEqualTo("Topic.pdf");
        assertThat(TopicPdfFileStore.bookFolderName("../outside")).isEqualTo(".._outside");
        assertThatThrownBy(() -> TopicPdfFileStore.bookFolderName("..")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicPdfFileStore.topicFileName(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicPdfFileStore.topicFileName("a".repeat(252))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void collidingSanitizedOrCaseInsensitiveNamesCannotOverwriteAFile() throws Exception {
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);
        Path operation = store.createOperationDirectory(7L, "Book");
        byte[] source = pdf(2);
        var saved = store.extractAndSave(source, 12L, "A/B", 1, 1, operation);
        byte[] original = Files.readAllBytes(Path.of(saved.pdfPath()));

        assertThatThrownBy(() -> store.extractAndSave(source, 13L, "A:B", 2, 2, operation))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.extractAndSave(source, 14L, "a_b", 2, 2, operation))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readAllBytes(Path.of(saved.pdfPath()))).containsExactly(original);
    }

    @Test
    void anExistingBookFolderIsNeverAdoptedOrModified() throws Exception {
        Path existing = Files.createDirectory(root.resolve("Existing Book"));
        Path existingPdf = Files.writeString(existing.resolve("Opening.pdf"), "previous upload");
        TopicPdfFileStore store = new TopicPdfFileStore(root.toString(), 1);

        assertThatThrownBy(() -> store.createOperationDirectory(8L, "Existing Book"))
                .isInstanceOf(TopicPdfFileStore.TopicPdfStorageException.class);

        assertThat(existingPdf).hasContent("previous upload");
        assertThatThrownBy(() -> store.cleanupOperation(existing)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int number = 1; number <= pages; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 600);
                    content.showText("Source page " + number);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
