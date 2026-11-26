package com.book.search.service.impl;

import static com.book.time.UtcDates.instant;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse;
import com.book.dto.BookSearchResponse.AuthorResult;
import com.book.dto.BookSearchResponse.BookResult;
import com.book.dto.BookSearchResponse.TopicResult;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.search.repository.BookSearchQueryRepository;
import com.book.search.service.SearchService;
import com.book.search.tool.BookSearchToolMetadata;

@Service
public class SearchServiceImpl implements SearchService {
    private final BookSearchQueryRepository books;

    public SearchServiceImpl(BookSearchQueryRepository books) {
        this.books = books;
    }

    @Override
    @Transactional(readOnly = true)
    @Tool(name = "searchBooks", description = BookSearchToolMetadata.DESCRIPTION)
    public BookSearchResponse searchBooks(
            @ToolParam(description = BookSearchToolMetadata.CRITERIA_DESCRIPTION, required = true) BookSearchCriteria criteria) {
        BookSearchCriteria normalized = normalize(criteria);
        var found = books.search(normalized);
        long total = found.totalElements();
        long totalPages = total == 0 ? 0 : 1 + (total - 1) / normalized.size();
        List<BookResult> response = found.books().stream().map(SearchServiceImpl::toResponse).toList();
        return new BookSearchResponse(response, normalized.page(), normalized.size(), total, totalPages);
    }

    private static BookSearchCriteria normalize(BookSearchCriteria criteria) {
        if (criteria == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The criteria object is required; use an empty object to list books.");
        }
        int page = criteria.page() == null ? 0 : criteria.page();
        int size = criteria.size() == null ? 20 : criteria.size();
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be greater than or equal to 0.");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100.");
        }
        return new BookSearchCriteria(normalize(criteria.bookName()), normalize(criteria.topicName()),
                normalize(criteria.authorName()), normalize(criteria.bookDetailsContains()),
                normalize(criteria.topicDetailsContains()), normalize(criteria.query()), page, size);
    }

    private static String normalize(String value) {
        if (value == null) return null;
        int first = 0;
        int last = value.length();
        while (first < last && isBoundarySpace(value.codePointAt(first))) {
            first += Character.charCount(value.codePointAt(first));
        }
        while (last > first && isBoundarySpace(value.codePointBefore(last))) {
            last -= Character.charCount(value.codePointBefore(last));
        }
        return first == last ? null : value.substring(first, last).toLowerCase(Locale.ROOT);
    }

    private static boolean isBoundarySpace(int character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    private static BookResult toResponse(BookDetails book) {
        AuthorDetails author = book.getAuthorDetails();
        AuthorResult authorDetails = author == null ? null : new AuthorResult(author.getAuthorId(),
                author.getAuthorName(), instant(author.getCreatedAt()), instant(author.getUpdatedAt()));
        List<TopicResult> topics = book.getTopics() == null ? List.of() : book.getTopics().stream()
                .sorted(Comparator.comparing(TopicDetails::getTopicId))
                .map(topic -> new TopicResult(topic.getTopicId(), topic.getTopicName(), topic.getDetails(),
                        topic.getStartPage(), topic.getEndPage(), topic.getPrintedStartPage(), topic.getPrintedEndPage(),
                        instant(topic.getCreatedAt()), instant(topic.getUpdatedAt()))).toList();
        return new BookResult(book.getBookId(), book.getBookName(), book.getBookType(), author == null ? null : author.getAuthorName(),
                book.getBookDetails(), book.getTotalPages(), book.getBookUploadId(), instant(book.getCreatedAt()),
                instant(book.getUpdatedAt()), authorDetails, topics.size(), topics);
    }
}
