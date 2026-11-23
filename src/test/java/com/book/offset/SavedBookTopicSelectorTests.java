package com.book.offset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.book.dto.BookExtractionResult;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SavedBookTopicSelectorTests {
    private final BookDetailsRepository books = mock(BookDetailsRepository.class);
    private final TopicDetailsRepository topics = mock(TopicDetailsRepository.class);
    private final SavedBookTopicSelector selector = new SavedBookTopicSelector(books, topics);

    @Test
    void selectsSmallestNumericPersistedIdFromUnsortedNestedResponse() {
        BookDetails book = book(7L, "Book");
        TopicDetails t25 = topic(book, 25L, "Later", 20, 29);
        TopicDetails t12 = topic(book, 12L, "Opening title", 1, 9);
        TopicDetails t19 = topic(book, 19L, "Middle", 10, 19);
        persist(book, t25, t12, t19);

        var selection = selector.select(response(book, t25, t12, t19));

        assertThat(selection.firstTopic()).isNotSameAs(t12);
        assertThat(selection.firstTopic().getTopicId()).isEqualTo(12L);
        assertThat(selection.firstTopic().getTopicName()).isEqualTo("Opening title");
        assertThat(selection.realTopics()).extracting(TopicDetails::getTopicId).containsExactly(12L, 19L, 25L);
        assertThat(selection.realTopicIds()).containsExactlyInAnyOrder(12L, 19L, 25L);
        assertThat(selection.bookId()).isEqualTo(7L);
        verify(books).findById(7L);
        verify(topics).findAllByBookDetailsBookIdOrderByTopicIdAsc(7L);
    }

    @Test
    void excludesOnlyExactCurrentBookOffsetNameBeforeSortingAndCompletenessComparison() {
        BookDetails book = book(7L, "Book");
        TopicDetails metadata = topic(book, 2L, "Book_offset", null, null);
        TopicDetails real = topic(book, 12L, "Opening", 1, 9);
        TopicDetails suffixTitle = topic(book, 19L, "Other_offset", 10, 19);
        persist(book, metadata, real, suffixTitle);

        var withMetadata = selector.select(response(book, metadata, suffixTitle, real));
        var withoutMetadata = selector.select(response(book, suffixTitle, real));

        assertThat(withMetadata.firstTopic().getTopicId()).isEqualTo(real.getTopicId());
        assertThat(withMetadata.realTopics()).extracting(TopicDetails::getTopicId).containsExactly(12L, 19L);
        assertThat(withoutMetadata.realTopicIds()).containsExactlyInAnyOrder(12L, 19L);
    }

    @Test
    void rejectsPartialAndUnexpectedTopicResponseLists() {
        BookDetails book = book(7L, "Book");
        TopicDetails first = topic(book, 12L, "Opening", 1, 9);
        TopicDetails next = topic(book, 19L, "Next", 10, 19);
        persist(book, first, next);

        assertThatThrownBy(() -> selector.select(response(book, first)))
                .isInstanceOf(SavedBookTopicSelector.SelectionException.class)
                .hasMessageContaining("missing topic IDs=[19]");
        TopicDetails extra = topic(book, 25L, "Extra", 20, 29);
        assertThatThrownBy(() -> selector.select(response(book, first, next, extra)))
                .hasMessageContaining("unexpected topic IDs=[25]");
    }

    @Test
    void queriesCurrentBookRowsRatherThanTrustingAnIncompleteCachedBookCollection() {
        BookDetails book = book(7L, "Book");
        TopicDetails first = topic(book, 12L, "Opening", 1, 9);
        TopicDetails concurrentTopic = topic(book, 19L, "Newly saved topic", 10, 19);
        persist(book, first);
        // Simulate a row committed after the book's lazy collection was initialized.
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(7L))
                .thenReturn(List.of(first, concurrentTopic));

        assertThatThrownBy(() -> selector.select(response(book, first)))
                .isInstanceOf(SavedBookTopicSelector.SelectionException.class)
                .hasMessageContaining("missing topic IDs=[19]");
        assertThat(book.getTopics()).containsExactly(first);
    }

    @Test
    void rejectsMixedBookTopicsEvenWhenTheirNameLooksLikeReservedMetadata() {
        BookDetails book = book(7L, "Book");
        BookDetails otherBook = book(8L, "Book");
        TopicDetails first = topic(book, 12L, "Opening", 1, 9);
        persist(book, first);
        TopicDetails foreign = topic(otherBook, 19L, "Book_offset", null, null);

        assertThatThrownBy(() -> selector.select(response(book, first, foreign)))
                .hasMessageContaining("Topic 19").hasMessageContaining("does not belong to book 7");
    }

    @Test
    void rejectsEmptyNullAndMetadataOnlyLists() {
        BookDetails book = book(7L, "Book");
        TopicDetails metadata = topic(book, 2L, "Book_offset", null, null);
        persist(book, metadata);

        assertThatThrownBy(() -> selector.select(response(book)))
                .hasMessageContaining("complete nested topicDetails list");
        assertThatThrownBy(() -> selector.select(new BookExtractionResult(null, book, null)))
                .hasMessageContaining("complete nested topicDetails list");
        assertThatThrownBy(() -> selector.select(response(book, metadata)))
                .hasMessageContaining("no real chapter/topic records");
    }

    @Test
    void rejectsUnsavedDuplicateAndNullTopics() {
        BookDetails book = book(7L, "Book");
        TopicDetails first = topic(book, 12L, "Opening", 1, 9);
        persist(book, first);
        TopicDetails unsaved = topic(book, null, "Unsaved", 10, 19);

        assertThatThrownBy(() -> selector.select(response(book, first, unsaved)))
                .hasMessageContaining("no valid persisted topic ID");
        assertThatThrownBy(() -> selector.select(response(book, first, first)))
                .hasMessageContaining("Duplicate topic ID 12");
        List<TopicDetails> nullEntry = new ArrayList<>();
        nullEntry.add(first);
        nullEntry.add(null);
        assertThatThrownBy(() -> selector.select(new BookExtractionResult(null, book, nullEntry)))
                .hasMessageContaining("A null topic");
    }

    @Test
    void rejectsChangedTitleAndPrintedSourceRangeRatherThanMatchingStaleResponse() {
        BookDetails persistedBook = book(7L, "Book");
        TopicDetails actual = topic(persistedBook, 12L, "Opening", 1, 9);
        persist(persistedBook, actual);
        BookDetails responseBook = book(7L, "Book");
        TopicDetails stale = topic(responseBook, 12L, "Wrong title", 2, 10);

        assertThatThrownBy(() -> selector.select(response(responseBook, stale)))
                .hasMessageContaining("title differs")
                .hasMessageContaining("printed source range differs");
    }

    @Test
    void rejectsUnknownOrUnsavedBooksAndInvalidBookResponseFields() {
        BookDetails missing = book(7L, "Book");
        TopicDetails topic = topic(missing, 12L, "Opening", 1, 9);
        when(books.findById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> selector.select(response(missing, topic)))
                .hasMessageContaining("Book 7 is not persisted");
        missing.setBookId(null);
        assertThatThrownBy(() -> selector.select(response(missing, topic)))
                .hasMessageContaining("no valid persisted book ID");
        assertThatThrownBy(() -> selector.select(null)).hasMessageContaining("saved book response");
        missing.setBookId(7L);
        missing.setBookName(" ");
        missing.setTotalPages(0);
        assertThatThrownBy(() -> selector.select(response(missing, topic)))
                .hasMessageContaining("no book name").hasMessageContaining("no valid PDF page count");
    }

    @Test
    void rejectsStaleBookNameAndPageCount() {
        BookDetails persistedBook = book(7L, "Actual Book");
        persist(persistedBook, topic(persistedBook, 12L, "Opening", 1, 9));
        BookDetails responseBook = book(7L, "Stale Book");
        responseBook.setTotalPages(99);

        assertThatThrownBy(() -> selector.select(response(responseBook,
                topic(responseBook, 12L, "Opening", 1, 9))))
                .hasMessageContaining("name differs").hasMessageContaining("PDF page count differs");
    }

    @Test
    void snapshotsAllTopicValuesBeforeManagedEntitiesCanChangeOrRollback() {
        BookDetails book = book(7L, "Book");
        TopicDetails actual = topic(book, 12L, "Opening", 1, 9);
        actual.setDetails("Subtopic one, Subtopic two");
        actual.setStartPage(null);
        actual.setEndPage(29);
        actual.setPdfPath("book/chapter.pdf");
        LocalDateTime created = LocalDateTime.of(2026, 11, 22, 10, 0);
        LocalDateTime updated = created.plusHours(1);
        ReflectionTestUtils.setField(actual, "createdAt", created);
        ReflectionTestUtils.setField(actual, "updatedAt", updated);
        persist(book, actual);

        var selection = selector.select(response(book, actual));
        actual.setTopicName("Changed");
        actual.setDetails("Changed");
        actual.setPrintedStartPage(99);
        actual.setPrintedEndPage(100);
        actual.setStartPage(21);
        actual.setEndPage(120);
        actual.setPdfPath("changed.pdf");
        ReflectionTestUtils.setField(actual, "updatedAt", updated.plusDays(1));

        TopicDetails snapshot = selection.firstTopic();
        assertThat(snapshot).isSameAs(selection.realTopics().getFirst());
        assertThat(snapshot.getTopicId()).isEqualTo(12L);
        assertThat(snapshot.getTopicName()).isEqualTo("Opening");
        assertThat(snapshot.getDetails()).isEqualTo("Subtopic one, Subtopic two");
        assertThat(snapshot.getPrintedStartPage()).isEqualTo(1);
        assertThat(snapshot.getPrintedEndPage()).isEqualTo(9);
        assertThat(snapshot.getStartPage()).isNull();
        assertThat(snapshot.getEndPage()).isEqualTo(29);
        assertThat(snapshot.getPdfPath()).isEqualTo("book/chapter.pdf");
        assertThat(snapshot.getCreatedAt()).isEqualTo(created);
        assertThat(snapshot.getUpdatedAt()).isEqualTo(updated);
        assertThat(snapshot.getBookDetails()).isSameAs(book);
    }

    private void persist(BookDetails book, TopicDetails... topics) {
        book.setTopics(new ArrayList<>(List.of(topics)));
        when(books.findById(book.getBookId())).thenReturn(Optional.of(book));
        when(this.topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(book.getBookId()))
                .thenReturn(List.of(topics));
    }

    private static BookExtractionResult response(BookDetails book, TopicDetails... topics) {
        return new BookExtractionResult(null, book, List.of(topics));
    }

    private static BookDetails book(Long id, String name) {
        BookDetails book = new BookDetails();
        book.setBookId(id);
        book.setBookName(name);
        book.setTotalPages(100);
        return book;
    }

    private static TopicDetails topic(BookDetails owner, Long id, String name, Integer start, Integer end) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(id);
        topic.setBookDetails(owner);
        topic.setTopicName(name);
        topic.setPrintedStartPage(start);
        topic.setPrintedEndPage(end);
        return topic;
    }
}
