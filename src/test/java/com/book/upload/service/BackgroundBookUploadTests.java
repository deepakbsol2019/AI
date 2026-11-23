package com.book.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.book.dto.UploadFileDtlsDto;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Real background execution and independently committed receipts around the complete book transaction. */
@DataJpaTest(showSql = false, properties = { "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
@Import({ FileUploadServiceImpl.class, BookMetadataExtractionService.class, BookPageOffsetService.class,
        SavedBookTopicSelector.class, ChapterOpeningDetector.class, PageOffsetAssumptionValidator.class,
        TopicPageOffsetWriter.class, TopicPdfUploadService.class, BookUploadStatusService.class,
        BookUploadSubmissionService.class, BackgroundBookUploadService.class, BackgroundBookUploadTests.Configuration.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackgroundBookUploadTests {
    private static final Path STORAGE_ROOT = temporaryDirectory();

    @Autowired BookUploadSubmissionService submissions;
    @Autowired BookUploadStatusService statuses;
    @Autowired BookPageAnalyzer analyzer;
    @Autowired RecordingExecutor bookExecutor;
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;
    @Autowired JdbcTemplate jdbc;
    private String bookName;
    private CountDownLatch analyzerRelease = new CountDownLatch(0);

    @BeforeEach
    void prepare() throws IOException {
        reset(analyzer);
        bookExecutor.prepareJob();
        analyzerRelease = new CountDownLatch(0);
        bookName = "Background Book " + UUID.randomUUID().toString().substring(0, 8);
        when(analyzer.analyze(any(), any())).thenReturn(modelResponse());
        Files.writeString(STORAGE_ROOT.resolve("existing-unrelated.txt"), "Preserve committed and unrelated files");
    }

    @AfterEach
    void drainJobEvenIfAnAssertionFailed() throws InterruptedException {
        analyzerRelease.countDown();
        bookExecutor.awaitFinished(10, TimeUnit.SECONDS);
    }

    @Test
    void returnsInProgressBeforeModelCompletesThenCommitsBookAndSuccessUnderTheSameUploadId() throws Exception {
        Counts before = counts();
        CountDownLatch enteredAnalyzer = new CountDownLatch(1);
        analyzerRelease = new CountDownLatch(1);
        when(analyzer.analyze(any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            enteredAnalyzer.countDown();
            if (!analyzerRelease.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Model release timed out");
            return modelResponse();
        });
        UploadFileDtlsDto originalUpload = upload();
        String originalFileName = originalUpload.getFileName();
        byte[] requestBytes = originalUpload.getFileData();

        BookUploadStatus acknowledgement = submissions.submit(originalUpload);

        assertThat(acknowledgement.getBookUploadId()).isPositive();
        assertThat(acknowledgement.getStatus()).isEqualTo("inprogress");
        assertThat(acknowledgement.getMessage()).isEqualTo(originalFileName
                + " upload service is uploading book topic by topic. please check final upload status after some time.");
        assertThat(enteredAnalyzer.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(bookExecutor.isFinished()).isFalse();
        assertThat(statuses.get(acknowledgement.getBookUploadId()).getStatus()).isEqualTo("inprogress");
        assertThat(counts()).isEqualTo(before);

        // HTTP request objects/bytes can be discarded after submit; the job has its own stable source.
        Arrays.fill(requestBytes, (byte) 0);
        originalUpload.setFileData(new byte[] {0});
        originalUpload.setFileName("request-ended.pdf");
        analyzerRelease.countDown();
        assertThat(bookExecutor.awaitFinished(10, TimeUnit.SECONDS)).isTrue();

        BookUploadStatus completed = statuses.get(acknowledgement.getBookUploadId());
        assertThat(completed.getStatus()).isEqualTo("Success");
        assertThat(completed.getMessage()).isEqualTo("uploaded " + bookName + " successfully.");
        assertThat(acknowledgement.getStatus()).isEqualTo("inprogress");
        assertThat(counts()).isEqualTo(before.plus(1, 1, 2));
        Long bookId = jdbc.queryForObject("select book_id from book_details where book_upload_id = ?",
                Long.class, acknowledgement.getBookUploadId());
        BookDetails savedBook = books.findById(bookId).orElseThrow();
        assertThat(savedBook.getBookName()).isEqualTo(bookName);
        assertThat(savedBook.getBookUploadId()).isEqualTo(acknowledgement.getBookUploadId());
        List<TopicDetails> savedTopics = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(bookId);
        assertThat(savedTopics).hasSize(2).allSatisfy(topic -> {
            assertThat(topic.getPdfPath()).isNotBlank();
            assertThat(Path.of(topic.getPdfPath())).isRegularFile().hasParent(STORAGE_ROOT.resolve(bookName));
        });
        assertThat(savedTopics).extracting(TopicDetails::getTopicName)
                .containsExactly("Real Numbers", bookName + "_offset");
        try (PDDocument prefix = Loader.loadPDF(Path.of(savedTopics.getLast().getPdfPath()).toFile());
                PDDocument chapter = Loader.loadPDF(Path.of(savedTopics.getFirst().getPdfPath()).toFile())) {
            assertThat(prefix.getNumberOfPages()).isEqualTo(20);
            assertThat(chapter.getNumberOfPages()).isEqualTo(3);
        }
        verify(analyzer, times(1)).analyze(any(), any());
    }

    @Test
    void modelFailureLeavesTheReceiptFailedAndRollsBackEveryBookRow() throws Exception {
        Counts before = counts();
        List<Path> existingFiles = regularFiles();
        when(analyzer.analyze(any(), any())).thenThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Model unavailable"));

        BookUploadStatus acknowledgement = submissions.submit(upload());
        assertThat(bookExecutor.awaitFinished(10, TimeUnit.SECONDS)).isTrue();

        BookUploadStatus failed = statuses.get(acknowledgement.getBookUploadId());
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getMessage()).isEqualTo("Model unavailable");
        assertThat(acknowledgement.getStatus()).isEqualTo("inprogress");
        assertThat(counts()).isEqualTo(before);
        assertThat(regularFiles()).containsExactlyElementsOf(existingFiles);
        assertThat(jdbc.queryForObject("select count(*) from book_details where book_upload_id = ?",
                Long.class, acknowledgement.getBookUploadId())).isZero();
        verify(analyzer, times(1)).analyze(any(), any());
    }

    @Test
    void commitFailureRollsBackSuccessAndFilesThenPersistsFailedInANewTransaction() throws Exception {
        Counts before = counts();
        List<Path> existingFiles = regularFiles();
        CountDownLatch beforeCommitReached = new CountDownLatch(1);
        when(analyzer.analyze(any(), any())).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    assertThat(jdbc.queryForObject("select status from book_upload_status where book_upload_id = "
                                    + "(select book_upload_id from book_details where book_name = ?)",
                            String.class, bookName)).isEqualTo("Success");
                    beforeCommitReached.countDown();
                    throw new IllegalStateException("Commit rejected");
                }
            });
            return modelResponse();
        });

        BookUploadStatus acknowledgement = submissions.submit(upload());
        assertThat(bookExecutor.awaitFinished(10, TimeUnit.SECONDS)).isTrue();

        assertThat(beforeCommitReached.getCount()).isZero();
        BookUploadStatus failed = statuses.get(acknowledgement.getBookUploadId());
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getMessage()).isEqualTo("Commit rejected");
        assertThat(acknowledgement.getStatus()).isEqualTo("inprogress");
        assertThat(counts()).isEqualTo(before);
        assertThat(regularFiles()).containsExactlyElementsOf(existingFiles);
        assertThat(STORAGE_ROOT.resolve(bookName)).doesNotExist();
        verify(analyzer, times(1)).analyze(any(), any());
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

    private String modelResponse() {
        return """
                {"authorDetails":{"authorName":"Background Writer"},
                 "bookDetails":{"bookName":"%s","bookDetails":"A short mathematical introduction."},
                 "topicDetails":[{"topicName":"Real Numbers","details":"Natural numbers, Properties",
                     "startPage":null,"endPage":null,"printedStartPage":1,"printedEndPage":null,
                     "topicNumber":"1","continuesPreviousTopic":false}],
                 "indexFound":true,"firstTopicStartPage":null,"requiredNextPageSet":true,"contentsComplete":false}
                """.formatted(bookName);
    }

    private UploadFileDtlsDto upload() throws IOException {
        UploadFileDtlsDto upload = new UploadFileDtlsDto();
        upload.setFileName("submitted-" + UUID.randomUUID().toString().substring(0, 8) + ".pdf");
        upload.setContentType("application/pdf");
        upload.setFileData(pdf());
        upload.setFileSize((long) upload.getFileData().length);
        return upload;
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
            return Files.createTempDirectory("background-book-tests-").toAbsolutePath().normalize();
        }
        catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @AfterAll
    static void removeTestFiles() throws IOException {
        if (!STORAGE_ROOT.getFileName().toString().startsWith("background-book-tests-")) {
            throw new IllegalStateException("Unexpected test storage directory");
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(STORAGE_ROOT)) {
                    throw new IllegalStateException("Path escaped the allocated test directory");
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private record Counts(long books, long authors, long topics) {
        Counts plus(int books, int authors, int topics) {
            return new Counts(this.books + books, this.authors + authors, this.topics + topics);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean BookPageAnalyzer bookPageAnalyzer() { return mock(BookPageAnalyzer.class); }
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean TopicPdfFileStore topicPdfFileStore() { return new TopicPdfFileStore(STORAGE_ROOT.toString(), 2); }
        @Bean(name = "topicPdfExecutor", destroyMethod = "close")
        ExecutorService topicPdfExecutor() { return Executors.newFixedThreadPool(2); }
        @Bean(name = "bookUploadExecutor", destroyMethod = "close")
        RecordingExecutor bookUploadExecutor() { return new RecordingExecutor(); }
    }

    static class RecordingExecutor implements Executor, AutoCloseable {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "book-background-test"));
        private volatile CountDownLatch finished = new CountDownLatch(0);

        void prepareJob() { finished = new CountDownLatch(1); }
        boolean isFinished() { return finished.getCount() == 0; }
        boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
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
