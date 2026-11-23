package com.book.upload.topic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.book.dto.BookExtractionResult;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.extraction.ContentsUnavailableException;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import com.book.upload.topic.TopicPdfFileStore.SavedTopicPdf;

/** Joins the upload transaction; asynchronous tasks never access repositories or managed entities. */
@Service
public class TopicPdfUploadService {
    private static final Logger logger = LoggerFactory.getLogger(TopicPdfUploadService.class);
    private final Executor executor;
    private final TopicPdfFileStore files;
    private final BookDetailsRepository books;
    private final TopicDetailsRepository topics;

    public TopicPdfUploadService(@Qualifier("topicPdfExecutor") Executor executor, TopicPdfFileStore files,
            BookDetailsRepository books, TopicDetailsRepository topics) {
        this.executor = executor;
        this.files = files;
        this.books = books;
        this.topics = topics;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public BookExtractionResult uploadTopic(BookExtractionResult savedBookDetails) {
        requireTransaction();
        PreparedUpload prepared = prepare(savedBookDetails);
        Path operationDirectory = files.createOperationDirectory(prepared.bookId(), prepared.bookName());
        List<CompletableFuture<SavedTopicPdf>> futures = new ArrayList<>();
        try {
            registerCompletionCleanup(operationDirectory);
            // Everything captured by these closures is frozen before any worker is dispatched.
            byte[] sourcePdf = prepared.pdfBytes();
            TopicPdfFileStore fileStore = files;
            for (TopicInput input : prepared.inputs()) {
                futures.add(CompletableFuture.supplyAsync(() -> prepared.wholeBook()
                        ? fileStore.saveWholeBook(sourcePdf, input.topicId(), input.topicName(), operationDirectory)
                        : input.offset()
                                ? fileStore.extractOffsetAndSave(sourcePdf, input.topicId(), input.topicName(), input.endPage(), operationDirectory)
                                : fileStore.extractAndSave(sourcePdf, input.topicId(), input.topicName(), input.startPage(), input.endPage(), operationDirectory), executor));
            }
            awaitWorkers(futures);
            if (Thread.currentThread().isInterrupted()) {
                throw new TopicPdfUploadException("The topic PDF operation was interrupted.");
            }
            List<SavedTopicPdf> results = futures.stream().map(CompletableFuture::join).toList();
            Map<Long, String> paths = validateSavedFiles(prepared.inputs(), results, operationDirectory);

            // This loop runs only on the transaction-owning caller, after every worker succeeded.
            for (TopicInput input : prepared.inputs()) {
                int changed = topics.assignPdfPath(prepared.bookId(), input.topicId(), input.startPage(),
                        input.endPage(), paths.get(input.topicId()));
                if (changed != 1) {
                    throw new TopicPdfUploadException("Topic " + input.topicId()
                            + " changed while its PDF was being generated; no PDF paths can be committed.");
                }
            }
            topics.flush();
            List<TopicDetails> responseTopics = prepared.responseTopics().stream().map(topic -> {
                TopicDetails response = topic.responseCopy();
                response.setPdfPath(paths.get(topic.getTopicId()));
                return response;
            }).toList();
            return new BookExtractionResult(savedBookDetails.authorDetails(), savedBookDetails.bookDetails(),
                    responseTopics, savedBookDetails.pageOffset(), null,
                    savedBookDetails.wholeBook(), savedBookDetails.fallbackReason());
        }
        catch (RuntimeException | Error failure) {
            // Cancellation is intentionally avoided: it does not stop a running CompletableFuture task.
            // Draining also covers submission rejection after earlier tasks have already started.
            awaitWorkers(futures);
            try {
                files.cleanupOperation(operationDirectory);
            }
            catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                logger.error("Could not clean failed topic PDF operation {}", operationDirectory, cleanupFailure);
            }
            throw failure;
        }
    }

