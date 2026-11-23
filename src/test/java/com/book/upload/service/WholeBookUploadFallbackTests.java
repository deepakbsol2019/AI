package com.book.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.book.dto.UploadFileDtlsDto;
import com.book.dto.BookExtractionResult;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;
import com.book.entity.TopicDetails;
import com.book.extraction.BookMetadataExtractionService;
import com.book.extraction.BookPageAnalyzer;
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
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange;
import org.apache.pdfbox.pdmodel.common.PDPageLabels;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The background job keeps a complete original PDF when contents cannot define a safe partition. */
@DataJpaTest(showSql = false, properties = {"debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO"})
@Import({FileUploadServiceImpl.class, BookMetadataExtractionService.class, BookPageOffsetService.class,
        SavedBookTopicSelector.class, ChapterOpeningDetector.class, PageOffsetAssumptionValidator.class,
        TopicPageOffsetWriter.class, TopicPdfUploadService.class, WholeBookFallbackService.class,
        BookUploadStatusService.class, BookUploadSubmissionService.class, BackgroundBookUploadService.class,
        WholeBookUploadFallbackTests.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WholeBookUploadFallbackTests {
    private static final Path STORAGE_ROOT = temporaryDirectory();
    private static final String DESCRIPTION = "A factual description retained from the book metadata.";
    private static final String BODY = "This page explains the central ideas of the book and provides complete examples for every reader.";

    @Autowired BookUploadSubmissionService submissions;
    @Autowired FileUploadService uploads;
    @Autowired BookUploadStatusService statuses;
    @Autowired BookPageAnalyzer analyzer;
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingExecutor bookExecutor;
    @Autowired FailingWholeBookStore fileStore;
    private String bookName;

    @BeforeEach
    void prepare() throws IOException {
        reset(analyzer);
        bookName = "Whole Book " + UUID.randomUUID().toString().substring(0, 8);
        bookExecutor.prepare();
        fileStore.failWholeBook = false;
        fileStore.wholeBookAttempts.set(0);
        fileStore.splitAttempts.set(0);
        Files.writeString(STORAGE_ROOT.resolve("unrelated-existing.txt"), "Preserve this file");
    }

    @AfterEach
    void drainWorker() throws InterruptedException {
        assertThat(bookExecutor.awaitFinished()).as("the accepted background job finished").isTrue();
    }

    @Test
    void missingContentsUploadsExactlyOneOriginalPdfWithBookMetadataAndUploadTracking() throws Exception {
        byte[] pdf = plainPdf(3);
        when(analyzer.analyze(any(), any())).thenReturn(response(false, false, "[]"));
        Counts before = counts();

        BookUploadStatus receipt = submitAndWait(pdf);

        assertWholeBook(receipt, pdf, 3, before);
        verify(analyzer, times(1)).analyze(any(), any());
    }

    @Test
    void contentsWithoutRequiredParentReferencesUploadsOneWholeBook() throws Exception {
        byte[] pdf = contentsWithoutReferences();
        when(analyzer.analyze(any(), any())).thenReturn(response(true, false,
                "[{\"topicName\":\"Opening\",\"details\":\"Incomplete topic details\","
                        + "\"printedStartPage\":null,\"printedEndPage\":null,\"topicNumber\":\"1\"}]"));
        Counts before = counts();

        BookUploadStatus receipt = submitAndWait(pdf);

        assertWholeBook(receipt, pdf, 3, before);
    }

    @Test
    void unfindableContentsChapterIsReplacedByOneWholeBookInsteadOfAFailedUpload() throws Exception {
        byte[] pdf = missingChapterPdf();
        when(analyzer.analyze(any(), any())).thenReturn(response(true, false,
                "[{\"topicName\":\"Missing Chapter\",\"details\":\"Basic ideas\","
                        + "\"printedStartPage\":1,\"printedEndPage\":null,\"topicNumber\":\"1\"}]"));
        Counts before = counts();

        BookUploadStatus receipt = submitAndWait(pdf);

        assertWholeBook(receipt, pdf, 4, before);
    }

    @Test
    void unsafeGapAfterPageMappingRemovesAllPartialTopicsAndOffsetBeforeSavingOneWholeBook() throws Exception {
        byte[] pdf = gappedContentsPdf();
        when(analyzer.analyze(any(), any())).thenReturn(response(true, false,
                "[{\"topicName\":\"Opening\",\"details\":\"First ideas\","
                        + "\"printedStartPage\":1,\"printedEndPage\":null,\"topicNumber\":\"1\"},"
                        + "{\"topicName\":\"Later\",\"details\":\"Later ideas\","
                        + "\"printedStartPage\":5,\"printedEndPage\":null,\"topicNumber\":\"2\"}]"));
        Counts before = counts();

        BookUploadStatus receipt = submitAndWait(pdf);

        assertWholeBook(receipt, pdf, 8, before);
        assertThat(fileStore.splitAttempts.get()).isZero();
    }

    @Test
    void missingFinalPrintedEndFromWriterValidationDoesNotMarkTheFallbackTransactionRollbackOnly() throws Exception {
        byte[] pdf = numberedPdfWithoutFinalPrintedEnd();
        when(analyzer.analyze(any(), any())).thenReturn(response(true, false,
                "[{\"topicName\":\"Opening\",\"details\":\"Basic ideas\","
                        + "\"printedStartPage\":1,\"printedEndPage\":null,\"topicNumber\":\"1\"}]"));
        Counts before = counts();
        BookUploadStatus receipt = statuses.create("numbered-source.pdf");
        UploadFileDtlsDto input = new UploadFileDtlsDto();
        input.setFileName("numbered-source.pdf");
        input.setContentType("application/pdf");
        input.setFileData(pdf);
        input.setFileSize((long) pdf.length);
        input.setBookUploadId(receipt.getBookUploadId());

        BookExtractionResult result = uploads.uploadFile(input);

        assertThat(result.wholeBook()).isTrue();
        assertThat(result.pageOffset()).isNotNull();
        assertThat(result.pageOffset().detection().pdfPageNumber()).isEqualTo(3);
        assertThat(result.pageOffset().update().issues()).anySatisfy(issue ->
                assertThat(issue.code()).isEqualTo("invalid_printed_pages"));
        assertWholeBook(receipt, pdf, 4, before);
    }

    @Test
    void wholeBookFileFailureStillFailsTheReceiptAndRollsBackAllBookRows() throws Exception {
        byte[] pdf = plainPdf(3);
        when(analyzer.analyze(any(), any())).thenReturn(response(false, false, "[]"));
        fileStore.failWholeBook = true;
        Counts before = counts();
        List<Path> existing = regularFiles();

        BookUploadStatus receipt = submitAndWait(pdf);

        assertThat(statuses.get(receipt.getBookUploadId()).getStatus()).isEqualTo(BookUploadStatus.FAILED);
        assertThat(counts()).isEqualTo(before);
        assertThat(regularFiles()).containsExactlyElementsOf(existing);
        assertThat(fileStore.wholeBookAttempts.get()).isEqualTo(2);
        assertThat(fileStore.splitAttempts.get()).isZero();
        assertThat(STORAGE_ROOT.resolve(bookName)).doesNotExist();
    }

    @Test
    void pathPersistenceFailureAfterWholeBookCopyIsNotRescuedOrRetried() throws Exception {
        byte[] pdf = plainPdf(3);
        when(analyzer.analyze(any(), any())).thenReturn(response(false, false, "[]"));
        Counts before = counts();
        List<Path> existing = regularFiles();
        long lastBookId = jdbc.queryForObject("select coalesce(max(book_id),0) from book_details", Long.class);
        jdbc.execute("alter table topic_details add constraint reject_whole_book_path check (pdf_path is null or book_id <= "
                + lastBookId + ")");
        try {
            BookUploadStatus receipt = submitAndWait(pdf);
            assertThat(statuses.get(receipt.getBookUploadId()).getStatus()).isEqualTo(BookUploadStatus.FAILED);
            assertThat(counts()).isEqualTo(before);
            assertThat(regularFiles()).containsExactlyElementsOf(existing);
            assertThat(fileStore.wholeBookAttempts.get()).isEqualTo(1);
            assertThat(fileStore.splitAttempts.get()).isZero();
        }
        finally {
            jdbc.execute("alter table topic_details drop constraint reject_whole_book_path");
        }
    }

    @Test
    void modelConnectionFailureStillReportsFailureRatherThanAssumingBadContents() throws Exception {
        when(analyzer.analyze(any(), any())).thenThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Model unavailable"));
        Counts before = counts();
        List<Path> existing = regularFiles();

        BookUploadStatus receipt = submitAndWait(plainPdf(3));

        BookUploadStatus failure = statuses.get(receipt.getBookUploadId());
        assertThat(failure.getStatus()).isEqualTo(BookUploadStatus.FAILED);
        assertThat(failure.getMessage()).isEqualTo("Model unavailable");
        assertThat(counts()).isEqualTo(before);
        assertThat(regularFiles()).containsExactlyElementsOf(existing);
        assertThat(fileStore.wholeBookAttempts.get()).isZero();
        verify(analyzer, times(1)).analyze(any(), any());
    }

    private BookUploadStatus submitAndWait(byte[] pdf) throws InterruptedException {
        UploadFileDtlsDto input = new UploadFileDtlsDto();
        input.setFileName("source-" + UUID.randomUUID().toString().substring(0, 8) + ".pdf");
        input.setContentType("application/pdf");
        input.setFileData(pdf);
        input.setFileSize((long) pdf.length);
        BookUploadStatus receipt = submissions.submit(input);
        assertThat(receipt.getStatus()).isEqualTo(BookUploadStatus.IN_PROGRESS);
        assertThat(bookExecutor.awaitFinished()).isTrue();
        return receipt;
    }

    private void assertWholeBook(BookUploadStatus receipt, byte[] original, int pages, Counts before) throws IOException {
        BookUploadStatus completed = statuses.get(receipt.getBookUploadId());
        assertThat(completed.getStatus()).withFailMessage(completed.getMessage()).isEqualTo(BookUploadStatus.SUCCESS);
        assertThat(completed.getMessage()).isEqualTo("uploaded " + bookName + " successfully.");
        assertThat(counts()).isEqualTo(before.plus(1, 1, 1));
        Long bookId = jdbc.queryForObject("select book_id from book_details where book_upload_id = ?",
                Long.class, receipt.getBookUploadId());
        BookDetails book = books.findById(bookId).orElseThrow();
        assertThat(book.getBookName()).isEqualTo(bookName);
        assertThat(book.getBookDetails()).isEqualTo(DESCRIPTION);
        assertThat(book.getBookUploadId()).isEqualTo(receipt.getBookUploadId());
        assertThat(jdbc.queryForObject("select a.author_name from author_details a join book_details b on b.author_id=a.aouthor_id where b.book_id=?",
                String.class, bookId)).isEqualTo("Fallback Writer");
        List<TopicDetails> saved = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(bookId);
        assertThat(saved).hasSize(1);
        TopicDetails only = saved.getFirst();
        assertThat(only.getTopicName()).isEqualTo(bookName);
        assertThat(only.getDetails()).isEqualTo(DESCRIPTION);
        assertThat(only.getStartPage()).isEqualTo(1);
        assertThat(only.getEndPage()).isEqualTo(pages);
        assertThat(only.getPrintedStartPage()).isNull();
        assertThat(only.getPrintedEndPage()).isNull();
        Path output = Path.of(only.getPdfPath());
        assertThat(output).isRegularFile().hasParent(STORAGE_ROOT.resolve(bookName)).hasFileName(bookName + ".pdf");
        assertThat(Files.readAllBytes(output)).isEqualTo(original);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertThat(document.getNumberOfPages()).isEqualTo(pages);
        }
        try (var paths = Files.list(output.getParent())) {
            assertThat(paths.toList()).containsExactly(output);
        }
        assertThat(fileStore.wholeBookAttempts.get()).isEqualTo(1);
        assertThat(fileStore.splitAttempts.get()).isZero();
    }

    private String response(boolean indexFound, boolean complete, String topicDetails) {
        return """
                {"authorDetails":{"authorName":"Fallback Writer"},
                 "bookDetails":{"bookName":"%s","bookDetails":"%s"},
                 "topicDetails":%s,"indexFound":%s,"firstTopicStartPage":null,
                 "requiredNextPageSet":%s,"contentsComplete":%s}
                """.formatted(bookName, DESCRIPTION, topicDetails, indexFound, indexFound && !complete, complete);
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

    private static byte[] plainPdf(int pages) throws IOException {
        return pdf(pages, (number, stream) -> draw(stream, BODY + " Page " + number + ".", 12, 600));
    }

    private static byte[] contentsWithoutReferences() throws IOException {
        return pdf(3, (number, stream) -> {
            if (number == 1) {
                draw(stream, "Contents", 24, 700);
                draw(stream, "Opening", 12, 650);
                draw(stream, "Basic ideas", 12, 630);
            }
            else draw(stream, BODY, 12, 600);
        });
    }

    private static byte[] missingChapterPdf() throws IOException {
        return pdf(4, (number, stream) -> {
            if (number == 1) {
                draw(stream, "Contents", 24, 700);
                draw(stream, "1 Missing Chapter ......... 1", 12, 650);
            }
            else {
                if (number == 2) draw(stream, "Different Chapter", 28, 650);
                draw(stream, BODY, 12, 580);
                draw(stream, Integer.toString(number - 1), 12, 40);
            }
        });
    }

    private static byte[] gappedContentsPdf() throws IOException {
        return pdf(8, (number, stream) -> {
            if (number == 1) {
                draw(stream, "Contents", 24, 700);
                draw(stream, "1 Opening ......... 1-2", 12, 650);
                draw(stream, "2 Later ......... 5-6", 12, 625);
            }
            else if (number >= 3) {
                if (number == 3 || number == 7) {
                    draw(stream, number == 3 ? "Chapter 1" : "Chapter 2", 18, 650);
                    draw(stream, number == 3 ? "Opening" : "Later", 28, 590);
                }
                draw(stream, BODY, 12, 520);
                draw(stream, Integer.toString(number - 2), 12, 40);
            }
        });
    }

    private static byte[] numberedPdfWithoutFinalPrintedEnd() throws IOException {
        byte[] content = pdf(4, (number, stream) -> {
            if (number == 1) {
                draw(stream, "Contents", 24, 700);
                draw(stream, "1 Opening ......... 1", 12, 650);
            }
            else if (number >= 3) {
                if (number == 3) {
                    draw(stream, "Chapter 1", 18, 650);
                    draw(stream, "Opening", 28, 590);
                    draw(stream, "1", 12, 40);
                }
                draw(stream, BODY, 12, 520);
                // The final physical page has no visible folio. PDF labels can corroborate
                // chapter location, but cannot invent the missing final printed end value.
            }
        });
        try (PDDocument document = Loader.loadPDF(content); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPageLabels labels = new PDPageLabels(document);
            PDPageLabelRange body = new PDPageLabelRange();
            body.setStyle(PDPageLabelRange.STYLE_DECIMAL);
            body.setStart(1);
            labels.setLabelItem(2, body);
            document.getDocumentCatalog().setPageLabels(labels);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] pdf(int pageCount, PageWriter writer) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int number = 1; number <= pageCount; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    writer.write(number, stream);
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
            return Files.createTempDirectory("whole-book-fallback-tests-").toAbsolutePath().normalize();
        }
        catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @AfterAll
    static void cleanTestFiles() throws IOException {
        if (!STORAGE_ROOT.getFileName().toString().startsWith("whole-book-fallback-tests-")) {
            throw new IllegalStateException("Unexpected test storage root");
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(STORAGE_ROOT)) {
                    throw new IllegalStateException("Test cleanup escaped its allocated directory");
                }
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface PageWriter { void write(int number, PDPageContentStream stream) throws IOException; }

    private record Counts(long books, long authors, long topics) {
        Counts plus(int books, int authors, int topics) {
            return new Counts(this.books + books, this.authors + authors, this.topics + topics);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean BookPageAnalyzer bookPageAnalyzer() { return mock(BookPageAnalyzer.class); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean FailingWholeBookStore topicPdfFileStore() { return new FailingWholeBookStore(); }
        @Bean(name = "topicPdfExecutor", destroyMethod = "close")
        ExecutorService topicPdfExecutor() { return Executors.newFixedThreadPool(2); }
        @Bean(name = "bookUploadExecutor", destroyMethod = "close")
        RecordingExecutor bookUploadExecutor() { return new RecordingExecutor(); }
    }

    static class FailingWholeBookStore extends TopicPdfFileStore {
        final AtomicInteger wholeBookAttempts = new AtomicInteger();
        final AtomicInteger splitAttempts = new AtomicInteger();
        volatile boolean failWholeBook;

        FailingWholeBookStore() { super(STORAGE_ROOT.toString(), 2); }

        @Override
        protected void writeTopicPdf(byte[] source, int startPage, int endPage, Path output) throws IOException {
            splitAttempts.incrementAndGet();
            super.writeTopicPdf(source, startPage, endPage, output);
        }

        @Override
        protected void writeWholeBookPdf(byte[] source, Path output) throws IOException {
            wholeBookAttempts.incrementAndGet();
            if (failWholeBook) {
                Files.writeString(output, "Incomplete whole-book copy");
                throw new IOException("Simulated whole-book local save failure");
            }
            super.writeWholeBookPdf(source, output);
        }
    }

    static class RecordingExecutor implements Executor, AutoCloseable {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private volatile CountDownLatch finished = new CountDownLatch(0);
        private volatile boolean submitted;

        void prepare() { finished = new CountDownLatch(1); submitted = false; }
        boolean awaitFinished() throws InterruptedException {
            return !submitted || finished.await(10, TimeUnit.SECONDS);
        }

        @Override
        public void execute(Runnable command) {
            submitted = true;
            CountDownLatch completion = finished;
            delegate.execute(() -> {
                try { command.run(); }
                finally { completion.countDown(); }
            });
        }

        @Override
        public void close() { delegate.close(); }
    }
}
