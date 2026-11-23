package com.book.download.pdf;

import java.io.IOException;
import java.nio.file.Files;
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
    private final Path configuredRoot;

    public TopicPdfSource(@Value("${app.topic-pdf.storage-root:C:/workspace/backend/Book_Search/upload-pdf}") String storageRoot) {
        if (storageRoot == null || storageRoot.isBlank()) {
            throw new IllegalArgumentException("Topic PDF storage root is required.");
        }
        configuredRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public Path resolve(TopicDetails topic) {
        if (topic.getPdfPath() == null || topic.getPdfPath().isBlank()) {
            throw unavailable(topic, "has no saved PDF", null);
        }
        try {
            Path root = configuredRoot.toRealPath();
            Path storedPath = Path.of(topic.getPdfPath());
            Path candidate = (storedPath.isAbsolute() ? storedPath : configuredRoot.resolve(storedPath))
                    .toAbsolutePath().normalize();
            // The upload store persists real paths, including when the configured root is a junction.
            if (!candidate.startsWith(configuredRoot) && !candidate.startsWith(root)) {
                throw unavailable(topic, "has a PDF outside the configured storage folder", null);
            }
            // Resolve symlinks/junctions as well as '..'; never serve another local file through a stored path.
            Path actual = candidate.toRealPath();
            if (!actual.startsWith(root)) {
                throw unavailable(topic, "has a PDF outside the configured storage folder", null);
            }
            if (!Files.isRegularFile(actual) || !Files.isReadable(actual)) {
                throw unavailable(topic, "has an unavailable PDF in storage", null);
            }
            return actual;
        }
        catch (IOException | InvalidPathException | SecurityException exception) {
            throw unavailable(topic, "has a missing or unreadable PDF in storage", exception);
        }
    }

    private static ResponseStatusException unavailable(TopicDetails topic, String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Topic " + topic.getTopicId() + " " + message + ".", cause);
    }
}