    /** A quality preflight performs no writes or dispatch and does not mark the caller's transaction for rollback. */
    public void validateContents(BookExtractionResult savedBookDetails) {
        requireTransaction();
        prepare(savedBookDetails);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new TopicPdfUploadException("Topic PDFs must participate in the existing upload transaction.");
        }
    }

    private PreparedUpload prepare(BookExtractionResult saved) {
        if (saved == null || saved.bookDetails() == null || saved.authorDetails() == null
                || saved.authorDetails().getAuthorId() == null || saved.topicDetails() == null) {
            throw new TopicPdfUploadException("The complete saved book, author and topic response is required.");
        }
        BookDetails responseBook = saved.bookDetails();
        Long bookId = responseBook.getBookId();
        String bookName = responseBook.getBookName();
        if (bookId == null || bookId <= 0 || bookName == null || bookName.isBlank()) {
            throw new TopicPdfUploadException("The saved book ID and original book name are required.");
        }
        if (saved.uploadedFile() == null || saved.uploadedFile().getFileData() == null
                || saved.uploadedFile().getFileData().length == 0) {
            throw new TopicPdfUploadException("The original uploaded PDF must remain available until topic generation completes.");
        }
        byte[] pdfBytes = saved.uploadedFile().getFileData().clone();
        int totalPages;
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            totalPages = pdf.getNumberOfPages();
            if (totalPages <= 0) throw new TopicPdfUploadException("The original PDF is empty.");
            if (!saved.wholeBook() && !pdf.getCurrentAccessPermission().canExtractContent()) {
                throw new ContentsUnavailableException("The readable PDF does not allow topic extraction; retain the original document instead.");
            }
        }
        catch (IOException failure) {
            throw new TopicPdfUploadException("The original uploaded PDF is unreadable.", failure);
        }
        BookDetails persistedBook = books.findLockedByBookId(bookId).orElseThrow(() ->
                new TopicPdfUploadException("The current saved book no longer exists."));
        if (!Objects.equals(bookName, persistedBook.getBookName())
                || responseBook.getTotalPages() != totalPages || persistedBook.getTotalPages() != totalPages
                || persistedBook.getAuthorDetails() == null
                || !Objects.equals(saved.authorDetails().getAuthorId(), persistedBook.getAuthorDetails().getAuthorId())) {
            throw new TopicPdfUploadException("The saved book/author response does not match the current uploaded PDF and book.");
        }
        String reservedOffsetName = bookName + "_offset";
        Map<Long, TopicDetails> responseEntries = validatedTopics(saved.topicDetails(), bookId, saved.wholeBook());
        Map<Long, TopicDetails> persistedEntries = validatedTopics(topics.findAllLockedForBook(bookId), bookId, saved.wholeBook());
        if (saved.wholeBook()) {
            return prepareWholeBook(saved, persistedBook, responseEntries, persistedEntries, pdfBytes, totalPages);
        }
        List<TopicDetails> metadata = persistedEntries.values().stream()
                .filter(topic -> reservedOffsetName.equals(topic.getTopicName())).toList();
        if (metadata.size() != 1) {
            throw new ContentsUnavailableException("The current book must have exactly one saved offset topic before generating PDFs.");
        }
        TopicDetails offsetTopic = metadata.getFirst();
        Integer offset = offsetTopic.getEndPage();
        if (!Objects.equals(offsetTopic.getStartPage(), 1) || offset == null || offset < 0 || offset >= totalPages
                || !Objects.equals(offsetTopic.getDetails(), bookName + "_offset detail")
                || offsetTopic.getPrintedStartPage() != null || offsetTopic.getPrintedEndPage() != null) {
            throw new ContentsUnavailableException("The saved offset topic has inconsistent metadata or prefix pages.");
        }
        Map<Long, TopicDetails> responseTopics = realTopics(responseEntries, reservedOffsetName);
        Map<Long, TopicDetails> persistedTopics = realTopics(persistedEntries, reservedOffsetName);
        for (TopicDetails response : responseEntries.values()) {
            if (reservedOffsetName.equals(response.getTopicName())
                    && (!Objects.equals(response.getTopicId(), offsetTopic.getTopicId())
                            || !Objects.equals(response.getStartPage(), offsetTopic.getStartPage())
                            || !Objects.equals(response.getEndPage(), offsetTopic.getEndPage())
                            || !Objects.equals(response.getDetails(), offsetTopic.getDetails())
                            || !Objects.equals(response.getPdfPath(), offsetTopic.getPdfPath())
                            || response.getPrintedStartPage() != null || response.getPrintedEndPage() != null)) {
                throw new TopicPdfUploadException("The nested offset topic response differs from the saved metadata.");
            }
        }
        if (responseTopics.isEmpty() && persistedTopics.isEmpty()) {
            throw new ContentsUnavailableException("The contents contain no usable topics.");
        }
        if (!responseTopics.keySet().equals(persistedTopics.keySet())) {
            throw new TopicPdfUploadException("The nested topic response must contain every current-book real topic exactly once.");
        }
        List<TopicInput> inputs = new ArrayList<>();
        List<TopicDetails> snapshots = new ArrayList<>();
        if (offsetTopic.getPdfPath() != null) {
            throw new TopicPdfUploadException("Topic " + offsetTopic.getTopicId() + " already has a PDF path; existing files are preserved.");
        }
        inputs.add(new TopicInput(offsetTopic.getTopicId(), offsetTopic.getTopicName(), 1, offset, true));
        snapshots.add(offsetTopic.responseCopy());
        int nextPage = offset + 1;
        for (TopicDetails response : responseTopics.values().stream()
                .sorted(Comparator.comparing(TopicDetails::getStartPage, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TopicDetails::getTopicId)).toList()) {
            TopicDetails persisted = persistedTopics.get(response.getTopicId());
            if (!Objects.equals(response.getTopicName(), persisted.getTopicName())
                    || !Objects.equals(response.getStartPage(), persisted.getStartPage())
                    || !Objects.equals(response.getEndPage(), persisted.getEndPage())) {
                throw new TopicPdfUploadException("Topic " + response.getTopicId() + " has stale title or PDF page values.");
            }
            if (response.getPdfPath() != null || persisted.getPdfPath() != null) {
                throw new TopicPdfUploadException("Topic " + response.getTopicId() + " already has a PDF path; existing files are preserved.");
            }
            Integer start = response.getStartPage();
            Integer end = response.getEndPage();
            if (start == null || end == null || start <= 0 || start > end || end > totalPages) {
                throw new ContentsUnavailableException("Topic " + response.getTopicId() + " has an invalid inclusive PDF page range.");
            }
            if (start != nextPage) {
                throw new ContentsUnavailableException("Topic " + response.getTopicId()
                        + " leaves a gap or overlaps another PDF range; merging the saved pieces would not reproduce the book.");
            }
            nextPage = end + 1;
            inputs.add(new TopicInput(response.getTopicId(), response.getTopicName(), start, end, false));
            snapshots.add(response.responseCopy());
        }
        if (nextPage != totalPages + 1) {
            throw new ContentsUnavailableException("The saved topic ranges omit trailing PDF pages; the final topic must include the book's final page.");
        }
        Set<String> names = new HashSet<>();
        for (TopicInput input : inputs) {
            String fileName;
            try {
                fileName = TopicPdfFileStore.topicFileName(input.topicName()).toLowerCase(Locale.ROOT);
            }
            catch (IllegalArgumentException invalidName) {
                throw new ContentsUnavailableException("A contents topic cannot form a usable PDF filename: "
                        + input.topicName() + ". " + invalidName.getMessage());
            }
            if (!names.add(fileName)) {
                throw new ContentsUnavailableException("Topic PDF names collide after Windows filename normalization: " + input.topicName());
            }
        }
        TopicPdfFileStore.bookFolderName(bookName);
        return new PreparedUpload(bookId, bookName, pdfBytes, List.copyOf(inputs), List.copyOf(snapshots), false);
    }

    private static PreparedUpload prepareWholeBook(BookExtractionResult saved, BookDetails persistedBook,
            Map<Long, TopicDetails> responseEntries, Map<Long, TopicDetails> persistedEntries,
            byte[] pdfBytes, int totalPages) {
        if (responseEntries.size() != 1 || persistedEntries.size() != 1
                || !responseEntries.keySet().equals(persistedEntries.keySet())) {
            throw new TopicPdfUploadException("A whole-book upload must contain exactly one saved topic and no offset metadata.");
        }
        TopicDetails response = responseEntries.values().iterator().next();
        TopicDetails persisted = persistedEntries.get(response.getTopicId());
        for (TopicDetails topic : List.of(response, persisted)) {
            if (!Objects.equals(topic.getTopicName(), saved.bookDetails().getBookName())
                    || !Objects.equals(topic.getDetails(), saved.bookDetails().getBookDetails())
                    || !Objects.equals(topic.getStartPage(), 1) || !Objects.equals(topic.getEndPage(), totalPages)
                    || topic.getPrintedStartPage() != null || topic.getPrintedEndPage() != null) {
                throw new TopicPdfUploadException("The whole-book topic must match the book details and cover physical pages 1 through the final page.");
            }
            if (topic.getPdfPath() != null) {
                throw new TopicPdfUploadException("The whole-book topic already has a PDF path; existing files are preserved.");
            }
        }
        if (!Objects.equals(saved.bookDetails().getBookDetails(), persistedBook.getBookDetails())) {
            throw new TopicPdfUploadException("The saved book description changed before whole-book generation.");
        }
        TopicPdfFileStore.bookFolderName(persistedBook.getBookName());
        TopicPdfFileStore.topicFileName(response.getTopicName());
        return new PreparedUpload(persistedBook.getBookId(), persistedBook.getBookName(), pdfBytes,
                List.of(new TopicInput(response.getTopicId(), response.getTopicName(), 1, totalPages, false)),
                List.of(response.responseCopy()), true);
    }

    private static Map<Long, TopicDetails> validatedTopics(List<TopicDetails> entries, Long bookId, boolean wholeBook) {
        Map<Long, TopicDetails> all = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (TopicDetails topic : entries) {
            if (topic == null || topic.getTopicId() == null || topic.getTopicId() <= 0
                    || topic.getBookDetails() == null || !bookId.equals(topic.getBookDetails().getBookId())
                    || !seen.add(topic.getTopicId())) {
                throw new TopicPdfUploadException("The topic response contains an unsaved, duplicate or foreign-book topic.");
            }
            if (topic.getTopicName() == null || topic.getTopicName().isBlank()) {
                if (!wholeBook) throw new ContentsUnavailableException("Topic " + topic.getTopicId() + " has no name.");
                throw new TopicPdfUploadException("Topic " + topic.getTopicId() + " has no name.");
            }
            all.put(topic.getTopicId(), topic);
        }
        return all;
    }

    private static Map<Long, TopicDetails> realTopics(Map<Long, TopicDetails> entries, String reservedName) {
        Map<Long, TopicDetails> real = new LinkedHashMap<>();
        entries.forEach((id, topic) -> { if (!reservedName.equals(topic.getTopicName())) real.put(id, topic); });
        return real;
    }

    private static Map<Long, String> validateSavedFiles(List<TopicInput> inputs, List<SavedTopicPdf> savedFiles,
            Path operationDirectory) {
        Map<Long, String> paths = new HashMap<>();
        Set<Path> seenPaths = new HashSet<>();
        Path operationRoot = operationDirectory.toAbsolutePath().normalize();
        for (int i = 0; i < inputs.size(); i++) {
            SavedTopicPdf saved = savedFiles.get(i);
            if (saved == null || !inputs.get(i).topicId().equals(saved.topicId()) || saved.pdfPath() == null
                    || saved.pdfPath().isBlank() || saved.pdfPath().length() > 2048) {
                throw new TopicPdfUploadException("A topic PDF worker returned invalid saved-file information.");
            }
            Path path = Path.of(saved.pdfPath()).toAbsolutePath().normalize();
            if (!Objects.equals(path.getParent(), operationRoot)
                    || !path.getFileName().toString().equals(TopicPdfFileStore.topicFileName(inputs.get(i).topicName()))
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !seenPaths.add(path)) {
                throw new TopicPdfUploadException("A topic PDF file is missing, duplicated or outside this operation's directory.");
            }
            paths.put(saved.topicId(), path.toString());
        }
        return Map.copyOf(paths);
    }

    private static void awaitWorkers(List<CompletableFuture<SavedTopicPdf>> futures) {
        // allOf waits for every future, including after one fails; handle keeps cleanup paths non-throwing.
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> null).join();
    }

    private void registerCompletionCleanup(Path operationDirectory) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                try {
                    if (status == STATUS_COMMITTED) files.retainOperation(operationDirectory);
                    else files.cleanupOperation(operationDirectory);
                }
                catch (RuntimeException failure) {
                    logger.error("Topic PDF completion cleanup failed for {} (transaction status {})",
                            operationDirectory, status, failure);
                }
            }
        });
    }

    private record TopicInput(Long topicId, String topicName, int startPage, int endPage, boolean offset) { }
    private record PreparedUpload(Long bookId, String bookName, byte[] pdfBytes, List<TopicInput> inputs,
            List<TopicDetails> responseTopics, boolean wholeBook) { }

    public static class TopicPdfUploadException extends RuntimeException {
        public TopicPdfUploadException(String message) { super(message); }
        public TopicPdfUploadException(String message, Throwable cause) { super(message, cause); }
    }
}
