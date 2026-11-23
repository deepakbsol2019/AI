package com.book.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Stored public book metadata, detached from persistence relationships and server file locations. */
public record BookSearchResponse(List<BookResult> books, int page, int size, long totalElements, long totalPages) {
    public BookSearchResponse {
        books = List.copyOf(books);
    }

    public record BookResult(Long bookId, String bookName, String authorName, String bookDetails,
            int totalPages, Long bookUploadId, LocalDateTime createdAt, LocalDateTime updatedAt,
            AuthorResult authorDetails, int topicCount, List<TopicResult> topics) {
        public BookResult {
            topics = List.copyOf(topics);
        }
    }

    /** The project stores one author record per book; its complete credited-name text is preserved. */
    public record AuthorResult(Long authorId, String authorName, LocalDateTime createdAt, LocalDateTime updatedAt) { }

    public record TopicResult(Long topicId, String topicName, String topicDetails, Integer startPage,
            Integer endPage, Integer printedStartPage, Integer printedEndPage,
            LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
