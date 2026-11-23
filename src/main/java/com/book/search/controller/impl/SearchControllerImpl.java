package com.book.search.controller.impl;

import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse;
import com.book.search.controller.SearchController;
import com.book.search.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchControllerImpl implements SearchController {
    private final SearchService searchService;

    public SearchControllerImpl(SearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public ResponseEntity<BookSearchResponse> searchBooks(BookSearchCriteria criteria) {
        return ResponseEntity.ok(searchService.searchBooks(criteria));
    }
}
