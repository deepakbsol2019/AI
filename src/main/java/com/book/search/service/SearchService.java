package com.book.search.service;

import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse;

public interface SearchService {
    BookSearchResponse searchBooks(BookSearchCriteria criteria);
}
