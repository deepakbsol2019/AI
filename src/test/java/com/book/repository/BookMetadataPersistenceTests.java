package com.book.repository;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.book.download.pdf.BookPdfMerger;
import com.book.download.pdf.TopicPdfSource;
import com.book.download.service.impl.DownloadServiceImpl;
import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse.BookResult;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;
import com.book.entity.TopicDetails;
import com.book.metadata.dto.MetadataPatchRequest;
import com.book.metadata.service.BookMetadataService;
import com.book.search.repository.BookSearchQueryRepository;
import com.book.search.service.impl.SearchServiceImpl;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql = false, properties = {"debug=false", "spring.jpa.show-sql=false"})
@Import(BookMetadataService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookMetadataPersistenceTests {
    @Autowired BookMetadataService metadata;
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path storage;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void individualFieldsPersistAndRefetchFullParagraphsWithoutChangingContentBoundariesOrPaths() {
        BookDetails saved = fixture(false);
        BookResult before = refetch(saved.getBookId());
        String text = "  First paragraph.\n\nSecond paragraph, with full text.  ";
        metadata.patchBook(saved.getBookId(), request("bookDetails", text));
        metadata.patchBook(saved.getBookId(), request("bookType", "  Physics  "));
        metadata.patchBook(saved.getBookId(), request("authorName", "  Updated author  "));
        Long topicId = before.topics().getFirst().topicId();
        metadata.patchTopic(saved.getBookId(), topicId, request("topicName", "  Updated chapter  "));
        BookResult confirmed = metadata.patchTopic(saved.getBookId(), topicId, request("topicDetails", text));
        BookResult after = refetch(saved.getBookId());
        assertThat(after).isEqualTo(confirmed);
        assertThat(after.bookName()).isEqualTo(before.bookName());
        assertThat(after.bookType()).isEqualTo("Physics");
        assertThat(after.authorName()).isEqualTo("Updated author");
        assertThat(after.bookDetails()).isEqualTo(text);
        assertThat(after.totalPages()).isEqualTo(before.totalPages());
        assertThat(after.topics().getFirst().topicDetails()).isEqualTo(text);
        assertThat(after.topics().getFirst().topicName()).isEqualTo("Updated chapter");
        assertThat(after.topics().getFirst().startPage()).isEqualTo(before.topics().getFirst().startPage());
        assertThat(after.topics().getFirst().endPage()).isEqualTo(before.topics().getFirst().endPage());
        assertThat(after.topics().getFirst().printedStartPage()).isEqualTo(before.topics().getFirst().printedStartPage());
        assertThat(topics.findById(topicId).orElseThrow().getPdfPath()).isEqualTo("preserved/chapter.pdf");
    }

    @Test
    void clearingIsExplicitAndValidationFailureRollsBackWithoutSilentlyNormalizingOverlongType() {
        Long id = fixture(false).getBookId();
        BookResult initial = refetch(id);
        metadata.patchBook(id, request("bookDetails", null));
        metadata.patchBook(id, request("authorName", null));
        metadata.patchBook(id, request("bookType", "  "));
        Long topicId = initial.topics().getFirst().topicId();
        metadata.patchTopic(id, topicId, request("topicDetails", ""));
        BookResult before = refetch(id);
        assertThat(before.bookName()).isEqualTo(initial.bookName());
        assertThat(before.bookDetails()).isNull();
        assertThat(before.authorName()).isNull();
        assertThat(before.bookType()).isEqualTo("Unknown");
        assertThat(before.topics().getFirst().topicDetails()).isEmpty();
        assertStatus(() -> metadata.patchBook(id, request("bookType", "x".repeat(121))), HttpStatus.UNPROCESSABLE_CONTENT);
        assertStatus(() -> metadata.patchBook(id, request("authorName", "x".repeat(201))), HttpStatus.UNPROCESSABLE_CONTENT);
        assertStatus(() -> metadata.patchTopic(id, topicId, request("topicName", "x".repeat(256))), HttpStatus.UNPROCESSABLE_CONTENT);
        assertStatus(() -> metadata.patchTopic(id, topicId, request("topicName", "Reserved_offset")), HttpStatus.CONFLICT);
        assertThat(refetch(id)).isEqualTo(before);
    }

    @Test
    void longtextDescriptionsHaveNoInventedShortLimitOrParagraphTruncation() {
        Long id = fixture(false).getBookId();
        Long topicId = refetch(id).topics().getFirst().topicId();
        String text = "A".repeat(70_000) + "\n\nFinal complete paragraph.";
        metadata.patchBook(id, request("bookDetails", text));
        metadata.patchTopic(id, topicId, request("topicDetails", text));
        assertThat(refetch(id).bookDetails()).isEqualTo(text);
        assertThat(refetch(id).topics().getFirst().topicDetails()).isEqualTo(text);
    }

    @Test
    void duplicateRenameAndInvalidReservedNameRollBackWhileSameCurrentNameIsAllowed() {
        Long id = fixture(false).getBookId();
        BookDetails other = fixture(false);
        BookResult before = refetch(id);
        metadata.patchBook(id, request("bookName", "  " + before.bookName() + "  "));
        assertStatus(() -> metadata.patchBook(id, request("bookName", other.getBookName().toUpperCase())), HttpStatus.CONFLICT);
        assertStatus(() -> metadata.patchBook(id, request("bookName", "  ")), HttpStatus.UNPROCESSABLE_CONTENT);
        assertStatus(() -> metadata.patchBook(id, request("bookName", "x".repeat(249))), HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refetch(id)).isEqualTo(before);
    }

    @Test
    void sharedAuthorChangesAreCopiedSoOtherBooksKeepTheirOriginalCreditedName() {
        BookDetails first = fixture(false);
        Long authorId = first.getAuthorDetails().getAuthorId();
        Long otherId = tx().execute(status -> {
            BookDetails other = newBook();
            other.setAuthorDetails(entityManager.find(AuthorDetails.class, authorId));
            return books.saveAndFlush(other).getBookId();
        });
        BookResult result = metadata.patchBook(first.getBookId(), request("authorName", "Only this book"));
        assertThat(result.authorName()).isEqualTo("Only this book");
        assertThat(result.authorDetails().authorId()).isNotEqualTo(authorId);
        assertThat(refetch(otherId).authorName()).isEqualTo("Original author");
        assertThat(refetch(otherId).authorDetails().authorId()).isEqualTo(authorId);
    }

    @Test
    void canonicalBookRenameKeepsReservedOffsetAndSourcePdfReadableWithoutFileChanges() throws Exception {
        Long id = fixture(true).getBookId();
        byte[] original = pdf();
        Path path = storage.resolve("original-unchanged.pdf"); Files.write(path, original);
        tx().executeWithoutResult(status -> books.findById(id).orElseThrow().getTopics().getFirst().setPdfPath(path.toString()));
        String renamed = "Renamed " + UUID.randomUUID();
        BookResult result = metadata.patchBook(id, request("bookName", renamed));
        assertThat(result.topics()).singleElement().satisfies(offset -> {
            assertThat(offset.topicName()).isEqualTo(renamed + "_offset");
            assertThat(offset.topicDetails()).isEqualTo(renamed + "_offset detail");
        });
        assertThat(Files.readAllBytes(path)).isEqualTo(original);
        assertThat(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(id).getFirst().getPdfPath()).isEqualTo(path.toString());
        assertFullBookReadable(id);
        Long offsetId = result.topics().getFirst().topicId();
        assertStatus(() -> metadata.patchTopic(id, offsetId, request("topicName", "No")), HttpStatus.CONFLICT);
        assertStatus(() -> metadata.patchTopic(id, offsetId, request("topicDetails", "No")), HttpStatus.CONFLICT);
        assertThat(refetch(id)).isEqualTo(result);
    }

    @Test
    void legacyWholeBookDerivedFieldsFollowBookEditsAndKeepTheExistingPdfPath() throws Exception {
        Long id = fixture(true).getBookId();
        Path path = storage.resolve("legacy-original.pdf"); Files.write(path, pdf());
        tx().executeWithoutResult(status -> {
            BookDetails book = books.findById(id).orElseThrow(); TopicDetails technical = book.getTopics().getFirst();
            technical.setTopicName(book.getBookName()); technical.setDetails(book.getBookDetails()); technical.setPdfPath(path.toString());
        });
        metadata.patchBook(id, request("bookDetails", "Changed\n\nbook description"));
        BookResult result = metadata.patchBook(id, request("bookName", "Legacy renamed " + UUID.randomUUID()));
        assertThat(result.topics().getFirst().topicName()).isEqualTo(result.bookName());
        assertThat(result.topics().getFirst().topicDetails()).isEqualTo(result.bookDetails());
        assertFullBookReadable(id);
        assertStatus(() -> metadata.patchTopic(id, result.topics().getFirst().topicId(), request("topicDetails", "No")), HttpStatus.CONFLICT);
    }

    @Test
    void pendingUploadRejectsEveryFieldWhileCompletedAndLegacyBooksRemainEditable() {
        Long id = fixture(false).getBookId();
        tx().executeWithoutResult(status -> {
            BookUploadStatus pending = new BookUploadStatus(); pending.setStatus("inprogress"); pending.setMessage("Processing");
            entityManager.persist(pending); books.findById(id).orElseThrow().setBookUploadStatus(pending);
        });
        BookResult before = refetch(id);
        for (String field : new String[]{"bookName", "bookType", "authorName", "bookDetails"})
            assertStatus(() -> metadata.patchBook(id, request(field, "Draft")), HttpStatus.CONFLICT);
        for (String field : new String[]{"topicName", "topicDetails"})
            assertStatus(() -> metadata.patchTopic(id, before.topics().getFirst().topicId(), request(field, "Draft")), HttpStatus.CONFLICT);
        assertThat(refetch(id)).isEqualTo(before);
        tx().executeWithoutResult(status -> books.findById(id).orElseThrow().getBookUploadStatus().setStatus("Success"));
        assertThat(metadata.patchBook(id, request("bookType", "Poetry")).bookType()).isEqualTo("Poetry");
    }

    @Test
    void concurrentPerFieldSavesRetainBothChanges() throws Exception {
        Long id = fixture(false).getBookId();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var type = executor.submit(() -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return metadata.patchBook(id, request("bookType", "Chemistry")); });
            var description = executor.submit(() -> { ready.countDown(); start.await(10, TimeUnit.SECONDS); return metadata.patchBook(id, request("bookDetails", "Concurrent description")); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            type.get(20, TimeUnit.SECONDS); description.get(20, TimeUnit.SECONDS);
        }
        assertThat(refetch(id).bookType()).isEqualTo("Chemistry");
        assertThat(refetch(id).bookDetails()).isEqualTo("Concurrent description");
    }

    private BookDetails fixture(boolean wholeBook) {
        BookDetails book = newBook();
        if (!wholeBook) {
            TopicDetails chapter = topic("Original chapter", "Original topic description", 2, 2, "preserved/chapter.pdf");
            chapter.setPrintedStartPage(1); chapter.setPrintedEndPage(1); book.addTopic(chapter);
        }
        book.addTopic(topic(book.getBookName() + "_offset", book.getBookName() + "_offset detail", 1, wholeBook ? 2 : 1, "preserved/offset.pdf"));
        return books.saveAndFlush(book);
    }

    private static BookDetails newBook() {
        AuthorDetails author = new AuthorDetails(); author.setAuthorName("Original author");
        BookDetails book = new BookDetails(); book.setBookName("Metadata " + UUID.randomUUID()); book.setBookType("History");
        book.setBookDetails("Original book description"); book.setExtractionId(UUID.randomUUID().toString()); book.setAuthorDetails(author); book.setTotalPages(2);
        return book;
    }

    private static TopicDetails topic(String name, String details, int start, int end, String path) {
        TopicDetails topic = new TopicDetails(); topic.setTopicName(name); topic.setDetails(details); topic.setStartPage(start); topic.setEndPage(end); topic.setPdfPath(path); return topic;
    }

    private MetadataPatchRequest request(String field, String value) {
        return mapper.readValue(mapper.writeValueAsString(Collections.singletonMap(field, value)), MetadataPatchRequest.class);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }

    private BookResult refetch(Long id) {
        return tx().execute(status -> {
            BookDetails book = books.findById(id).orElseThrow();
            return new SearchServiceImpl(new BookSearchQueryRepository(entityManager)).searchBooks(
                    new BookSearchCriteria(book.getBookName(), null, null, null, null, null, 0, 100))
                    .books().stream().filter(result -> id.equals(result.bookId())).findFirst().orElseThrow();
        });
    }

    private void assertFullBookReadable(Long id) throws Exception {
        byte[] bytes = tx().execute(status -> new DownloadServiceImpl(books, topics, new TopicPdfSource(storage.toString()), null, new BookPdfMerger()).downloadBook(id));
        try (PDDocument document = Loader.loadPDF(bytes)) { assertThat(document.getNumberOfPages()).isEqualTo(2); }
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage()); document.addPage(new PDPage()); document.save(output); return output.toByteArray();
        }
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class, failure -> assertThat(failure.getStatusCode()).isEqualTo(status));
    }
}
