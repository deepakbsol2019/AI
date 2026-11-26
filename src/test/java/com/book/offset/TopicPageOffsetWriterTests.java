package com.book.offset;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import com.book.offset.TopicPageOffsetWriter.OffsetEntryStatus;
import com.book.offset.TopicPageOffsetWriter.OffsetWriteException;
import com.book.offset.TopicPageOffsetWriter.SourceSnapshot;
import com.book.offset.TopicPageOffsetWriter.UpdateStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false, properties = { "spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
@Import(TopicPageOffsetWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TopicPageOffsetWriterTests {
    @Autowired TopicPageOffsetWriter writer;
    @Autowired BookDetailsRepository books;
    @Autowired TopicDetailsRepository topics;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fillsEachMissingColumnAndPreservesSourcesAuditAndOtherBooks() {
        BookDetails book = book("Mapped", 35, topic("Opening", 1, 10), topic("Second", 11, 15));
        BookDetails other = book("Other", 50, topic("Other chapter", 1, 10));
        List<TopicDetails> before = real(book);
        jdbc.update("update topic_details set start_page = ? where topic_id = ?", 21, before.get(0).getTopicId());
        jdbc.update("update topic_details set end_page = ?, pdf_path = ? where topic_id = ?", 35,
                "existing.pdf", before.get(1).getTopicId());
        before = real(book);
        List<LocalDateTime> created = before.stream().map(TopicDetails::getCreatedAt).toList();
        List<LocalDateTime> updated = before.stream().map(TopicDetails::getUpdatedAt).toList();

        var result = writer.apply(book.getBookId(), book.getBookName(), ids(before), 20, 35, snapshots(before));

        assertThat(result.status()).isEqualTo(UpdateStatus.updated);
        assertThat(result.recordsUpdated()).isEqualTo(2);
        assertThat(result.realTopics()).extracting(TopicDetails::getStartPage).containsExactly(21, 31);
        assertThat(result.realTopics()).extracting(TopicDetails::getEndPage).containsExactly(30, 35);
        List<TopicDetails> after = real(book);
        assertThat(after).extracting(TopicDetails::getPrintedStartPage).containsExactly(1, 11);
        assertThat(after).extracting(TopicDetails::getPrintedEndPage).containsExactly(10, 15);
        assertThat(after).extracting(TopicDetails::getCreatedAt).containsExactlyElementsOf(created);
        assertThat(after).extracting(TopicDetails::getUpdatedAt).containsExactlyElementsOf(updated);
        assertThat(after.get(1).getPdfPath()).isEqualTo("existing.pdf");
        assertThat(after).extracting(TopicDetails::getDetails).containsExactly("Details for Opening", "Details for Second");
        assertThat(real(other).getFirst().getStartPage()).isNull();
        assertThat(real(other).getFirst().getEndPage()).isNull();
        assertThat(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(other.getBookId())).hasSize(1);
    }

    @Test
    void createsExactOffsetEntryAndReusesItWithoutApplyingOffsetTwice() {
        BookDetails book = book("Retry", 50, topic("Opening", 1, 10), topic("Second", 11, 30));
        List<TopicDetails> initial = real(book);
        var first = writer.apply(book.getBookId(), book.getBookName(), ids(initial), 20, 50);
        var retry = writer.apply(book.getBookId(), book.getBookName(), ids(initial), 20, 50);

        assertThat(first.offsetEntryStatus()).isEqualTo(OffsetEntryStatus.created);
        assertThat(retry.offsetEntryStatus()).isEqualTo(OffsetEntryStatus.reused);
        assertThat(retry.status()).isEqualTo(UpdateStatus.unchanged);
        assertThat(retry.recordsUpdated()).isZero();
        assertThat(retry.offsetEntryId()).isEqualTo(first.offsetEntryId());
        assertThat(retry.realTopics()).hasSize(2).extracting(TopicDetails::getStartPage).containsExactly(21, 31);
        List<TopicDetails> all = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(book.getBookId());
        assertThat(all).hasSize(3);
        TopicDetails metadata = all.getLast();
        assertThat(metadata.getTopicId()).isNotNull();
        assertThat(metadata.getTopicName()).isEqualTo("Retry_offset");
        assertThat(metadata.getDetails()).isEqualTo("Retry_offset detail");
        assertThat(metadata.getStartPage()).isEqualTo(1);
        assertThat(metadata.getEndPage()).isEqualTo(20);
        assertThat(metadata.getPrintedStartPage()).isNull();
        assertThat(metadata.getPrintedEndPage()).isNull();
        assertThat(metadata.getPdfPath()).isNull();
        assertThat(metadata.getCreatedAt()).isNotNull();
        assertThat(metadata.getUpdatedAt()).isNotNull();
        assertThat(metadata.getBookDetails().getBookId()).isEqualTo(book.getBookId());
        Map<String, Object> raw = jdbc.queryForMap("select printed_start_page, printed_end_page, pdf_path from topic_details where topic_id = ?",
                metadata.getTopicId());
        assertThat(raw.values()).containsOnlyNulls();
    }

    @Test
    void offsetZeroKeepsAnEmptyPrefixOutsideRealTopicValidation() {
        BookDetails book = book("NoPrefix", 4, topic("Opening", 1, 4));
        Set<Long> expected = ids(real(book));
        var first = writer.apply(book.getBookId(), book.getBookName(), expected, 0, 4);
        var retry = writer.apply(book.getBookId(), book.getBookName(), expected, 0, 4);
        TopicDetails metadata = topics.findById(first.offsetEntryId()).orElseThrow();
        assertThat(metadata.getStartPage()).isEqualTo(1);
        assertThat(metadata.getEndPage()).isZero();
        assertThat(retry.status()).isEqualTo(UpdateStatus.unchanged);
        assertThat(retry.realTopics()).hasSize(1);
        assertThat(retry.realTopics().getFirst().getStartPage()).isEqualTo(1);
        assertThat(retry.realTopics().getFirst().getEndPage()).isEqualTo(4);
    }

    @Test
    void finalTopicIncludesUnnumberedClosingPagesWithoutChangingItsPrintedRange() {
        BookDetails book = book("ClosingPages", 10, topic("Opening", 1, 2), topic("Final chapter", 3, 5));
        List<TopicDetails> before = real(book);
        var result = writer.apply(book.getBookId(), book.getBookName(), ids(before), 2, 10);
        assertThat(result.realTopics()).extracting(TopicDetails::getStartPage).containsExactly(3, 5);
        assertThat(result.realTopics()).extracting(TopicDetails::getEndPage).containsExactly(4, 10);
        assertThat(result.realTopics()).extracting(TopicDetails::getPrintedEndPage).containsExactly(2, 5);
        assertThat(writer.apply(book.getBookId(), book.getBookName(), ids(before), 2, 10).status())
                .isEqualTo(UpdateStatus.unchanged);
    }

    @Test
    void offsetRetryPreservesItsUploadedPdfPath() {
        BookDetails book = book("UploadedPrefix", 10, topic("Opening", 1, 8));
        Set<Long> expected = ids(real(book));
        var first = writer.apply(book.getBookId(), book.getBookName(), expected, 2, 10);
        String path = "C:/books/UploadedPrefix/UploadedPrefix_offset.pdf";
        jdbc.update("update topic_details set pdf_path = ? where topic_id = ?", path, first.offsetEntryId());
        var retry = writer.apply(book.getBookId(), book.getBookName(), expected, 2, 10);
        assertThat(retry.offsetEntryStatus()).isEqualTo(OffsetEntryStatus.reused);
        assertThat(topics.findById(first.offsetEntryId()).orElseThrow().getPdfPath()).isEqualTo(path);
    }

    @Test
    void mapsPrintedPageThreeToPhysicalSixWhileKeepingFivePagesInTheOffsetPdf() {
        BookDetails book = book("NonOneNumbering", 12,
                topic("Introduction", 3, 5), topic("Next chapter", 6, 9));
        List<TopicDetails> before = real(book);
        var result = writer.apply(book.getBookId(), book.getBookName(), ids(before), 5, 12, snapshots(before), 3);

        assertThat(result.realTopics()).extracting(TopicDetails::getStartPage).containsExactly(6, 9);
        assertThat(result.realTopics()).extracting(TopicDetails::getEndPage).containsExactly(8, 12);
        assertThat(result.realTopics()).extracting(TopicDetails::getPrintedStartPage).containsExactly(3, 6);
        assertThat(result.realTopics()).extracting(TopicDetails::getPrintedEndPage).containsExactly(5, 9);
        assertThat(topics.findById(result.offsetEntryId()).orElseThrow().getEndPage()).isEqualTo(5);

        var retry = writer.apply(book.getBookId(), book.getBookName(), ids(before), 5, 12, snapshots(before), 3);
        assertThat(retry.status()).isEqualTo(UpdateStatus.unchanged);
        assertThat(retry.recordsUpdated()).isZero();
        assertThat(retry.offsetEntryId()).isEqualTo(result.offsetEntryId());
        assertThat(retry.offsetEntryStatus()).isEqualTo(OffsetEntryStatus.reused);
    }

    @Test
    void inconsistentPrefixAndPrintedTranslationPreventAnyPartialUpdate() {
        BookDetails book = book("MismatchedPrefix", 12,
                topic("Introduction", 3, 5), topic("Next chapter", 6, 9));
        List<TopicDetails> before = real(book);

        assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), ids(before), 3, 12,
                snapshots(before), 3)).isInstanceOfSatisfying(OffsetWriteException.class, failure ->
                        assertThat(failure.issues()).extracting(TopicPageOffsetWriter.ValidationIssue::code)
                                .contains("prefix_mapping_mismatch"));

        assertUnchanged(book, 2);
    }

    @Test
    void permitsNegativePrintedTranslationWhenPhysicalRangesRemainValid() {
        BookDetails book = book("NumberingFromHundred", 4, topic("Opening", 100, 103));
        List<TopicDetails> before = real(book);

        var result = writer.apply(book.getBookId(), book.getBookName(), ids(before), 0, 4,
                snapshots(before), -99);

        assertThat(result.realTopics()).singleElement().satisfies(topic -> {
            assertThat(topic.getStartPage()).isEqualTo(1);
            assertThat(topic.getEndPage()).isEqualTo(4);
            assertThat(topic.getPrintedStartPage()).isEqualTo(100);
            assertThat(topic.getPrintedEndPage()).isEqualTo(103);
        });
        TopicDetails metadata = topics.findById(result.offsetEntryId()).orElseThrow();
        assertThat(metadata.getStartPage()).isEqualTo(1);
        assertThat(metadata.getEndPage()).isZero();
    }

    @Test
    void conflictInAnyDestinationPreventsEveryPageWrite() {
        BookDetails book = book("Conflict", 50, topic("Opening", 1, 10), topic("Second", 11, 20));
        List<TopicDetails> before = real(book);
        jdbc.update("update topic_details set end_page = ? where topic_id = ?", 49, before.getLast().getTopicId());
        assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), ids(before), 20, 50))
                .isInstanceOfSatisfying(OffsetWriteException.class, ex ->
                        assertThat(ex.issues()).anySatisfy(issue -> {
                            assertThat(issue.topicId()).isEqualTo(before.getLast().getTopicId());
                            assertThat(issue.code()).isEqualTo("end_page_conflict");
                        }));
        assertThat(real(book).getFirst().getStartPage()).isNull();
        assertThat(real(book).getLast().getStartPage()).isNull();
        assertThat(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(book.getBookId())).hasSize(2);
    }

    @Test
    void reportsMissingReversedAndOutOfRangePrintedSourcesWithoutPartialWrites() {
        BookDetails book = book("BadSources", 50, topic("Opening", 1, 10), topic("Missing", null, 20),
                topic("Reversed", 30, 20), topic("Past PDF", 40, 50));
        List<TopicDetails> before = real(book);
        assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), ids(before), 20, 50))
                .isInstanceOfSatisfying(OffsetWriteException.class, ex -> {
                    assertThat(ex.issues()).extracting(TopicPageOffsetWriter.ValidationIssue::topicId)
                            .contains(before.get(1).getTopicId(), before.get(2).getTopicId(), before.get(3).getTopicId());
                });
        assertUnchanged(book, 4);
    }

    @Test
    void requiresTheCompleteCurrentBookTopicSetAndFreshSourceSnapshot() {
        BookDetails book = book("Complete", 50, topic("Opening", 1, 10), topic("Second", 11, 20));
        BookDetails other = book("Foreign", 50, topic("Foreign opening", 1, 10));
        List<TopicDetails> initial = real(book);
        assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), Set.of(initial.getFirst().getTopicId()), 20, 50))
                .isInstanceOf(OffsetWriteException.class);
        Set<Long> mixed = new java.util.HashSet<>(ids(initial));
        mixed.add(real(other).getFirst().getTopicId());
        assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), mixed, 20, 50))
                .isInstanceOf(OffsetWriteException.class);
        Map<Long, SourceSnapshot> snapshots = snapshots(initial);
        jdbc.update("update topic_details set topic_name = ? where topic_id = ?", "Renamed", initial.getFirst().getTopicId());
        assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), ids(initial), 20, 50, snapshots))
                .isInstanceOfSatisfying(OffsetWriteException.class, ex ->
                        assertThat(ex.issues()).extracting(TopicPageOffsetWriter.ValidationIssue::code).contains("stale_topic_response"));
        assertUnchanged(book, 2);
        assertUnchanged(other, 1);
    }

    @Test
    void rejectsConflictingAndDuplicateReservedEntriesBeforeAnyTopicWrite() {
        BookDetails conflicting = book("ReservedConflict", 50, topic("Opening", 1, 10));
        Set<Long> realIds = ids(real(conflicting));
        TopicDetails bad = metadata(conflicting, 20);
        bad.setDetails("wrong suffix");
        topics.saveAndFlush(bad);
        assertThatThrownBy(() -> writer.apply(conflicting.getBookId(), conflicting.getBookName(), realIds, 20, 50))
                .isInstanceOfSatisfying(OffsetWriteException.class, ex ->
                        assertThat(ex.issues()).extracting(TopicPageOffsetWriter.ValidationIssue::code).contains("conflicting_offset_entry"));
        assertThat(real(conflicting).getFirst().getStartPage()).isNull();

        BookDetails duplicate = book("ReservedDuplicate", 50, topic("Opening", 1, 10));
        Set<Long> duplicateIds = ids(real(duplicate));
        topics.saveAndFlush(metadata(duplicate, 20));
        topics.saveAndFlush(metadata(duplicate, 20));
        assertThatThrownBy(() -> writer.apply(duplicate.getBookId(), duplicate.getBookName(), duplicateIds, 20, 50))
                .isInstanceOfSatisfying(OffsetWriteException.class, ex ->
                        assertThat(ex.issues()).extracting(TopicPageOffsetWriter.ValidationIssue::code).contains("duplicate_offset_entry"));
        assertThat(real(duplicate).getFirst().getStartPage()).isNull();
    }

    @Test
    void metadataInsertFailureRollsBackAllEarlierPageUpdates() {
        BookDetails book = book("RejectedMetadata", 50, topic("Opening", 1, 10), topic("Second", 11, 20));
        Set<Long> expected = ids(real(book));
        jdbc.execute("alter table topic_details add constraint reject_writer_metadata check (topic_name <> 'RejectedMetadata_offset')");
        try {
            assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), expected, 20, 50))
                    .isInstanceOf(RuntimeException.class);
            assertUnchanged(book, 2);
        } finally {
            jdbc.execute("alter table topic_details drop constraint reject_writer_metadata");
        }
    }

    @Test
    void topicUpdateFailureAlsoRollsBackEarlierTopicUpdates() {
        BookDetails book = book("RejectedPage", 50, topic("Opening", 1, 10), topic("Second", 11, 20));
        Set<Long> expected = ids(real(book));
        jdbc.execute("alter table topic_details add constraint reject_writer_page check (book_id <> "
                + book.getBookId() + " or topic_name <> 'Second' or start_page is null)");
        try {
            assertThatThrownBy(() -> writer.apply(book.getBookId(), book.getBookName(), expected, 20, 50))
                    .isInstanceOf(RuntimeException.class);
            assertUnchanged(book, 2);
        } finally {
            jdbc.execute("alter table topic_details drop constraint reject_writer_page");
        }
    }

    @Test
    void concurrentRetriesSerializeBeforeCreatingTheReservedEntry() throws Exception {
        BookDetails book = book("Concurrent", 50, topic("Opening", 1, 10), topic("Second", 11, 20));
        Set<Long> expected = ids(real(book));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<TopicPageOffsetWriter.WriterResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timed out");
                return writer.apply(book.getBookId(), book.getBookName(), expected, 20, 50);
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var first = futures.get(0).get(20, TimeUnit.SECONDS);
            var second = futures.get(1).get(20, TimeUnit.SECONDS);
            assertThat(List.of(first.offsetEntryStatus(), second.offsetEntryStatus()))
                    .containsExactlyInAnyOrder(OffsetEntryStatus.created, OffsetEntryStatus.reused);
            assertThat(first.offsetEntryId()).isEqualTo(second.offsetEntryId());
            assertThat(List.of(first.status(), second.status())).containsExactlyInAnyOrder(UpdateStatus.updated, UpdateStatus.unchanged);
        }
        assertThat(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(book.getBookId())).hasSize(3);
    }

    private BookDetails book(String name, int totalPages, TopicDetails... realTopics) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Writer");
        BookDetails book = new BookDetails();
        book.setBookName(name);
        book.setBookDetails("Book details");
        book.setExtractionId(UUID.randomUUID().toString());
        book.setTotalPages(totalPages);
        book.setAuthorDetails(author);
        for (TopicDetails topic : realTopics) book.addTopic(topic);
        return books.saveAndFlush(book);
    }

    private static TopicDetails topic(String name, Integer printedStart, Integer printedEnd) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicName(name);
        topic.setDetails("Details for " + name);
        topic.setPrintedStartPage(printedStart);
        topic.setPrintedEndPage(printedEnd);
        return topic;
    }

    private static TopicDetails metadata(BookDetails book, int offset) {
        TopicDetails metadata = new TopicDetails();
        metadata.setBookDetails(book);
        metadata.setTopicName(book.getBookName() + "_offset");
        metadata.setDetails(book.getBookName() + "_offset detail");
        metadata.setStartPage(1);
        metadata.setEndPage(offset);
        return metadata;
    }

    private List<TopicDetails> real(BookDetails book) {
        return topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(book.getBookId()).stream()
                .filter(topic -> !(book.getBookName() + "_offset").equals(topic.getTopicName())).toList();
    }

    private static Set<Long> ids(List<TopicDetails> values) {
        return values.stream().map(TopicDetails::getTopicId).collect(Collectors.toSet());
    }

    private static Map<Long, SourceSnapshot> snapshots(List<TopicDetails> values) {
        return values.stream().collect(Collectors.toUnmodifiableMap(TopicDetails::getTopicId, SourceSnapshot::of));
    }

    private void assertUnchanged(BookDetails book, int count) {
        List<TopicDetails> saved = topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(book.getBookId());
        assertThat(saved).hasSize(count).allSatisfy(topic -> {
            assertThat(topic.getStartPage()).isNull();
            assertThat(topic.getEndPage()).isNull();
        });
    }
}
