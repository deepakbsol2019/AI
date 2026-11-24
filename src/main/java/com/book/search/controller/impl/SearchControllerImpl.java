package com.book.search.controller.impl;

import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse;
import com.book.search.controller.SearchController;
import com.book.search.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchControllerImpl implements SearchController {
    private static final int DEFAULT_LISTING_SIZE = 5;
    private final SearchService searchService;

    public SearchControllerImpl(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public ResponseEntity<BookSearchResponse> searchBooks(BookSearchCriteria criteria) {
        if (criteria != null && criteria.size() == null) {
            // The homepage listing defaults to five; the shared AI tool retains its own existing default.
            criteria = new BookSearchCriteria(criteria.bookName(), criteria.topicName(), criteria.authorName(),
                    criteria.bookDetailsContains(), criteria.topicDetailsContains(), criteria.query(),
                    criteria.page(), DEFAULT_LISTING_SIZE);
        }
        return ResponseEntity.ok(searchService.searchBooks(criteria));
    }
}
