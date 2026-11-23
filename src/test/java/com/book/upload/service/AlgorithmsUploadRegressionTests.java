package com.book.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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

import com.book.dto.BookExtractionResult;
import com.book.dto.UploadFileDtlsDto;
import com.book.entity.BookUploadStatus;
import com.book.entity.TopicDetails;
import com.book.repository.TopicDetailsRepository;
import com.book.extraction.BookMetadataExtractionService;
import com.book.extraction.BookPageAnalyzer;
import com.book.offset.BookPageOffsetResult;
import com.book.offset.BookPageOffsetService;
import com.book.offset.ChapterDetection;
import com.book.offset.ChapterOpeningDetector;
import com.book.offset.PageOffsetAssumptionValidator;
import com.book.offset.SavedBookTopicSelector;
import com.book.offset.TopicPageOffsetWriter;
import com.book.upload.service.impl.FileUploadServiceImpl;
import com.book.upload.topic.TopicPdfFileStore;
import com.book.upload.topic.TopicPdfUploadService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Replays the reported null-valued model response against the real Wikibooks Algorithms PDF. */
@DataJpaTest(showSql = false, properties = {"debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO"})
@Import({FileUploadServiceImpl.class, BookMetadataExtractionService.class, BookPageOffsetService.class,
        SavedBookTopicSelector.class, ChapterOpeningDetector.class, PageOffsetAssumptionValidator.class,
        TopicPageOffsetWriter.class, TopicPdfUploadService.class, BookUploadStatusService.class,
        BookUploadSubmissionService.class, BackgroundBookUploadService.class, WholeBookFallbackService.class,
        AlgorithmsUploadRegressionTests.Configuration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfSystemProperty(named = "book.algorithms.fixture", matches = ".+")
class AlgorithmsUploadRegressionTests {
    private static final Path STORAGE_ROOT = temporaryDirectory();
    private static final String MODEL_RESPONSE = """
            {"authorDetails":{"authorName":null},"bookDetails":{"bookName":null,"bookDetails":null},
             "topicDetails":[
               {"topicName":"Introduction","details":"Prerequisites, When is Efficiency Important?","topicNumber":"1"},
               {"topicName":"Mathematical Background","details":"Asymptotic Notation","topicNumber":"2"},
               {"topicName":"Divide and Conquer","details":"Merge Sort, Binary Search","topicNumber":"3"},
               {"topicName":"Randomization","details":"Ordered Statistics, Quicksort","topicNumber":"4"},
               {"topicName":"Backtracking","details":"Largest Independent Set","topicNumber":"5"},
               {"topicName":"Dynamic Programming","details":"Fibonacci Numbers","topicNumber":"6"},
               {"topicName":"Greedy Algorithms","details":"Event Scheduling Problem","topicNumber":"7"},
               {"topicName":"Hill Climbing","details":"Network Flow","topicNumber":"8"},
               {"topicName":"Ada Implementation","details":"Introduction","topicNumber":"9"},
               {"topicName":"Contributors","details":"Contributors","topicNumber":"10"},
               {"topicName":"Licenses","details":"GNU Free Documentation License","topicNumber":"11"}],
             "indexFound":false,"firstTopicStartPage":null,"requiredNextPageSet":true,"contentsComplete":false}
            """;

    @Autowired FileUploadService uploads;
    @Autowired BookUploadSubmissionService submissions;
    @Autowired BookUploadStatusService statuses;
    @Autowired BookPageAnalyzer analyzer;
    @Autowired RecordingExecutor bookExecutor;
    @Autowired JdbcTemplate jdbc;
    @Autowired TopicDetailsRepository topics;

    @BeforeEach
    void replayReportedModelResponse() {
        reset(analyzer);
        when(analyzer.analyze(any(), any())).thenReturn(MODEL_RESPONSE);
        bookExecutor.prepareJob();
    }

    @Test
    @Transactional
    void actualPrintedThreeOpeningUsesMappingThreeAndKeepsFivePrefixPagesForACompleteBook() throws Exception {
        byte[] original = fixture();

        BookExtractionResult result = uploads.uploadFile(source(original));

        assertThat(result.topicDetails()).hasSize(13);
        List<TopicDetails> chapters = result.topicDetails().subList(1, result.topicDetails().size());
        assertThat(chapters.getFirst().getTopicName()).isEqualTo("Introduction");
        assertThat(chapters).extracting(TopicDetails::getPrintedStartPage)
                .containsExactly(3, 11, 19, 35, 45, 49, 57, 61, 69, 77, 81, 85);
        assertThat(chapters).extracting(TopicDetails::getTopicName).contains("List of Figures");
        assertThat(result.authorDetails().getAuthorName()).isNull();

        assertThat(result.pageOffset().detection().status()).isEqualTo(ChapterDetection.Status.found);
        assertThat(result.pageOffset().detection().pdfPageNumber()).isEqualTo(6);
        assertThat(result.pageOffset().detection().printedPageLabel()).isEqualTo("3");
        assertThat(result.pageOffset().selectedTopicName()).isEqualTo("Introduction");
        assertThat(result.pageOffset().update().status()).isEqualTo(BookPageOffsetResult.UpdateStatus.updated);
        assertThat(result.pageOffset().update().issues()).isEmpty();
        assertThat(result.pageOffset().offsetNumber()).isEqualTo(5);
        assertThat(result.pageOffset().printedPageOffset()).isEqualTo(3);
        assertThat(result.topicDetails().getFirst().getTopicName()).isEqualTo(result.bookDetails().getBookName() + "_offset");
        assertThat(result.topicDetails().getFirst().getStartPage()).isEqualTo(1);
        assertThat(result.topicDetails().getFirst().getEndPage()).isEqualTo(5);
        assertThat(chapters.getFirst().getStartPage()).isEqualTo(6);
        assertThat(chapters.getFirst().getEndPage()).isEqualTo(13);
        assertThat(chapters.getLast().getEndPage()).isEqualTo(90);
        assertThat(result.topicDetails()).allSatisfy(topic -> {
            assertThat(topic.getPdfPath()).isNotBlank();
            assertThat(Path.of(topic.getPdfPath())).isRegularFile();
        });
        assertMergedMatchesSource(result.topicDetails(), original);
        verify(analyzer, times(1)).analyze(any(), any());
    }

    @Test
    void backgroundUploadCommitsSuccessAndAllThirteenPdfsUnderItsReceipt() throws Exception {
        Counts before = counts();
        byte[] original = fixture();
        UploadFileDtlsDto source = source(original);

        BookUploadStatus acknowledgement = submissions.submit(source);

        assertThat(acknowledgement.getStatus()).isEqualTo(BookUploadStatus.IN_PROGRESS);
        assertThat(bookExecutor.awaitFinished()).isTrue();
        BookUploadStatus completed = statuses.get(acknowledgement.getBookUploadId());
        assertThat(completed.getStatus()).isEqualTo(BookUploadStatus.SUCCESS);
        assertThat(completed.getMessage()).isEqualTo("uploaded " + source.getFileName() + " successfully.");
        assertThat(acknowledgement.getStatus()).isEqualTo(BookUploadStatus.IN_PROGRESS);
        assertThat(counts()).isEqualTo(new Counts(before.books() + 1, before.authors() + 1, before.topics() + 13));
        Long bookId = jdbc.queryForObject("select book_id from book_details where book_upload_id = ?",
                Long.class, acknowledgement.getBookUploadId());
        List<TopicDetails> saved = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(bookId).stream()
                .sorted(Comparator.comparing(TopicDetails::getStartPage)).toList();
        assertThat(saved).hasSize(13);
        assertThat(saved.getFirst().getTopicName()).isEqualTo(source.getFileName() + "_offset");
        assertThat(saved.getFirst().getEndPage()).isEqualTo(5);
        assertThat(saved.get(1).getPrintedStartPage()).isEqualTo(3);
        assertThat(saved.get(1).getStartPage()).isEqualTo(6);
        assertThat(saved.getLast().getEndPage()).isEqualTo(90);
        assertMergedMatchesSource(saved, original);
        verify(analyzer, times(1)).analyze(any(), any());
    }

    private static UploadFileDtlsDto source(byte[] original) {
        UploadFileDtlsDto source = new UploadFileDtlsDto();
        source.setFileName("Algorithms-" + UUID.randomUUID().toString().substring(0, 8) + ".pdf");
        source.setContentType("application/pdf");
        source.setFileData(original);
        source.setFileSize((long) original.length);
        return source;
    }

    private static void assertMergedMatchesSource(List<TopicDetails> orderedTopics, byte[] source) throws IOException {
        PDFMergerUtility merger = new PDFMergerUtility();
        try (PDDocument original = Loader.loadPDF(source); PDDocument merged = new PDDocument()) {
            for (TopicDetails topic : orderedTopics) {
                try (PDDocument part = Loader.loadPDF(Path.of(topic.getPdfPath()).toFile())) {
                    merger.appendDocument(merged, part);
                }
            }
            assertThat(merged.getNumberOfPages()).isEqualTo(90).isEqualTo(original.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            assertThat(stripper.getText(merged)).isEqualTo(stripper.getText(original));
        }
    }

    private static byte[] fixture() throws IOException {
        return Files.readAllBytes(Path.of(System.getProperty("book.algorithms.fixture")));
    }

    private Counts counts() {
        return new Counts(jdbc.queryForObject("select count(*) from book_details", Long.class),
                jdbc.queryForObject("select count(*) from author_details", Long.class),
                jdbc.queryForObject("select count(*) from topic_details", Long.class));
    }

    private record Counts(long books, long authors, long topics) { }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("algorithms-upload-regression-").toAbsolutePath().normalize();
        }
        catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @AfterAll
    static void removeOnlyAllocatedTestFiles() throws IOException {
        if (!STORAGE_ROOT.getFileName().toString().startsWith("algorithms-upload-regression-")) {
            throw new IllegalStateException("Unexpected test directory");
        }
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(STORAGE_ROOT)) {
                    throw new IllegalStateException("Path escaped this test's directory");
                }
                Files.deleteIfExists(path);
            }
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
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private volatile CountDownLatch finished = new CountDownLatch(0);
        void prepareJob() { finished = new CountDownLatch(1); }
        boolean awaitFinished() throws InterruptedException { return finished.await(20, TimeUnit.SECONDS); }
        @Override public void execute(Runnable job) {
            CountDownLatch completion = finished;
            delegate.execute(() -> {
                try { job.run(); }
                finally { completion.countDown(); }
            });
        }
        @Override public void close() { delegate.close(); }
    }
}
