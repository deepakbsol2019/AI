package com.book.download;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.book.download.service.BookReaderService;
import com.book.download.service.DownloadService;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.TopicDetailsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BookReaderServiceTests extends com.book.user.TenantAwareTest {
    private final TopicDetailsRepository topics = mock(TopicDetailsRepository.class);
    private final DownloadService downloads = mock(DownloadService.class);
    private final BookReaderService reader = new BookReaderService(topics, downloads);
    private BookDetails book;

    @BeforeEach
    void book() {
        book = new BookDetails();
        book.setBookId(7L);
        book.setBookName("Stored Book");
        book.setTotalPages(100);
    }

    @Test
    void laterIdOffsetIsFirstAndOutOfOrderRequestIdsAreAssembledInPhysicalSourceOrder() {
        List<Long> requested = List.of(2L, 90L, 1L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(1L, "First chapter", 5, 30),
                        topic(2L, "Second chapter", 31, 100), topic(90L, "Stored Book_offset", 1, 4)));
        byte[] pdf = new byte[]{1, 2, 3};
        when(downloads.downloadTopics(7L, List.of(90L, 1L, 2L))).thenReturn(pdf);

        assertThat(reader.readTopics(7L, requested)).isSameAs(pdf);

        verify(downloads).downloadTopics(7L, List.of(90L, 1L, 2L));
        verifyNoMoreInteractions(downloads);
    }

    @Test
    void physicalRangesTakePriorityOverBothRequestOrderAndTopicIdOrder() {
        List<Long> requested = List.of(2L, 30L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(2L, "Later", 51, 100), topic(30L, "Earlier", 5, 50)));

        reader.readTopics(7L, requested);

        verify(downloads).downloadTopics(7L, List.of(30L, 2L));
    }

    @Test
    void acceptsVerifiedEmptyOffsetWithoutGuessingItsPages() {
        List<Long> requested = List.of(1L, 99L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(1L, "First chapter", 1, 100), topic(99L, "Stored Book_offset", 1, 0)));

        reader.readTopics(7L, requested);

        verify(downloads).downloadTopics(7L, List.of(99L, 1L));
    }

    @Test
    void equalRangesPutExactReservedOffsetBeforeChapterIdsThenUseStableIdOrder() {
        List<Long> requested = List.of(30L, 99L, 20L, 1L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(30L, "Same range B", 1, 3), topic(99L, "Stored Book_offset", 1, 3),
                        topic(20L, "Same range A", 1, 3), topic(1L, "Next range", 4, 100)));

        reader.readTopics(7L, requested);

        verify(downloads).downloadTopics(7L, List.of(99L, 20L, 30L, 1L));
    }

    @ParameterizedTest
    @MethodSource("invalidBoundaries")
    void refusesMultiTopicReaderWhenPhysicalBoundariesAreMissingOrInvalid(Integer start, Integer end) {
        List<Long> requested = List.of(1L, 2L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(1L, "Unknown or invalid range", start, end),
                        topic(2L, "Known range", 51, 100)));

        assertThatThrownBy(() -> reader.readTopics(7L, requested))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getReason()).contains("verified source order", "physical PDF page boundaries",
                            "one topic at a time");
                });
        verifyNoInteractions(downloads);
    }

    private static Stream<Arguments> invalidBoundaries() {
        return Stream.of(Arguments.of(null, 10), Arguments.of(1, null), Arguments.of(0, 10),
                Arguments.of(-1, 10), Arguments.of(10, 5), Arguments.of(1, -1),
                Arguments.of(1, 0), Arguments.of(1, 101));
    }

    @Test
    void doesNotTreatAnotherBooksOrSimilarOffsetNameAsTheEmptyPrefix() {
        List<Long> requested = List.of(1L, 2L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(1L, "Another Book_offset", 1, 0), topic(2L, "Known", 1, 100)));

        assertStatus(() -> reader.readTopics(7L, requested), HttpStatus.CONFLICT);
        verifyNoInteractions(downloads);
    }

    @Test
    void missingOrForeignTopicNeverSilentlyProducesAPartialRead() {
        List<Long> requested = List.of(1L, 2L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(1L, "Known", 1, 10)));
        assertStatus(() -> reader.readTopics(7L, requested), HttpStatus.NOT_FOUND);
        TopicDetails foreign = topic(2L, "Foreign", 11, 100);
        BookDetails anotherBook = new BookDetails();
        anotherBook.setBookId(8L);
        foreign.setBookDetails(anotherBook);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(1L, "Known", 1, 10), foreign));
        assertStatus(() -> reader.readTopics(7L, requested), HttpStatus.NOT_FOUND);
        verifyNoInteractions(downloads);
    }

    @ParameterizedTest
    @MethodSource("invalidSelections")
    void rejectsDuplicateOrInvalidIdsBeforeRepositoryLookup(Long bookId, List<Long> selected) {
        assertStatus(() -> reader.readTopics(bookId, selected), HttpStatus.BAD_REQUEST);
        verifyNoInteractions(topics, downloads);
    }

    private static Stream<Arguments> invalidSelections() {
        return Stream.of(Arguments.of(7L, List.of(1L, 1L)), Arguments.of(7L, List.of()),
                Arguments.of(7L, (List<Long>) null), Arguments.of(7L, List.of(0L)),
                Arguments.of(7L, List.of(-1L)), Arguments.of(7L, Arrays.asList(1L, null)),
                Arguments.of(null, List.of(1L)), Arguments.of(0L, List.of(1L)));
    }

    @Test
    void singleTopicReusesExistingOwnershipAndFileChecksWithoutInventingMissingPageBoundaries() {
        byte[] pdf = new byte[]{4, 5, 6};
        when(downloads.downloadTopics(7L, List.of(4L))).thenReturn(pdf);

        assertThat(reader.readTopics(7L, List.of(4L))).isSameAs(pdf);

        verifyNoInteractions(topics);
        verify(downloads).downloadTopics(7L, List.of(4L));
    }

    @Test
    void preservesExistingAssemblyErrorsAfterOrdering() {
        List<Long> requested = List.of(1L, 2L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(7L, requested))
                .thenReturn(List.of(topic(1L, "First", 1, 10), topic(2L, "Second", 11, 100)));
        var sourceFailure = new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored PDF is unavailable.");
        when(downloads.downloadTopics(7L, requested)).thenThrow(sourceFailure);

        assertThatThrownBy(() -> reader.readTopics(7L, requested)).isSameAs(sourceFailure);
    }

    private TopicDetails topic(Long id, String name, Integer start, Integer end) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(id);
        topic.setTopicName(name);
        topic.setStartPage(start);
        topic.setEndPage(end);
        topic.setBookDetails(book);
        return topic;
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(status));
    }
}
