package com.book.upload.topic;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Worker-side PDF and filesystem work only. No Spring transaction, entity, or repository
 * is accessed here. Each attempt opens its own source/output PDF documents.
 */
@Component
public class TopicPdfFileStore {
    private final Path configuredRoot;
    private final int maxAttempts;
    private final ConcurrentMap<Path, OperationFiles> operations = new ConcurrentHashMap<>();

    public TopicPdfFileStore(@Value("${app.topic-pdf.storage-root:C:/workspace/backend/Book_Search/upload-pdf}") String storageRoot,
            @Value("${app.topic-pdf.max-attempts:3}") int maxAttempts) {
        if (storageRoot == null || storageRoot.isBlank()) {
            throw new IllegalArgumentException("Topic PDF storage root is required.");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("Topic PDF max-attempts must be between 1 and 10.");
        }
        configuredRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.maxAttempts = maxAttempts;
    }

    /** The requested book folder is exclusively owned by this upload until its transaction completes. */
    public Path createOperationDirectory(Long bookId, String bookName) {
        if (bookId == null || bookId <= 0) {
            throw new IllegalArgumentException("A persisted book ID is required for topic PDF storage.");
        }
        try {
            Files.createDirectories(configuredRoot);
            Path root = configuredRoot.toRealPath();
            Path operation = root.resolve(bookFolderName(bookName)).toAbsolutePath().normalize();
            // Never adopt an existing folder: it may contain another upload's committed PDFs.
            Files.createDirectory(operation);
            operations.put(operation, new OperationFiles());
            return operation;
        }
        catch (IOException exception) {
            throw new TopicPdfStorageException("Could not create the book's topic PDF operation directory.", exception);
        }
    }

    /**
     * Extracts an inclusive, one-based physical page range. Only PDF extraction and local
     * saving are retried. An invalid range or programming/configuration error is not retried.
     */
    public SavedTopicPdf extractAndSave(byte[] sourcePdf, Long topicId, String topicName,
            int startPage, int endPage, Path operationDirectory) {
        return extractAndSave(sourcePdf, topicId, topicName, startPage, endPage, operationDirectory, false, false);
    }

    /** Saves the original document intact; PDF validity is checked once by the upload orchestration thread. */
    public SavedTopicPdf saveWholeBook(byte[] sourcePdf, Long topicId, String topicName, Path operationDirectory) {
        return extractAndSave(sourcePdf, topicId, topicName, 1, 1, operationDirectory, false, true);
    }

    /** The offset row represents the physical prefix, including a legitimate empty prefix. */
    public SavedTopicPdf extractOffsetAndSave(byte[] sourcePdf, Long topicId, String topicName,
            int offset, Path operationDirectory) {
        if (offset < 0) {
            throw new IllegalArgumentException("The offset prefix cannot have a negative length.");
        }
        return extractAndSave(sourcePdf, topicId, topicName, 1, offset, operationDirectory, offset == 0, false);
    }

