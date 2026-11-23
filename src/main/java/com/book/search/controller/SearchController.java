package com.book.search.controller;

import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/search")
public interface SearchController {
    @GetMapping("/books")
    ResponseEntity<BookSearchResponse> searchBooks(@ModelAttribute BookSearchCriteria criteria);
}
