package com.book.download.pdf;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import com.book.entity.TopicDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Read adapter for the local files written by TopicPdfFileStore. Uses stored paths, including legacy filenames. */
@Component
public class TopicPdfSource {
    private final com.book.storage.TenantStoragePaths storage;

    public TopicPdfSource(@Value("${app.topic-pdf.storage-root:C:/workspace/backend/Book_Search/upload-pdf}") String storageRoot) {
        if (storageRoot == null || storageRoot.isBlank()) {
            throw new IllegalArgumentException("Topic PDF storage root is required.");
        }
        storage = new com.book.storage.TenantStoragePaths(storageRoot);
    }

    public Path resolve(TopicDetails topic) {
        com.book.user.security.TenantContext.requireTenantId();
        if (topic.getBookDetails()==null) throw unavailable("Topic", "has no tenant owner",null);
        com.book.user.security.TenantContext.requireOwned(topic.getBookDetails().getTenantId());
        return resolveStoredPath(topic.getPdfPath(), "Topic " + topic.getTopicId());
    }

    private Path resolveStoredPath(String pdfPath, String label) {
        if (pdfPath == null || pdfPath.isBlank()) {
            throw unavailable(label, "has no saved PDF", null);
        }
        try {
            return storage.readableFile(com.book.user.security.TenantContext.requireTenantId(),pdfPath);
        }
        catch (IOException | InvalidPathException | SecurityException exception) {
            throw unavailable(label, "has a missing, unreadable or outside-tenant PDF in storage", exception);
        }
    }

    private static ResponseStatusException unavailable(String label, String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                label + " " + message + ".", cause);
    }
}