    private SavedTopicPdf extractAndSave(byte[] sourcePdf, Long topicId, String topicName,
            int startPage, int endPage, Path operationDirectory, boolean emptyPrefix, boolean wholeBook) {
        if (sourcePdf == null || sourcePdf.length == 0 || topicId == null || topicId <= 0
                || topicName == null || topicName.isBlank() || startPage < 1
                || (endPage < startPage && !(emptyPrefix && startPage == 1 && endPage == 0))) {
            throw new IllegalArgumentException("A PDF, persisted topic ID, title and valid inclusive page range are required.");
        }
        String filename = topicFileName(topicName);
        Path operation = normalized(operationDirectory);
        OperationFiles files = ownedOperation(operation);
        synchronized (files) {
            if (files.closed || files.claimedTopics.contains(topicId)
                    || files.claimedFilenames.contains(filename.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("This topic PDF operation is closed or the topic was already dispatched.");
            }
            files.claimedTopics.add(topicId);
            files.claimedFilenames.add(filename.toLowerCase(Locale.ROOT));
            files.activeWorkers++;
        }
        try {
            Path destination = operation.resolve(filename);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                Path temporary = null;
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new TopicPdfStorageException("Topic PDF extraction was interrupted for topic " + topicId + ".");
                    }
                    requireOperationDirectory(operation);
                    temporary = Files.createTempFile(operation, ".topic-" + topicId + "-", ".part");
                    files.paths.add(temporary);
                    if (wholeBook) writeWholeBookPdf(sourcePdf, temporary);
                    else writeTopicPdf(sourcePdf, startPage, endPage, temporary);
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("A topic PDF already exists in this operation: " + destination);
                    }
                    moveCompletedFile(temporary, destination);
                    files.paths.remove(temporary);
                    files.paths.add(destination);
                    return new SavedTopicPdf(topicId, destination.toString());
                }
                catch (IOException exception) {
                    boolean cleaned = deleteAttempt(temporary, files, exception);
                    if (!cleaned || attempt == maxAttempts || Thread.currentThread().isInterrupted()) {
                        throw new TopicPdfStorageException("Topic PDF extraction/local saving failed for topic "
                                + topicId + " after " + attempt + " attempt(s).", exception);
                    }
                }
                catch (RuntimeException | Error exception) {
                    deleteAttempt(temporary, files, exception);
                    throw exception;
                }
            }
            throw new IllegalStateException("Topic PDF retry loop completed without a result.");
        }
        finally {
            synchronized (files) {
                files.activeWorkers--;
            }
        }
    }

    public static String bookFolderName(String bookName) {
        return windowsComponent(bookName, 255);
    }

    public static String topicFileName(String topicName) {
        return windowsComponent(topicName, 251) + ".pdf";
    }

    /** Keeps valid titles intact and converts characters Windows cannot use in a path component. */
    private static String windowsComponent(String name, int maximumLength) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A nonempty book or topic name is required for PDF storage.");
        }
        String safe = name.strip().replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f\\x7f]", "_")
                .replaceFirst("[. ]+$", "");
        if (safe.isBlank()) {
            throw new IllegalArgumentException("The book or topic name is not a valid Windows filename.");
        }
        if (safe.matches("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\\..*)?$")) {
            safe = "_" + safe;
        }
        if (safe.length() > maximumLength) {
            throw new IllegalArgumentException("The book or topic filename exceeds the Windows component limit.");
        }
        return safe;
    }

    /** A protected I/O boundary permits deterministic filesystem-failure tests without a database. */
    protected void writeWholeBookPdf(byte[] sourcePdf, Path output) throws IOException {
        Files.write(output, sourcePdf);
    }

    /** A protected I/O boundary permits deterministic filesystem-failure tests without a database. */
    protected void writeTopicPdf(byte[] sourcePdf, int startPage, int endPage, Path output) throws IOException {
        try (PDDocument source = Loader.loadPDF(sourcePdf); PDDocument topic = new PDDocument()) {
            if (endPage > source.getNumberOfPages() || !source.getCurrentAccessPermission().canExtractContent()) {
                throw new IllegalArgumentException("Topic page range is outside the source PDF or extraction is not permitted.");
            }
            for (int pageNumber = startPage; pageNumber <= endPage; pageNumber++) {
                PDPage sourcePage = source.getPage(pageNumber - 1);
                PDPage importedPage = topic.importPage(sourcePage);
                // Page resources may be inherited; importPage does not copy inherited resources itself.
                if (sourcePage.getResources() != null) {
                    importedPage.setResources(sourcePage.getResources());
                }
            }
            topic.save(output.toFile());
        }
    }

    private static void moveCompletedFile(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException exception) {
            // Both paths have the same unique parent, so this never crosses filesystems.
            Files.move(temporary, destination);
        }
    }

    /**
     * Called only after every dispatched worker has finished. Deletes only files created
     * by this operation, then its empty directory; it never recursively walks a tree.
     */
    public void cleanupOperation(Path operationDirectory) {
        Path operation = normalized(operationDirectory);
        OperationFiles files = operations.get(operation);
        if (files == null) {
            if (!Files.exists(operation, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            throw new IllegalArgumentException("Refusing to clean a directory not owned by this operation.");
        }
        synchronized (files) {
            if (files.activeWorkers != 0) {
                throw new IllegalStateException("Wait for all topic PDF workers before cleanup.");
            }
            files.closed = true;
        }
        List<IOException> failures = new ArrayList<>();
        try {
            requireOperationDirectory(operation);
        }
        catch (IOException exception) {
            // Do not follow a replaced directory/link, even to remove tracked filenames.
            throw new TopicPdfStorageException("The topic PDF operation directory changed before cleanup.", exception);
        }
        for (Path path : List.copyOf(files.paths)) {
            try {
                if (!Objects.equals(path.getParent(), operation)) {
                    throw new IOException("Refusing a tracked file outside this operation directory.");
                }
                Files.deleteIfExists(path);
                files.paths.remove(path);
            }
            catch (IOException exception) {
                failures.add(exception);
            }
        }
        if (failures.isEmpty()) {
            try {
                Files.deleteIfExists(operation);
                operations.remove(operation, files);
            }
            catch (IOException exception) {
                failures.add(exception);
            }
        }
        if (!failures.isEmpty()) {
            TopicPdfStorageException exception = new TopicPdfStorageException(
                    "Could not clean this topic PDF operation's files: " + operation, failures.getFirst());
            failures.stream().skip(1).forEach(exception::addSuppressed);
            throw exception;
        }
    }

    /** Called after a successful database commit to release tracking while retaining the PDFs. */
    public void retainOperation(Path operationDirectory) {
        Path operation = normalized(operationDirectory);
        OperationFiles files = ownedOperation(operation);
        synchronized (files) {
            if (files.activeWorkers != 0 || files.closed) {
                throw new IllegalStateException("Cannot retain an active or closed topic PDF operation.");
            }
            files.closed = true;
            operations.remove(operation, files);
        }
    }

    private OperationFiles ownedOperation(Path operation) {
        OperationFiles files = operations.get(operation);
        if (files == null) {
            throw new IllegalArgumentException("The directory does not belong to this topic PDF operation.");
        }
        return files;
    }

    private static Path normalized(Path operationDirectory) {
        return Objects.requireNonNull(operationDirectory, "Topic PDF operation directory is required.")
                .toAbsolutePath().normalize();
    }

    private static void requireOperationDirectory(Path operation) throws IOException {
        if (!Files.isDirectory(operation, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(operation.getParent())
                || !operation.toRealPath().equals(operation)) {
            throw new IOException("Topic PDF operation directory is absent or resolves through a symbolic link.");
        }
    }

    private static boolean deleteAttempt(Path temporary, OperationFiles files, Throwable failure) {
        if (temporary == null) {
            return true;
        }
        try {
            Files.deleteIfExists(temporary);
            files.paths.remove(temporary);
            return true;
        }
        catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            return false;
        }
    }

    private static final class OperationFiles {
        private final Set<Path> paths = ConcurrentHashMap.newKeySet();
        private final Set<Long> claimedTopics = ConcurrentHashMap.newKeySet();
        private final Set<String> claimedFilenames = ConcurrentHashMap.newKeySet();
        private int activeWorkers;
        private boolean closed;
    }

    public record SavedTopicPdf(Long topicId, String pdfPath) { }

    public static class TopicPdfStorageException extends RuntimeException {
        public TopicPdfStorageException(String message) { super(message); }
        public TopicPdfStorageException(String message, Throwable cause) { super(message, cause); }
    }
}
