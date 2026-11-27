package com.book.offset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.book.dto.BookExtractionResult;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Exercises the actual post-save API, PDF layout detector, and independently committed writer together. */
@DataJpaTest(showSql = false, properties = { "spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
@Import({ BookPageOffsetService.class, SavedBookTopicSelector.class, ChapterOpeningDetector.class,
        PageOffsetAssumptionValidator.class, TopicPageOffsetWriter.class })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookPageOffsetFlowTests {
    @Autowired BookPageOffsetService service;
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;
    @Autowired JdbcTemplate jdbc;

    @Test
    void passesTheSavedNestedResponseAndCommitsTheDetectedOffsetThenReusesIt() throws Exception {
        BookExtractionResult saved = savedBook("FlowBook", 23, topic("Real Numbers", 1, 3));
        Long topicId = saved.topicDetails().getFirst().getTopicId();
        TopicDetails original = topics.findById(topicId).orElseThrow();
        LocalDateTime created = original.getCreatedAt();
        LocalDateTime updated = original.getUpdatedAt();
        byte[] pdf = syntheticPdf();

        // This is the same call made immediately after extractionService.extract(...) succeeds.
        BookExtractionResult completed = service.populatePdfPages(saved, pdf);

        assertThat(completed.pageOffset().bookId()).isEqualTo(saved.bookDetails().getBookId());
        assertThat(completed.pageOffset().selectedTopicId()).isEqualTo(topicId);
        assertThat(completed.pageOffset().detection().status()).isEqualTo(ChapterDetection.Status.found);
        assertThat(completed.pageOffset().detection().pdfPageIndex()).isEqualTo(20);
        assertThat(completed.pageOffset().detection().pdfPageNumber()).isEqualTo(21);
        assertThat(completed.pageOffset().offsetNumber()).isEqualTo(20);
        assertThat(completed.pageOffset().update().status()).isEqualTo(BookPageOffsetResult.UpdateStatus.updated);
        assertThat(completed.pageOffset().update().topicRecordsUpdated()).isEqualTo(1);
        assertThat(completed.topicDetails()).hasSize(1);
        assertThat(completed.topicDetails().getFirst().getStartPage()).isEqualTo(21);
        assertThat(completed.topicDetails().getFirst().getEndPage()).isEqualTo(23);
        assertThat(saved.topicDetails().getFirst().getStartPage()).isNull();

        BookExtractionResult retry = service.populatePdfPages(completed, pdf);
        assertThat(retry.pageOffset().detection().status()).isEqualTo(ChapterDetection.Status.found);
        assertThat(retry.pageOffset().update().status()).isEqualTo(BookPageOffsetResult.UpdateStatus.unchanged);
        assertThat(retry.pageOffset().update().topicRecordsUpdated()).isZero();
        assertThat(retry.pageOffset().update().offsetEntryStatus()).isEqualTo("reused");
        assertThat(retry.pageOffset().update().offsetEntryId()).isEqualTo(completed.pageOffset().update().offsetEntryId());
        assertThat(retry.topicDetails()).hasSize(1).extracting(TopicDetails::getTopicName).containsExactly("Real Numbers");
        List<TopicDetails> database = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(saved.bookDetails().getBookId());
        assertThat(database).hasSize(2);
        assertThat(database.getFirst().getStartPage()).isEqualTo(21);
        assertThat(database.getFirst().getEndPage()).isEqualTo(23);
        assertThat(database.getFirst().getPrintedStartPage()).isEqualTo(1);
        assertThat(database.getFirst().getPrintedEndPage()).isEqualTo(3);
        assertThat(database.getFirst().getCreatedAt()).isEqualTo(created);
        assertThat(database.getFirst().getUpdatedAt()).isEqualTo(updated);
        assertThat(database.getLast().getTopicName()).isEqualTo("FlowBook_offset");
        assertThat(database.getLast().getDetails()).isEqualTo("FlowBook_offset detail");
        assertThat(database.getLast().getStartPage()).isEqualTo(1);
        assertThat(database.getLast().getEndPage()).isEqualTo(20);
    }

    @Test
    void insertFailureIsReportedSeparatelyAndNeverReturnsRolledBackPages() throws Exception {
        BookExtractionResult saved = savedBook("FlowRejectedMetadata", 23, topic("Real Numbers", 1, 3));
        Long topicId = saved.topicDetails().getFirst().getTopicId();
        byte[] pdf = syntheticPdf();
        jdbc.execute("alter table pdf_file_topic_details add constraint reject_flow_metadata check (topic_name <> 'FlowRejectedMetadata_offset')");
        try {
            BookExtractionResult result = service.populatePdfPages(saved, pdf);
            assertThat(result.pageOffset().detection().status()).isEqualTo(ChapterDetection.Status.found);
            assertThat(result.pageOffset().offsetNumber()).isEqualTo(20);
            assertThat(result.pageOffset().update().status()).isEqualTo(BookPageOffsetResult.UpdateStatus.failed);
            assertThat(result.pageOffset().update().topicRecordsUpdated()).isZero();
            assertThat(result.pageOffset().update().offsetEntryId()).isNull();
            assertThat(result.pageOffset().update().issues()).extracting(BookPageOffsetResult.Issue::code)
                    .contains("persistence_failed");
            assertThat(result.topicDetails()).hasSize(1);
            assertThat(result.topicDetails().getFirst().getStartPage()).isNull();
            assertThat(result.topicDetails().getFirst().getEndPage()).isNull();
            assertThat(saved.topicDetails().getFirst().getStartPage()).isNull();
            TopicDetails database = topics.findById(topicId).orElseThrow();
            assertThat(database.getStartPage()).isNull();
            assertThat(database.getEndPage()).isNull();
            assertThat(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(saved.bookDetails().getBookId())).hasSize(1);
        } finally {
            jdbc.execute("alter table pdf_file_topic_details drop constraint reject_flow_metadata");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "book.contents.fixture", matches = ".+")
    void mapsSavedThinkJavaSourceRangesUsingItsActualChapterOpening() throws Exception {
        byte[] pdf = Files.readAllBytes(Path.of(System.getProperty("book.contents.fixture")));
        int pages;
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            pages = document.getNumberOfPages();
        }
        // Complete response for this saved book: both real rows, not an incomplete subset of a larger DB set.
        BookExtractionResult saved = savedBook("FlowThinkJava", pages,
                topic("Computer Programming", 1, 16), topic("Index", 341, 349));

        BookExtractionResult result = service.populatePdfPages(saved, pdf);

        assertThat(result.pageOffset().detection().status()).withFailMessage(result.pageOffset().toString())
                .isEqualTo(ChapterDetection.Status.found);
        assertThat(result.pageOffset().detection().pdfPageNumber()).isEqualTo(23);
        assertThat(result.pageOffset().offsetNumber()).isEqualTo(22);
        assertThat(result.pageOffset().update().status()).withFailMessage(result.pageOffset().toString())
                .isEqualTo(BookPageOffsetResult.UpdateStatus.updated);
        assertThat(result.topicDetails()).extracting(TopicDetails::getStartPage).containsExactly(23, 363);
        assertThat(result.topicDetails()).extracting(TopicDetails::getEndPage).containsExactly(38, 372);
        assertThat(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(saved.bookDetails().getBookId())).hasSize(3);
    }

    private BookExtractionResult savedBook(String name, int pageCount, TopicDetails... realTopics) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Book Writer");
        BookDetails book = new BookDetails();
        book.setBookName(name);
        book.setBookDetails("Short description");
        book.setExtractionId(UUID.randomUUID().toString());
        book.setTotalPages(pageCount);
        book.setAuthorDetails(author);
        for (TopicDetails topic : realTopics) book.addTopic(topic);
        BookDetails saved = books.saveAndFlush(book);
        return new BookExtractionResult(saved.getAuthorDetails(), saved, List.copyOf(saved.getTopics()));
    }

    private static TopicDetails topic(String name, int start, int end) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicName(name);
        topic.setDetails("Subtopics");
        topic.setPrintedStartPage(start);
        topic.setPrintedEndPage(end);
        return topic;
    }

    private static byte[] syntheticPdf() throws Exception {
        String body = "This chapter introduces the natural numbers and explains their properties using several useful mathematical examples.";
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int number = 1; number <= 23; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    if (number == 20) {
                        draw(stream, "Contents", 24, 700);
                        draw(stream, "Real Numbers ......... 1", 12, 650);
                        draw(stream, "Whole Numbers ......... 17", 12, 625);
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

    private static void draw(PDPageContentStream stream, String text, int fontSize, int y) throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), fontSize);
        stream.newLineAtOffset(50, y);
        stream.showText(text);
        stream.endText();
    }
}
