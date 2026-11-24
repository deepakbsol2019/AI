package com.book.search;

import java.util.List;

import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;
import com.book.entity.TopicDetails;
import com.book.search.repository.BookSearchQueryRepository;
import com.book.search.repository.BookSearchQueryRepository.SearchPage;
import com.book.search.service.impl.SearchServiceImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchServiceTests {
    private final BookSearchQueryRepository repository = mock(BookSearchQueryRepository.class);
    private final SearchServiceImpl service = new SearchServiceImpl(repository);

    @Test
    void normalizesEveryFilterAndAppliesDefaultsInTheSharedMethod() {
        when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));
        BookSearchCriteria input = new BookSearchCriteria("  JaVa  ", " Collections ", " ASHA Sharma ",
                " 100%_Match! ", " Thread Pool ", " Books ", null, null);

        BookSearchResponse result = service.searchBooks(input);

        verify(repository).search(new BookSearchCriteria("java", "collections", "asha sharma",
                "100%_match!", "thread pool", "books", 0, 20));
        assertThat(input.bookName()).isEqualTo("  JaVa  ");
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.books()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
    }

    @Test
    void blankFiltersBecomeAnUnfilteredListingRatherThanEmptyStringMatches() {
        when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));
        service.searchBooks(new BookSearchCriteria("", " \t ", "\n", null, " ", "  ", null, null));
        verify(repository).search(new BookSearchCriteria(null, null, null, null, null, null, 0, 20));
    }

    @Test
    void copiedNonbreakingSpacesAreTrimmedWithoutCollapsingInternalNameWhitespace() {
        when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));
        service.searchBooks(new BookSearchCriteria("\u00a0Java  Essentials\u202f", "\u00a0\u2007\u202f", null,
                null, null, null, null, null));
        verify(repository).search(new BookSearchCriteria("java  essentials", null, null, null, null, null, 0, 20));
    }

    @Test
    void outOfRangePageKeepsTheActualDistinctTotalsAndRequestedPagination() {
        when(repository.search(any())).thenReturn(new SearchPage(List.of(), 24));
        var result = service.searchBooks(new BookSearchCriteria(null, null, null, null, null, null, 2, 20));
        assertThat(result.books()).isEmpty();
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(24);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    void missingOuterCriteriaIsRejectedBeforeQuerying() {
        assertBadRequest(() -> service.searchBooks(null));
        verifyNoInteractions(repository);
    }

    @Test
    void negativePageIsRejectedBeforeQuerying() {
        assertBadRequest(() -> service.searchBooks(new BookSearchCriteria(null, null, null, null, null, null, -1, 20)));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void invalidSizesAreRejectedRatherThanClamped(int size) {
        assertBadRequest(() -> service.searchBooks(new BookSearchCriteria(null, null, null, null, null, null, 0, size)));
        verifyNoInteractions(repository);
    }

    @Test
    void returnsStoredMetadataAndAllTopicsWithoutExposingThePersistenceGraph() {
        BookDetails book = new BookDetails();
        book.setBookId(101L);
        book.setBookName("JaVa");
        book.setBookType("Java Programming");
        book.setBookDetails("Original Description");
        book.setTotalPages(10);
        book.setExtractionId("internal-extraction-id");
        BookUploadStatus receipt = new BookUploadStatus();
        receipt.setBookUploadId(77L);
        book.setBookUploadStatus(receipt);
        AuthorDetails author = new AuthorDetails();
        author.setAuthorId(55L);
        author.setAuthorName("Asha Sharma, Meera Rao");
        book.setAuthorDetails(author);
        book.addTopic(topic(203L, "Collections", "Lists and Maps"));
        book.addTopic(topic(201L, "JaVa_offset", "Front matter"));
        book.addTopic(topic(202L, "Fundamentals", null));
        when(repository.search(any())).thenReturn(new SearchPage(List.of(book), 1));

        var result = service.searchBooks(new BookSearchCriteria(null, "Collections", null, null, null, null, null, null));

        var stored = result.books().getFirst();
        assertThat(stored.bookName()).isEqualTo("JaVa");
        assertThat(stored.bookType()).isEqualTo("Java Programming");
        assertThat(stored.bookDetails()).isEqualTo("Original Description");
        assertThat(stored.authorName()).isEqualTo("Asha Sharma, Meera Rao");
        assertThat(stored.authorDetails().authorId()).isEqualTo(55L);
        assertThat(stored.authorDetails().authorName()).isEqualTo(stored.authorName());
        assertThat(stored.totalPages()).isEqualTo(10);
        assertThat(stored.bookUploadId()).isEqualTo(77L);
        assertThat(stored.topicCount()).isEqualTo(3);
        assertThat(stored.topics()).extracting(BookSearchResponse.TopicResult::topicId).containsExactly(201L, 202L, 203L);
        String json = JsonMapper.builder().build().writeValueAsString(result);
        assertThat(json).contains("\"topicDetails\":\"Lists and Maps\"")
                .contains("\"bookType\":\"Java Programming\"")
                .doesNotContain("internal-extraction-id", "extractionId", "pdfPath", "private-server-path", "private-server-source-path", "sourcePdfPath", "bookUploadStatus");
    }

    @Test
    void missingAuthorAndTopicsRemainNullAndEmptyInTheResponse() {
        BookDetails book = new BookDetails();
        book.setBookId(8L);
        book.setBookName("Stored Book");
        when(repository.search(any())).thenReturn(new SearchPage(List.of(book), 1));

        var result = service.searchBooks(new BookSearchCriteria(null, null, null, null, null, null, null, null));

        assertThat(result.books()).singleElement().satisfies(stored -> {
            assertThat(stored.authorName()).isNull();
            assertThat(stored.bookType()).isEqualTo("Unknown");
            assertThat(stored.authorDetails()).isNull();
            assertThat(stored.bookDetails()).isNull();
            assertThat(stored.topics()).isEmpty();
            assertThat(stored.topicCount()).isZero();
        });
    }

    private static TopicDetails topic(Long id, String name, String details) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(id);
        topic.setTopicName(name);
        topic.setDetails(details);
        topic.setPdfPath("C:/private-server-path/topic.pdf");
        return topic;
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
