package com.book.download.controller.impl;

import java.util.List;

import com.book.download.controller.BookReaderController;
import com.book.download.service.DownloadService;
import com.book.download.service.BookReaderService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookReaderControllerImpl implements BookReaderController {
    private final DownloadService downloads;
    private final BookReaderService reader;

    public BookReaderControllerImpl(DownloadService downloads, BookReaderService reader) {
        this.downloads = downloads;
        this.reader = reader;
    }

    @Override
    public ResponseEntity<byte[]> readBook(Long bookId) {
        return inline(downloads.downloadBook(bookId), "book-" + bookId + ".pdf");
    }

    @Override
    public ResponseEntity<byte[]> readTopics(Long bookId, List<Long> topicIds) {
        return inline(reader.readTopics(bookId, topicIds), "book-" + bookId + "-topics.pdf");
    }

    private static ResponseEntity<byte[]> inline(byte[] pdf, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(filename).build().toString())
                .contentLength(pdf.length)
                .body(pdf);
    }
}
