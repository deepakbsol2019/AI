package com.book.offset;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.book.dto.BookExtractionResult;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.offset.BookPageOffsetResult.UpdateStatus;
import com.book.offset.ChapterDetection.PrintedAnchor;
import com.book.offset.SavedBookTopicSelector.Selection;
import com.book.offset.SavedBookTopicSelector.SelectionException;
import com.book.offset.TopicPageOffsetWriter.OffsetEntryStatus;
import com.book.offset.TopicPageOffsetWriter.SourceSnapshot;
import com.book.offset.TopicPageOffsetWriter.WriterResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookPageOffsetServiceTests {
    private final SavedBookTopicSelector selector = mock(SavedBookTopicSelector.class);
    private final ChapterOpeningDetector detector = mock(ChapterOpeningDetector.class);
    private final TopicPageOffsetWriter writer = mock(TopicPageOffsetWriter.class);
    private final BookPageOffsetService service = new BookPageOffsetService(selector, detector,
            new PageOffsetAssumptionValidator(), writer);
    private final byte[] pdf = { 1, 2, 3 };
    private BookExtractionResult saved;
    private Selection selection;

    @BeforeEach
    void setupSavedResponse() {
        BookDetails book = new BookDetails();
        book.setBookId(7L);
        book.setBookName("Mathematics");
        book.setTotalPages(50);
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(12L);
        topic.setTopicName("Real Numbers");
        topic.setPrintedStartPage(1);
        topic.setPrintedEndPage(25);
        book.addTopic(topic);
        saved = new BookExtractionResult(null, book, List.of(topic));
        selection = new Selection(7L, "Mathematics", 50, topic, List.of(topic), Set.of(12L));
        when(selector.select(saved)).thenReturn(selection);
        when(detector.detect(pdf, "Real Numbers", 1)).thenReturn(found(21, 1));
    }

    @Test
    void usesExistingSavedIdAndResponseThenReturnsCommittedTopicResults() {
        TopicDetails updated = new TopicDetails();
        updated.setTopicId(12L);
        updated.setStartPage(21);
        updated.setEndPage(45);
        when(writer.apply(eq(7L), eq("Mathematics"), eq(Set.of(12L)), eq(20), eq(50), anyMap(), eq(20)))
                .thenReturn(new WriterResult(TopicPageOffsetWriter.UpdateStatus.updated, 1, 26L,
                        OffsetEntryStatus.created, List.of(updated)));

        BookExtractionResult result = service.populatePdfPages(saved, pdf);

        assertThat(result.pageOffset().detection().status()).isEqualTo(ChapterDetection.Status.found);
        assertThat(result.pageOffset().offsetNumber()).isEqualTo(20);
        assertThat(result.pageOffset().printedPageOffset()).isEqualTo(20);
        assertThat(result.pageOffset().selectedTopicId()).isEqualTo(12L);
        assertThat(result.pageOffset().update().status()).isEqualTo(UpdateStatus.updated);
        assertThat(result.pageOffset().update().topicRecordsUpdated()).isEqualTo(1);
        assertThat(result.pageOffset().update().offsetEntryId()).isEqualTo(26L);
        assertThat(result.topicDetails()).singleElement().satisfies(topic -> {
            assertThat(topic.getTopicId()).isEqualTo(12L);
            assertThat(topic.getStartPage()).isEqualTo(21);
            assertThat(topic.getEndPage()).isEqualTo(45);
        });
        assertThat(saved.topicDetails().getFirst().getStartPage()).isNull();
        verify(writer).apply(7L, "Mathematics", Set.of(12L), 20, 50,
                Map.of(12L, new SourceSnapshot(12L, "Real Numbers", 1, 25)), 20);
        verify(detector).detect(pdf, "Real Numbers", 1); // ID 12 never becomes "Chapter 12".
    }

    @Test
    void findingAChapterDoesNotClaimAWriteWhenTheTransactionRollsBack() {
        when(writer.apply(anyLong(), anyString(), anySet(), anyInt(), anyInt(), anyMap(), anyInt()))
                .thenThrow(new IllegalStateException("Simulated metadata insert failure"));
        var result = service.populatePdfPages(saved, pdf);
        assertThat(result.pageOffset().detection().status()).isEqualTo(ChapterDetection.Status.found);
        assertThat(result.pageOffset().update().status()).isEqualTo(UpdateStatus.failed);
        assertThat(result.pageOffset().update().topicRecordsUpdated()).isZero();
        assertThat(result.pageOffset().update().offsetEntryId()).isNull();
    }

    @Test
    void returnsAffectedTopicsForWriterValidationFailures() {
        when(writer.apply(anyLong(), anyString(), anySet(), anyInt(), anyInt(), anyMap(), anyInt()))
                .thenThrow(new TopicPageOffsetWriter.OffsetWriteException(List.of(
                        new TopicPageOffsetWriter.ValidationIssue(12L, "end_page_conflict", "Conflict"))));
        var result = service.populatePdfPages(saved, pdf).pageOffset();
        assertThat(result.update().status()).isEqualTo(UpdateStatus.failed);
        assertThat(result.update().issues().getFirst().topicId()).isEqualTo(12L);
    }

    @Test
    void retryReportsUnchangedAndReusedSeparately() {
        when(writer.apply(anyLong(), anyString(), anySet(), anyInt(), anyInt(), anyMap(), anyInt()))
                .thenReturn(new WriterResult(TopicPageOffsetWriter.UpdateStatus.unchanged, 0, 26L,
                        OffsetEntryStatus.reused, selection.realTopics()));
        var result = service.populatePdfPages(saved, pdf).pageOffset();
        assertThat(result.update().status()).isEqualTo(UpdateStatus.unchanged);
        assertThat(result.update().offsetEntryStatus()).isEqualTo("reused");
    }

    @Test
    void numberingMismatchRetainsDetectionButDoesNotAttemptWrites() {
        when(detector.detect(pdf, "Real Numbers", 1)).thenReturn(found(22, 1));
        var result = service.populatePdfPages(saved, pdf).pageOffset();
        assertThat(result.detection().status()).isEqualTo(ChapterDetection.Status.found);
        assertThat(result.offsetNumber()).isNull();
        assertThat(result.update().status()).isEqualTo(UpdateStatus.not_attempted);
        assertThat(result.update().issues()).extracting(BookPageOffsetResult.Issue::code)
                .contains("printed_anchor_mismatch");
        verifyNoInteractions(writer);
    }

    @Test
    void ambiguousOrUncertainLocationsAreNotPersisted() {
        when(detector.detect(pdf, "Real Numbers", 1)).thenReturn(new ChapterDetection(
                ChapterDetection.Status.ambiguous, null, null, null, null, "unconfirmed",
                List.of("Two plausible openings"), List.of(), 50, List.of()));
        var result = service.populatePdfPages(saved, pdf).pageOffset();
        assertThat(result.detection().pdfPageNumber()).isNull();
        assertThat(result.update().status()).isEqualTo(UpdateStatus.not_attempted);
        verifyNoInteractions(writer);
    }

    @Test
    void invalidNestedNullRecordReportsValidationInsteadOfThrowingWhileCopyingTheResponse() {
        BookExtractionResult bad = new BookExtractionResult(null, saved.bookDetails(), Arrays.asList((TopicDetails) null));
        when(selector.select(bad)).thenThrow(new SelectionException(List.of("Null nested topic")));
        var result = service.populatePdfPages(bad, pdf);
        assertThat(result.pageOffset().detection().status()).isEqualTo(ChapterDetection.Status.insufficient_data);
        assertThat(result.pageOffset().update().status()).isEqualTo(UpdateStatus.not_attempted);
        assertThat(result.topicDetails()).containsExactly((TopicDetails) null);
        verifyNoInteractions(detector, writer);
    }

    @Test
    void separatesFrontMatterLengthFromTheVerifiedPrintedPageTranslation() {
        selection.firstTopic().setPrintedStartPage(3);
        var detection = new ChapterDetection(ChapterDetection.Status.found, 5, 6, "3", null,
                "high: numbered heading and following content", List.of(), List.of(), 50,
                List.of(new PrintedAnchor(6, 3, "visible_folio"), new PrintedAnchor(7, 4, "visible_folio")));
        when(detector.detect(pdf, "Real Numbers", 3)).thenReturn(detection);
        TopicDetails updated = selection.firstTopic().responseCopy();
        updated.setStartPage(6);
        updated.setEndPage(50);
        when(writer.apply(eq(7L), eq("Mathematics"), eq(Set.of(12L)), eq(5), eq(50), anyMap(), eq(3)))
                .thenReturn(new WriterResult(TopicPageOffsetWriter.UpdateStatus.updated, 1, 26L,
                        OffsetEntryStatus.created, List.of(updated)));

        var result = service.populatePdfPages(saved, pdf);

        assertThat(result.pageOffset().offsetNumber()).isEqualTo(5);
        assertThat(result.pageOffset().printedPageOffset()).isEqualTo(3);
        assertThat(result.pageOffset().update().status()).isEqualTo(UpdateStatus.updated);
        verify(writer).apply(7L, "Mathematics", Set.of(12L), 5, 50,
                Map.of(12L, new SourceSnapshot(12L, "Real Numbers", 3, 25)), 3);
    }

    private ChapterDetection found(int anchorPhysical, int anchorPrinted) {
        return new ChapterDetection(ChapterDetection.Status.found, 20, 21, null, null,
                "strong: distinctive heading and following prose", List.of("validated content"), List.of(), 50,
                List.of(new PrintedAnchor(anchorPhysical, anchorPrinted, "visible_folio")));
    }
}
