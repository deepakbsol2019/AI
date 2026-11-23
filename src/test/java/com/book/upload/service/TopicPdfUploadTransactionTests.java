package com.book.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.book.dto.BookExtractionResult;
import com.book.dto.UploadFileDtlsDto;
import com.book.entity.TopicDetails;
import com.book.extraction.BookMetadataExtractionService;
import com.book.extraction.BookPageAnalyzer;
import com.book.offset.BookPageOffsetResult;
import com.book.offset.BookPageOffsetService;
import com.book.offset.ChapterOpeningDetector;
import com.book.offset.PageOffsetAssumptionValidator;
import com.book.offset.SavedBookTopicSelector;
import com.book.offset.TopicPageOffsetWriter;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import com.book.upload.service.impl.FileUploadServiceImpl;
import com.book.upload.topic.TopicPdfFileStore;
import com.book.upload.topic.TopicPdfUploadService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** All initial saves, page mapping, worker files, and path updates succeed or roll back as one upload. */
@DataJpaTest(showSql = false, properties = { "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
@Import({ FileUploadServiceImpl.class, BookMetadataExtractionService.class, BookPageOffsetService.class,
        SavedBookTopicSelector.class, ChapterOpeningDetector.class, PageOffsetAssumptionValidator.class,
        TopicPageOffsetWriter.class, TopicPdfUploadService.class, BookUploadStatusService.class,
        TopicPdfUploadTransactionTests.Configuration.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TopicPdfUploadTransactionTests {
    private static final Path STORAGE_ROOT = temporaryDirectory();
    private static final String MODEL_RESPONSE = """
            {"authorDetails":{"authorName":"Atomic Writer"},
             "bookDetails":{"bookName":"Atomic Book","bookDetails":"A short mathematical introduction."},
             "topicDetails":[{"topicName":"Real Numbers","details":"Natural numbers, Properties",
                 "startPage":null,"endPage":null,"printedStartPage":1,"printedEndPage":null,
                 "topicNumber":"1","continuesPreviousTopic":false}],
             "indexFound":true,"firstTopicStartPage":null,"requiredNextPageSet":true,"contentsComplete":false}
            """;

    @Autowired FileUploadService uploadService;
    @Autowired BookPageAnalyzer analyzer;
    @Autowired FailingFileStore fileStore;
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private String bookName;

    @BeforeEach
    void prepareModelAndStorage() throws IOException {
        reset(analyzer);
        bookName = "Atomic Book " + UUID.randomUUID().toString().substring(0, 8);
        when(analyzer.analyze(any(), any())).thenReturn(MODEL_RESPONSE.replace("Atomic Book", bookName));
        fileStore.failExtraction = false;
        fileStore.attempts.set(0);
        Files.writeString(STORAGE_ROOT.resolve("unrelated-existing-file.txt"), "Keep this file");
    }

    @Test
    void uploadCommitsAuthorBookTopicsOffsetAndCompletedPdfPathsTogether() throws Exception {
        Counts before = counts();

        BookExtractionResult result = uploadService.uploadFile(upload());

        assertThat(counts()).isEqualTo(before.plus(1, 1, 2));
        assertThat(result.authorDetails().getAuthorId()).isNotNull();
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Atomic Writer");
        assertThat(result.bookDetails().getBookName()).isEqualTo(bookName);
        assertThat(result.pageOffset().offsetNumber()).isEqualTo(20);
        assertThat(result.pageOffset().update().status()).isEqualTo(BookPageOffsetResult.UpdateStatus.updated);
        assertThat(result.topicDetails()).hasSize(2);
        TopicDetails prefix = result.topicDetails().getFirst();
        assertThat(prefix.getTopicName()).isEqualTo(bookName + "_offset");
        assertThat(prefix.getStartPage()).isEqualTo(1);
        assertThat(prefix.getEndPage()).isEqualTo(20);
        assertThat(Path.of(prefix.getPdfPath())).hasParent(STORAGE_ROOT.resolve(bookName))
                .hasFileName(bookName + "_offset.pdf");
        try (PDDocument document = Loader.loadPDF(Path.of(prefix.getPdfPath()).toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(20);
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("Contents");
        }
        TopicDetails returned = result.topicDetails().get(1);
        assertThat(returned.getTopicName()).isEqualTo("Real Numbers");
        assertThat(returned.getStartPage()).isEqualTo(21);
        assertThat(returned.getEndPage()).isEqualTo(23);
        assertThat(returned.getPdfPath()).isNotBlank();
        Path savedPdf = Path.of(returned.getPdfPath());
        assertThat(savedPdf).isRegularFile().hasParent(STORAGE_ROOT.resolve(bookName)).hasFileName("Real Numbers.pdf");
        try (PDDocument document = Loader.loadPDF(savedPdf.toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document)).contains("Real Numbers");
        }
        List<TopicDetails> persisted = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(result.bookDetails().getBookId());
        assertThat(persisted).hasSize(2);
        assertThat(persisted.getFirst().getPdfPath()).isEqualTo(returned.getPdfPath());
        assertThat(persisted.getLast().getTopicName()).isEqualTo(bookName + "_offset");
        assertThat(persisted.getLast().getStartPage()).isEqualTo(1);
        assertThat(persisted.getLast().getEndPage()).isEqualTo(20);
        assertThat(persisted.getLast().getPdfPath()).isEqualTo(prefix.getPdfPath());
        assertThat(fileStore.attempts.get()).isEqualTo(2);
        assertMergedMatchesSource(result, pdf());
        verify(analyzer, times(1)).analyze(any(), any());
    }

    @Test
    void exhaustedWorkerIoRetriesRollBackInitialSavesAndOnlyRemoveThisOperationsFiles() throws Exception {
        Counts before = counts();
        List<Path> existingFiles = regularFiles();
        fileStore.failExtraction = true;

        assertThatThrownBy(() -> uploadService.uploadFile(upload())).isInstanceOf(RuntimeException.class);

        assertThat(fileStore.attempts.get()).isEqualTo(4); // Both the offset and real topic receive two I/O attempts.
        assertThat(counts()).isEqualTo(before);
        assertThat(regularFiles()).containsExactlyElementsOf(existingFiles);
        verify(analyzer, times(1)).analyze(any(), any());
    }

    @Test
    void pdfPathPersistenceFailureRollsBackAllRowsAndDeletesAlreadyWrittenPdfs() throws Exception {
        Counts before = counts();
        List<Path> existingFiles = regularFiles();
        long priorMaxBookId = jdbc.queryForObject("select coalesce(max(book_id), 0) from book_details", Long.class);
        jdbc.execute("alter table topic_details add constraint reject_atomic_pdf_path check (pdf_path is null or book_id <= "
                + priorMaxBookId + ")");
        try {
            assertThatThrownBy(() -> uploadService.uploadFile(upload())).isInstanceOf(RuntimeException.class);
            assertThat(fileStore.attempts.get()).isEqualTo(2); // One offset and one real-topic file; database work is never retried.
            assertThat(counts()).isEqualTo(before);
            assertThat(regularFiles()).containsExactlyElementsOf(existingFiles);
            verify(analyzer, times(1)).analyze(any(), any());
        } finally {
            jdbc.execute("alter table topic_details drop constraint reject_atomic_pdf_path");
        }
    }

    @Test
    void callerCommitFailureRollsBackTheJoinedUploadAndCleansItsSuccessfulWorkerFiles() throws Exception {
        Counts before = counts();
        List<Path> existingFiles = regularFiles();
        UploadFileDtlsDto source = upload();
        AtomicReference<BookExtractionResult> provisional = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            BookExtractionResult pending = uploadService.uploadFile(source);
            provisional.set(pending);
            assertThat(Files.exists(Path.of(pending.topicDetails().getFirst().getPdfPath()))).isTrue();
            assertThat(counts()).isEqualTo(before.plus(1, 1, 2));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("Simulated outer commit failure");
                }
            });
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Simulated outer commit failure");

        assertThat(provisional.get()).isNotNull();
        assertThat(counts()).isEqualTo(before);
        assertThat(regularFiles()).containsExactlyElementsOf(existingFiles);
        assertThat(Path.of(provisional.get().topicDetails().getFirst().getPdfPath())).doesNotExist();
        assertThat(fileStore.attempts.get()).isEqualTo(2);
        verify(analyzer, times(1)).analyze(any(), any());
    }

    @Test
    void unnumberedClosingPagesAreRetainedByTheFinalTopicWhenReconstructingTheBook() throws Exception {
        byte[] source;
        try (PDDocument document = Loader.loadPDF(pdf()); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage closing = new PDPage();
            document.addPage(closing);
            try (PDPageContentStream stream = new PDPageContentStream(document, closing)) {
                draw(stream, "Unnumbered closing notes", 12, 600);
            }
            document.save(output);
            source = output.toByteArray();
        }
        UploadFileDtlsDto uploaded = upload();
        uploaded.setFileData(source);
        uploaded.setFileSize((long) source.length);

        BookExtractionResult result = uploadService.uploadFile(uploaded);

        TopicDetails last = result.topicDetails().getLast();
        assertThat(last.getPrintedEndPage()).isEqualTo(3);
        assertThat(last.getEndPage()).isEqualTo(24);
        assertMergedMatchesSource(result, source);
    }

    @Test
    @EnabledIfSystemProperty(named = "book.contents.fixture", matches = ".+")
    void actualThinkJavaUploadIncludesOffsetAndAllTwentyTwoTopicsAndMergesBackToTheCompleteBook() throws Exception {
        byte[] source = Files.readAllBytes(Path.of(System.getProperty("book.contents.fixture")));
        String first = """
                {"authorDetails":{"authorName":"Fixture Writer"},
                 "bookDetails":{"bookName":"%s","bookDetails":"Programming book"},
                 "topicDetails":[],"indexFound":true,"firstTopicStartPage":null,
                 "requiredNextPageSet":true,"contentsComplete":false}
                """.formatted(bookName);
        String continuation = """
                {"topicDetails":[],"indexFound":true,"firstTopicStartPage":null,
                 "requiredNextPageSet":true,"contentsComplete":false}
                """;
        when(analyzer.analyze(any(), any())).thenReturn(first, continuation, continuation);
        UploadFileDtlsDto uploaded = upload();
        uploaded.setFileData(source);
        uploaded.setFileSize((long) source.length);

        BookExtractionResult result = uploadService.uploadFile(uploaded);

        assertThat(result.pageOffset().offsetNumber()).isEqualTo(22);
        assertThat(result.topicDetails()).hasSize(23);
        assertThat(result.topicDetails().getFirst().getTopicName()).isEqualTo(bookName + "_offset");
        assertThat(result.topicDetails().getFirst().getStartPage()).isEqualTo(1);
        assertThat(result.topicDetails().getFirst().getEndPage()).isEqualTo(22);
        assertThat(result.topicDetails().getLast().getTopicName()).isEqualTo("Index");
        assertThat(result.topicDetails().getLast().getEndPage()).isEqualTo(372);
        assertThat(result.topicDetails()).allSatisfy(topic -> assertThat(Path.of(topic.getPdfPath()))
                .hasParent(STORAGE_ROOT.resolve(bookName))
                .hasFileName(TopicPdfFileStore.topicFileName(topic.getTopicName())));
        assertThat(fileStore.attempts.get()).isEqualTo(23);
        assertMergedMatchesSource(result, source);
        verify(analyzer, times(3)).analyze(any(), any());
    }

    private static void assertMergedMatchesSource(BookExtractionResult result, byte[] source) throws IOException {
        org.apache.pdfbox.multipdf.PDFMergerUtility merger = new org.apache.pdfbox.multipdf.PDFMergerUtility();
        try (PDDocument combined = new PDDocument(); PDDocument original = Loader.loadPDF(source)) {
            for (TopicDetails topic : result.topicDetails()) {
                try (PDDocument part = Loader.loadPDF(Path.of(topic.getPdfPath()).toFile())) {
                    merger.appendDocument(combined, part);
                }
            }
            assertThat(combined.getNumberOfPages()).isEqualTo(original.getNumberOfPages());
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            assertThat(stripper.getText(combined)).isEqualTo(stripper.getText(original));
        }
    }

    private Counts counts() {
        return new Counts(jdbc.queryForObject("select count(*) from book_details", Long.class),
                jdbc.queryForObject("select count(*) from author_details", Long.class),
                jdbc.queryForObject("select count(*) from topic_details", Long.class));
    }

    private List<Path> regularFiles() throws IOException {
        try (var paths = Files.walk(STORAGE_ROOT)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private record Counts(long books, long authors, long topics) {
        Counts plus(int books, int authors, int topics) {
            return new Counts(this.books + books, this.authors + authors, this.topics + topics);
        }
    }

    private UploadFileDtlsDto upload() throws Exception {
        UploadFileDtlsDto file = new UploadFileDtlsDto();
        file.setFileName("original-book.pdf");
        file.setContentType("application/pdf");
        file.setFileData(pdf());
        file.setFileSize((long) file.getFileData().length);
        return file;
    }

    private static byte[] pdf() throws IOException {
        String body = "This chapter introduces the natural numbers and explains their properties using several useful mathematical examples.";
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int number = 1; number <= 23; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    if (number == 1) {
                        draw(stream, "Contents", 24, 700);
                        draw(stream, "1 Real Numbers ............. 1", 12, 650);
                    } else if (number == 21) {
                        draw(stream, "Chapter 1", 18, 650);
                        draw(stream, "Real Numbers", 28, 590);
                        draw(stream, body, 12, 520);
                        draw(stream, "1", 12, 40);
                    } else if (number > 21) {
                        draw(stream, "Real Numbers", 12, 750);
                        draw(stream, body, 12, 680);
                        draw(stream, Integer.toString(number - 20), 12, 40);
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static void draw(PDPageContentStream stream, String text, int size, int y) throws IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        stream.newLineAtOffset(50, y);
        stream.showText(text);
        stream.endText();
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("topic-pdf-atomic-tests-").toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @AfterAll
    static void removeTestFiles() throws IOException {
        // This is the exact directory allocated by this test; do not follow links or delete outside it.
        if (!STORAGE_ROOT.getFileName().toString().startsWith("topic-pdf-atomic-tests-")) {
            throw new IllegalStateException("Unexpected test storage path");
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(STORAGE_ROOT)) {
                    throw new IllegalStateException("Path escaped the test storage directory");
                }
                Files.deleteIfExists(path);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean BookPageAnalyzer bookPageAnalyzer() { return mock(BookPageAnalyzer.class); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean FailingFileStore topicPdfFileStore() { return new FailingFileStore(); }
        @Bean(name = "topicPdfExecutor", destroyMethod = "close")
        ExecutorService topicPdfExecutor() { return Executors.newFixedThreadPool(2); }
    }

    static class FailingFileStore extends TopicPdfFileStore {
        volatile boolean failExtraction;
        final AtomicInteger attempts = new AtomicInteger();

        FailingFileStore() { super(STORAGE_ROOT.toString(), 2); }

        @Override
        protected void writeTopicPdf(byte[] sourcePdf, int startPage, int endPage, Path output) throws IOException {
            attempts.incrementAndGet();
            if (failExtraction) {
                Files.writeString(output, "Incomplete worker output");
                throw new IOException("Simulated local PDF saving failure");
            }
            super.writeTopicPdf(sourcePdf, startPage, endPage, output);
        }
    }
}
