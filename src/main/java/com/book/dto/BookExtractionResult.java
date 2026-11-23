package com.book.dto;

import java.util.List;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.offset.BookPageOffsetResult;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record BookExtractionResult(AuthorDetails authorDetails, BookDetails bookDetails,
        List<TopicDetails> topicDetails, BookPageOffsetResult pageOffset,
        @JsonIgnore UploadFileDtlsDto uploadedFile, boolean wholeBook, String fallbackReason) {
    public BookExtractionResult(AuthorDetails authorDetails, BookDetails bookDetails,
            List<TopicDetails> topicDetails) {
        this(authorDetails, bookDetails, topicDetails, null, null, false, null);
    }

    public BookExtractionResult(AuthorDetails authorDetails, BookDetails bookDetails,
            List<TopicDetails> topicDetails, BookPageOffsetResult pageOffset) {
        this(authorDetails, bookDetails, topicDetails, pageOffset, null, false, null);
    }

    public BookExtractionResult(AuthorDetails authorDetails, BookDetails bookDetails,
            List<TopicDetails> topicDetails, BookPageOffsetResult pageOffset, UploadFileDtlsDto uploadedFile) {
        this(authorDetails, bookDetails, topicDetails, pageOffset, uploadedFile, false, null);
    }

    /** Internal upload context reuses the actual upload DTO and is never exposed in JSON. */
    public BookExtractionResult withUploadedFile(UploadFileDtlsDto uploadedFile) {
        return new BookExtractionResult(authorDetails, bookDetails, topicDetails, pageOffset, uploadedFile,
                wholeBook, fallbackReason);
    }

    public BookExtractionResult withWholeBook(String reason) {
        return new BookExtractionResult(authorDetails, bookDetails, topicDetails, pageOffset, uploadedFile, true, reason);
    }
}
