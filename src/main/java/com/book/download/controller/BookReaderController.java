package com.book.download.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Inline delivery of the same authorized, authoritative PDFs used by downloads. */
@RequestMapping("/api/read")
public interface BookReaderController {
    @GetMapping(value = "/book", produces = "application/pdf")
    ResponseEntity<byte[]> readBook(@RequestParam("bookId") Long bookId);

    @GetMapping(value = "/topics", produces = "application/pdf")
    ResponseEntity<byte[]> readTopics(@RequestParam("bookId") Long bookId,
            @RequestParam("topicIds") List<Long> topicIds);
}
