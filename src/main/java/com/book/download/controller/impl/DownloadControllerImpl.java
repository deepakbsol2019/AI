package com.book.download.controller.impl;

import java.util.List;

import com.book.download.controller.DownloadController;
import com.book.download.service.DownloadService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DownloadControllerImpl implements DownloadController {
    private final DownloadService downloadService;

    public DownloadControllerImpl(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @Override
    public ResponseEntity<byte[]> downloadBook(Long bookId) {
        return attachment(downloadService.downloadBook(bookId), "book-" + bookId + ".pdf");
    }

    @Override
    public ResponseEntity<byte[]> downloadTopics(Long bookId, List<Long> topicIds) {
        return attachment(downloadService.downloadTopics(bookId, topicIds),
                "book-" + bookId + "-selected-topics.pdf");
    }

    private static ResponseEntity<byte[]> attachment(byte[] pdf, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentLength(pdf.length)
                .body(pdf);
    }
}
