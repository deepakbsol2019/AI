package com.book.download.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RequestMapping("/api/download")
public interface DownloadController {
    @GetMapping(value = "/book-download", produces = "application/pdf")
    ResponseEntity<byte[]> downloadBook(@RequestParam("bookId") Long bookId);

    @GetMapping(value = "/book-topic-download", produces = "application/pdf")
    ResponseEntity<byte[]> downloadTopics(@RequestParam("bookId") Long bookId,
            @RequestParam("topicIds") List<Long> topicIds);
}
