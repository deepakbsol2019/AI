package com.book.upload.topic;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.book.dto.BookExtractionResult;
import com.book.dto.UploadFileDtlsDto;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import com.book.upload.topic.TopicPdfFileStore.SavedTopicPdf;
import com.book.upload.topic.TopicPdfUploadService.TopicPdfUploadException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import com.book.extraction.ContentsUnavailableException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TopicPdfUploadServiceTests {
    @TempDir Path root;
    private final TopicPdfFileStore files = mock(TopicPdfFileStore.class);
    private final BookDetailsRepository books = mock(BookDetailsRepository.class);
    private final TopicDetailsRepository topics = mock(TopicDetailsRepository.class);
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicReference<Thread> databaseOwner = new AtomicReference<>();
    private BookExtractionResult saved;
    private List<TopicDetails> stored;
    private Path operation;
    private TopicPdfUploadService service;

    @BeforeEach
    void prepareInputAndTransaction() throws Exception {
        beginTransaction();
        databaseOwner.set(Thread.currentThread());
        AuthorDetails author = new AuthorDetails();
        author.setAuthorId(7L);
        author.setAuthorName("Writer");
        BookDetails book = new BookDetails();
        book.setBookId(44L);
        book.setBookName("Original Book Name");
        book.setTotalPages(3);
        book.setAuthorDetails(author);
        TopicDetails first = topic(book, 12L, "Opening", 1, 2);
        TopicDetails second = topic(book, 25L, "Second", 3, 3);
        TopicDetails metadata = topic(book, 1L, "Original Book Name_offset", 1, 0);
        metadata.setDetails("Original Book Name_offset detail");
        metadata.setPrintedStartPage(null);
        metadata.setPrintedEndPage(null);
        stored = List.of(metadata, first, second);
        UploadFileDtlsDto upload = new UploadFileDtlsDto();
        upload.setFileName("original.pdf");
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 3; i++) pdf.addPage(new PDPage());
            pdf.save(output);
            upload.setFileData(output.toByteArray());
        }
        saved = new BookExtractionResult(author, book, List.of(second, metadata, first)).withUploadedFile(upload);
        when(books.findLockedByBookId(44L)).thenAnswer(invocation -> {
            assertDatabaseThread();
            return Optional.of(book);
        });
        when(topics.findAllLockedForBook(44L)).thenAnswer(invocation -> {
            assertDatabaseThread();
            return stored;
        });
        when(topics.assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString())).thenAnswer(invocation -> {
            assertDatabaseThread();
            return 1;
        });
        operation = root.resolve("Original Book Name");
        when(files.createOperationDirectory(44L, "Original Book Name")).thenAnswer(invocation -> Files.createDirectory(operation));
        when(files.extractAndSave(any(byte[].class), anyLong(), anyString(), anyInt(), anyInt(), any(Path.class)))
                .thenAnswer(invocation -> saveFile(invocation.getArgument(1), invocation.getArgument(5)));
        when(files.extractOffsetAndSave(any(byte[].class), anyLong(), anyString(), anyInt(), any(Path.class)))
                .thenAnswer(invocation -> saveFile(invocation.getArgument(1), invocation.getArgument(4)));
        doAnswer(invocation -> {
            Path directory = invocation.getArgument(0);
            if (Files.exists(directory)) {
                try (var entries = Files.list(directory)) {
                    for (Path entry : entries.toList()) Files.delete(entry);
                }
                Files.delete(directory);
            }
            return null;
        }).when(files).cleanupOperation(any(Path.class));
        service = new TopicPdfUploadService(workers, files, books, topics);
    }

    @AfterEach
    void finishTransactionAndWorkers() throws Exception {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void wholeBookModeDispatchesOneOriginalCopyAndSavesOnlyItsPath() throws Exception {
        saved = wholeBookInput();
        byte[] original = saved.uploadedFile().getFileData().clone();
        when(files.saveWholeBook(any(), eq(12L), eq("Original Book Name"), eq(operation))).thenAnswer(invocation -> {
            assertThat(Thread.currentThread()).isNotSameAs(databaseOwner.get());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.<byte[]>getArgument(0)).containsExactly(original);
            Path path = operation.resolve("Original Book Name.pdf");
            Files.write(path, invocation.<byte[]>getArgument(0));
            return new SavedTopicPdf(12L, path.toString());
        });

        BookExtractionResult result = service.uploadTopic(saved);

        assertThat(result.wholeBook()).isTrue();
        assertThat(result.fallbackReason()).isEqualTo("Contents unavailable");
        assertThat(result.uploadedFile()).isNull();
        assertThat(result.topicDetails()).singleElement().satisfies(topic -> {
            assertThat(topic.getTopicName()).isEqualTo("Original Book Name");
            assertThat(topic.getDetails()).isEqualTo("The complete book description");
            assertThat(topic.getStartPage()).isEqualTo(1);
            assertThat(topic.getEndPage()).isEqualTo(3);
            assertThat(topic.getPrintedStartPage()).isNull();
            assertThat(topic.getPrintedEndPage()).isNull();
            assertThat(Path.of(topic.getPdfPath())).hasFileName("Original Book Name.pdf");
        });
        verify(files).saveWholeBook(any(), eq(12L), eq("Original Book Name"), eq(operation));
        verify(files, never()).extractAndSave(any(), anyLong(), anyString(), anyInt(), anyInt(), any());
        verify(files, never()).extractOffsetAndSave(any(), anyLong(), anyString(), anyInt(), any());
        verify(topics).assignPdfPath(eq(44L), eq(12L), eq(1), eq(3), anyString());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(files).retainOperation(operation);
    }

    @Test
    void canonicalOffsetMetadataStoresTheOriginalPdfOnlyInItsTopicPath() throws Exception {
        saved = wholeBookInput();
        TopicDetails metadata = saved.topicDetails().getFirst();
        String reservedName = saved.bookDetails().getBookName() + "_offset";
        metadata.setTopicName(reservedName);
        metadata.setDetails(reservedName + " detail");
        byte[] original = saved.uploadedFile().getFileData().clone();
        when(files.saveWholeBook(any(), eq(12L), eq(reservedName), eq(operation))).thenAnswer(invocation -> {
            Path path = operation.resolve(reservedName + ".pdf");
            Files.write(path, invocation.<byte[]>getArgument(0));
            return new SavedTopicPdf(12L, path.toString());
        });

        BookExtractionResult result = service.uploadTopic(saved);

        assertThat(result.wholeBook()).isTrue();
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("The complete book description");
        assertThat(result.topicDetails()).singleElement().satisfies(topic -> {
            assertThat(topic.getTopicName()).isEqualTo(reservedName);
            assertThat(topic.getDetails()).isEqualTo(reservedName + " detail");
            assertThat(topic.getStartPage()).isEqualTo(1);
            assertThat(topic.getEndPage()).isEqualTo(3);
            assertThat(topic.getPrintedStartPage()).isNull();
            assertThat(topic.getPrintedEndPage()).isNull();
            assertThat(Path.of(topic.getPdfPath())).isRegularFile().hasFileName(reservedName + ".pdf");
        });
        assertThat(Files.readAllBytes(Path.of(result.topicDetails().getFirst().getPdfPath()))).containsExactly(original);
        verify(topics).assignPdfPath(eq(44L), eq(12L), eq(1), eq(3), anyString());
        verify(topics, never()).save(any());
        verify(files, never()).extractOffsetAndSave(any(), anyLong(), anyString(), anyInt(), any());
        verify(files, never()).extractAndSave(any(), anyLong(), anyString(), anyInt(), anyInt(), any());
    }

    @Test
    void wholeBookWithoutOriginalPdfMetadataIsRejectedBeforeWritingFiles() {
        BookDetails book = saved.bookDetails();
        book.getTopics().clear();
        stored = List.of();
        saved = new BookExtractionResult(saved.authorDetails(), book, List.of())
                .withUploadedFile(saved.uploadedFile()).withWholeBook("No detected contents");

        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(TopicPdfUploadException.class)
                .hasMessageContaining("exactly one saved original-PDF topic");
        verifyNoInteractions(files);
        verify(topics, never()).assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void wholeBookCopyFailureStillPropagatesAndCleansWithoutUpdatingAnyPath() {
        saved = wholeBookInput();
        when(files.saveWholeBook(any(), anyLong(), anyString(), any())).thenThrow(new IllegalStateException("Disk full"));

        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(files).cleanupOperation(operation);
        verify(topics, never()).assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void wholeBookModeRejectsExtraRowsAndInventedPrintedRangesBeforeIo() {
        BookExtractionResult whole = wholeBookInput();
        TopicDetails extra = topic(whole.bookDetails(), 99L, "Unexpected offset", 1, 0);
        stored = List.of(stored.getFirst(), extra);
        assertThatThrownBy(() -> service.uploadTopic(whole)).isInstanceOf(TopicPdfUploadException.class)
                .hasMessageContaining("exactly one");
        stored = List.of(whole.topicDetails().getFirst());
        stored.getFirst().setPrintedStartPage(1);
        assertThatThrownBy(() -> service.uploadTopic(whole)).isInstanceOf(TopicPdfUploadException.class)
                .hasMessageContaining("whole-book topic");
        verifyNoInteractions(files);
    }

    @Test
    void splitQualityPreflightDoesNotCreateFilesOrUpdateRows() {
        service.validateContents(saved);
        stored.get(1).setEndPage(1);

        assertThatThrownBy(() -> service.validateContents(saved)).isInstanceOf(ContentsUnavailableException.class)
                .hasMessageContaining("gap or overlaps");

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        verifyNoInteractions(files);
        verify(topics, never()).assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
        verify(topics, never()).flush();
    }

    @Test
    void splitQualityPreflightDoesNotDowngradeDatabaseOrExistingPathFailures() {
        stored.get(1).setPdfPath("already-uploaded.pdf");
        assertThatThrownBy(() -> service.validateContents(saved)).isInstanceOf(TopicPdfUploadException.class)
                .isNotInstanceOf(ContentsUnavailableException.class).hasMessageContaining("already has a PDF path");
        stored.get(1).setPdfPath(null);
        when(topics.findAllLockedForBook(44L)).thenThrow(new DataIntegrityViolationException("database unavailable"));
        assertThatThrownBy(() -> service.validateContents(saved)).isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ContentsUnavailableException.class);
        verifyNoInteractions(files);
    }

    @Test
    void workersOnlyWriteFilesAndCallerUpdatesPathsAfterEveryWorkerSucceeded() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        when(files.extractAndSave(any(), anyLong(), anyString(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            SavedTopicPdf result = saveFile(invocation.getArgument(1), invocation.getArgument(5));
            completed.incrementAndGet();
            return result;
        });
        when(topics.assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString())).thenAnswer(invocation -> {
            assertDatabaseThread();
            assertThat(completed.get()).isEqualTo(2);
            return 1;
        });

        BookExtractionResult result = service.uploadTopic(saved);

        assertThat(result.bookDetails()).isSameAs(saved.bookDetails());
        assertThat(result.authorDetails()).isSameAs(saved.authorDetails());
        assertThat(result.topicDetails()).extracting(TopicDetails::getTopicId).containsExactly(1L, 12L, 25L);
        assertThat(result.topicDetails()).allSatisfy(topic -> assertThat(Files.isRegularFile(Path.of(topic.getPdfPath()))).isTrue());
        assertThat(result.uploadedFile()).isNull();
        assertThat(saved.topicDetails()).allSatisfy(topic -> assertThat(topic.getPdfPath()).isNull());
        verify(files, times(2)).extractAndSave(any(), anyLong(), anyString(), anyInt(), anyInt(), eq(operation));
        verify(files).extractOffsetAndSave(any(), eq(1L), eq("Original Book Name_offset"), eq(0), eq(operation));
        verify(topics, times(3)).assignPdfPath(eq(44L), anyLong(), anyInt(), anyInt(), anyString());
        verify(topics).assignPdfPath(eq(44L), eq(1L), eq(1), eq(0), anyString());
        verify(files, never()).cleanupOperation(any());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(files).retainOperation(operation);
        try (var entries = Files.list(operation)) {
            assertThat(entries.toList()).hasSize(3);
        }
    }

    @Test
    void immutableWorkInputsDoNotFollowLaterChangesToTheResponseOrUploadBytes() throws Exception {
        byte firstByte = saved.uploadedFile().getFileData()[0];
        CountDownLatch mutated = new CountDownLatch(1);
        when(files.extractAndSave(any(), anyLong(), anyString(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(1);
            if (id == 12L) {
                saved.uploadedFile().getFileData()[0] = 0;
                saved.topicDetails().getFirst().setTopicName("Changed after dispatch");
                saved.topicDetails().getFirst().setEndPage(1);
                mutated.countDown();
            } else {
                assertThat(mutated.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(invocation.<String>getArgument(2)).isEqualTo("Second");
                assertThat(invocation.<Integer>getArgument(3)).isEqualTo(3);
                assertThat(invocation.<Integer>getArgument(4)).isEqualTo(3);
            }
            assertThat(invocation.<byte[]>getArgument(0)[0]).isEqualTo(firstByte);
            return saveFile(id, invocation.getArgument(5));
        });

        BookExtractionResult result = service.uploadTopic(saved);

        assertThat(result.topicDetails()).extracting(TopicDetails::getTopicName)
                .containsExactly("Original Book Name_offset", "Opening", "Second");
        assertThat(result.topicDetails()).extracting(TopicDetails::getEndPage).containsExactly(0, 2, 3);
    }

    @Test
    void workerFailureWaitsForLateWorkerBeforeCleaningAndNeverUpdatesTheDatabase() throws Exception {
        CountDownLatch firstFailed = new CountDownLatch(1);
        CountDownLatch lateStarted = new CountDownLatch(1);
        CountDownLatch releaseLate = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicBoolean lateFinished = new AtomicBoolean();
        when(files.extractAndSave(any(), anyLong(), anyString(), anyInt(), anyInt(), any())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(1);
            if (id == 12L) {
                firstFailed.countDown();
                throw new IllegalStateException("extraction exhausted retries");
            }
            lateStarted.countDown();
            assertThat(releaseLate.await(5, TimeUnit.SECONDS)).isTrue();
            SavedTopicPdf result = saveFile(id, invocation.getArgument(5));
            lateFinished.set(true);
            return result;
        });
        doAnswer(invocation -> {
            assertThat(lateFinished).isTrue();
            cleaned.countDown();
            return null;
        }).when(files).cleanupOperation(operation);

        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> callInTransaction(service));
            assertThat(firstFailed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(lateStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(cleaned.await(100, TimeUnit.MILLISECONDS)).isFalse();
            releaseLate.countDown();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(cleaned.await(5, TimeUnit.SECONDS)).isTrue();
        }
        verify(topics, never()).assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void rejectedSubmissionDrainsPreviouslySubmittedTasksBeforeCleanup() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectsSecond = task -> {
            if (submissions.incrementAndGet() == 2) throw new RejectedExecutionException("queue full");
            workers.execute(task);
        };
        var rejectingService = new TopicPdfUploadService(rejectsSecond, files, books, topics);
        when(files.extractOffsetAndSave(any(), anyLong(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            running.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            SavedTopicPdf result = saveFile(invocation.getArgument(1), invocation.getArgument(4));
            completed.set(true);
            return result;
        });
        doAnswer(invocation -> {
            assertThat(completed).isTrue();
            return null;
        }).when(files).cleanupOperation(operation);
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> callInTransaction(rejectingService));
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result.isDone()).isFalse();
            verify(files, never()).cleanupOperation(operation);
            release.countDown();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RejectedExecutionException.class);
        }
        verify(topics, never()).assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void databaseFailureIsNotRetriedAndCleansAllGeneratedFiles() throws Exception {
        when(topics.assignPdfPath(eq(44L), eq(25L), anyInt(), anyInt(), anyString()))
                .thenThrow(new DataIntegrityViolationException("database rejected path"));
        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(Files.exists(operation)).isFalse();
        verify(files, times(2)).extractAndSave(any(), anyLong(), anyString(), anyInt(), anyInt(), any());
        verify(topics, times(1)).assignPdfPath(eq(44L), eq(25L), anyInt(), anyInt(), anyString());
        verify(files).cleanupOperation(operation);
        verify(files, never()).retainOperation(any());
    }

    @Test
    void outerRollbackAfterSuccessfulGenerationAlsoCleansOperationFiles() throws Exception {
        BookExtractionResult result = service.uploadTopic(saved);
        assertThat(result.topicDetails()).allSatisfy(topic -> assertThat(Files.exists(Path.of(topic.getPdfPath()))).isTrue());
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(Files.exists(operation)).isFalse();
        verify(files).cleanupOperation(operation);
        verify(files, never()).retainOperation(any());
    }

    @Test
    void invalidOrIncompleteRangesFailBeforeDispatchAndExistingFilesRemainUntouched() {
        saved.topicDetails().getFirst().setEndPage(9);
        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(ContentsUnavailableException.class);
        verifyNoInteractions(files);
        verify(topics, never()).assignPdfPath(anyLong(), anyLong(), anyInt(), anyInt(), anyString());
    }

    @Test
    void partialNestedResponsesAndAlreadyAssignedPathsAreRejectedBeforeIo() {
        BookExtractionResult partial = new BookExtractionResult(saved.authorDetails(), saved.bookDetails(),
                List.of(saved.topicDetails().getFirst())).withUploadedFile(saved.uploadedFile());
        assertThatThrownBy(() -> service.uploadTopic(partial)).isInstanceOf(TopicPdfUploadException.class)
                .hasMessageContaining("every current-book real topic");
        saved.topicDetails().getFirst().setPdfPath("previous-operation.pdf");
        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(TopicPdfUploadException.class)
                .hasMessageContaining("already has a PDF path");
        verifyNoInteractions(files);
    }

    @Test
    void missingOuterTransactionIsRejectedBeforeAnyWork() {
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(TopicPdfUploadException.class)
                .hasMessageContaining("existing upload transaction");
        verifyNoInteractions(files, books, topics);
    }

    @Test
    void offsetIsLoadedFromSavedRowsWhenTheNestedResponseContainsOnlyRealTopics() {
        BookExtractionResult realOnly = new BookExtractionResult(saved.authorDetails(), saved.bookDetails(),
                List.of(stored.get(1), stored.get(2))).withUploadedFile(saved.uploadedFile());
        BookExtractionResult result = service.uploadTopic(realOnly);
        assertThat(result.topicDetails()).extracting(TopicDetails::getTopicId).containsExactly(1L, 12L, 25L);
        verify(files).extractOffsetAndSave(any(), eq(1L), eq("Original Book Name_offset"), eq(0), eq(operation));
    }

    @Test
    void missingOffsetAndSanitizedFilenameCollisionsFailBeforeIo() {
        stored = List.of(stored.get(1), stored.get(2));
        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(ContentsUnavailableException.class)
                .hasMessageContaining("exactly one saved offset");
        TopicDetails offset = saved.topicDetails().stream().filter(topic -> topic.getTopicId() == 1L).findFirst().orElseThrow();
        stored = List.of(offset, stored.get(0), stored.get(1));
        stored.get(1).setTopicName("A/B");
        stored.get(2).setTopicName("A:B");
        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(ContentsUnavailableException.class)
                .hasMessageContaining("names collide");
        verifyNoInteractions(files);
    }

    @Test
    void gapsInPhysicalPageCoverageCannotProduceAnIncompleteReconstructedBook() {
        stored.get(1).setEndPage(1);
        assertThatThrownBy(() -> service.uploadTopic(saved)).isInstanceOf(ContentsUnavailableException.class)
                .hasMessageContaining("gap or overlaps");
        verifyNoInteractions(files);
    }

    private SavedTopicPdf saveFile(Long topicId, Path directory) throws Exception {
        assertThat(Thread.currentThread()).isNotSameAs(databaseOwner.get());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        String name = topicId == 1L ? "Original Book Name_offset" : topicId == 12L ? "Opening" : "Second";
        Path file = directory.resolve(name + ".pdf");
        Files.write(file, new byte[] {1, 2, 3});
        return new SavedTopicPdf(topicId, file.toString());
    }

    private BookExtractionResult wholeBookInput() {
        BookDetails book = saved.bookDetails();
        book.setBookDetails("The complete book description");
        book.getTopics().clear();
        TopicDetails topic = topic(book, 12L, book.getBookName(), 1, 3);
        topic.setDetails(book.getBookDetails());
        topic.setPrintedStartPage(null);
        topic.setPrintedEndPage(null);
        stored = List.of(topic);
        return new BookExtractionResult(saved.authorDetails(), book, List.of(topic), null,
                saved.uploadedFile(), true, "Contents unavailable");
    }

    private void assertDatabaseThread() {
        assertThat(Thread.currentThread()).isSameAs(databaseOwner.get());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    }

    private BookExtractionResult callInTransaction(TopicPdfUploadService uploadService) {
        beginTransaction();
        databaseOwner.set(Thread.currentThread());
        try {
            return uploadService.uploadTopic(saved);
        }
        finally {
            finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void finishTransaction(int status) {
        List<TransactionSynchronization> callbacks = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        try {
            callbacks.forEach(callback -> callback.afterCompletion(status));
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static TopicDetails topic(BookDetails owner, Long id, String name, int start, int end) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(id);
        topic.setTopicName(name);
        topic.setStartPage(start);
        topic.setEndPage(end);
        topic.setPrintedStartPage(start);
        topic.setPrintedEndPage(end);
        owner.addTopic(topic);
        return topic;
    }
}
