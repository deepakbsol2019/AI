package com.book.metadata.controller;

import com.book.dto.BookSearchResponse.BookResult;
import com.book.metadata.dto.MetadataPatchRequest;
import com.book.metadata.service.BookMetadataService;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/books")
public class BookMetadataController {
    private final BookMetadataService metadata;

    public BookMetadataController(BookMetadataService metadata) { this.metadata = metadata; }

    @PatchMapping("/{bookId}/metadata")
    public ResponseEntity<BookResult> patchBook(@PathVariable("bookId") Long bookId,
            @RequestBody MetadataPatchRequest request) {
        return ResponseEntity.ok(metadata.patchBook(bookId, request));
    }

    @PatchMapping("/{bookId}/topics/{topicId}/metadata")
    public ResponseEntity<BookResult> patchTopic(@PathVariable("bookId") Long bookId,
            @PathVariable("topicId") Long topicId, @RequestBody MetadataPatchRequest request) {
        return ResponseEntity.ok(metadata.patchTopic(bookId, topicId, request));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> invalidMetadata(ResponseStatusException failure) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(failure.getStatusCode(),
                failure.getReason() == null ? "The metadata update could not be saved." : failure.getReason());
        return ResponseEntity.status(failure.getStatusCode()).body(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformedMetadata() {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Send one JSON object containing exactly one editable text or null field."));
    }
}
