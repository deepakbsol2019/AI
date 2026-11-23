package com.book.upload.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.book.dto.UploadFileDtlsDto;
import com.book.entity.BookUploadStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookUploadSubmissionServiceTests {
    private final BookUploadStatusService statuses = mock(BookUploadStatusService.class);
    private final BackgroundBookUploadService background = mock(BackgroundBookUploadService.class);

    @Test
    void returnsTheSavedReceiptWithoutWaitingAndOwnsTheBackgroundBytes() {
        List<Runnable> pending = new ArrayList<>();
        BookUploadSubmissionService service = new BookUploadSubmissionService(statuses, background, pending::add);
        BookUploadStatus initial = status(7L, BookUploadStatus.IN_PROGRESS, "Uploading");
        when(statuses.create("original.pdf")).thenReturn(initial);
        byte[] original = {1, 2, 3};
        UploadFileDtlsDto input = source(original);

        assertThat(service.submit(input)).isSameAs(initial);

        verifyNoInteractions(background);
        assertThat(pending).hasSize(1);
        original[0] = 99;
        input.setFileName("changed.pdf");
        input.setBookUploadId(999L);
        input.setFileData(new byte[] {9});
        pending.getFirst().run();
        ArgumentCaptor<UploadFileDtlsDto> captured = ArgumentCaptor.forClass(UploadFileDtlsDto.class);
        verify(background).process(captured.capture());
        UploadFileDtlsDto frozen = captured.getValue();
        assertThat(frozen.getBookUploadId()).isEqualTo(7L);
        assertThat(frozen.getFileName()).isEqualTo("original.pdf");
        assertThat(frozen.getFileData()).containsExactly(1, 2, 3);
        assertThat(frozen.getFileSize()).isEqualTo(3L);
        assertThat(frozen.getInputStream()).isNull();
        assertThat(frozen.getResource()).isNull();
    }

    @Test
    void queueRejectionUpdatesAndReturnsTheSamePersistedUploadId() {
        RejectedExecutionException rejection = new RejectedExecutionException("Upload queue is full");
        Executor rejecting = job -> { throw rejection; };
        var service = new BookUploadSubmissionService(statuses, background, rejecting);
        when(statuses.create("original.pdf")).thenReturn(status(7L, BookUploadStatus.IN_PROGRESS, "Uploading"));
        var failed = status(7L, BookUploadStatus.FAILED, rejection.getMessage());
        when(statuses.get(7L)).thenReturn(failed);

        assertThat(service.submit(source(new byte[] {1}))).isSameAs(failed);

        var calls = inOrder(statuses);
        calls.verify(statuses).create("original.pdf");
        calls.verify(statuses).fail(7L, rejection);
        calls.verify(statuses).get(7L);
        verifyNoInteractions(background);
    }

    @Test
    void invalidInputDoesNotCreateAReceiptOrSubmitAJob() {
        var service = new BookUploadSubmissionService(statuses, background, job -> fail("No job expected"));
        assertThatThrownBy(() -> service.submit(source(new byte[0]))).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(statuses, background);
    }

    @Test
    void backgroundFailureIsRecordedAfterTheUploadTransactionCallHasFailed() {
        FileUploadService uploads = mock(FileUploadService.class);
        var worker = new BackgroundBookUploadService(uploads, statuses);
        var input = source(new byte[] {1});
        input.setBookUploadId(7L);
        var failure = new IllegalStateException("PDF saving failed");
        when(uploads.uploadFile(input)).thenThrow(failure);

        worker.process(input);

        var calls = inOrder(uploads, statuses);
        calls.verify(uploads).uploadFile(input);
        calls.verify(statuses).fail(7L, failure);
        verifyNoMoreInteractions(statuses);
    }

    @Test
    void backgroundSuccessDoesNotPerformAnIndependentPrematureStatusCommit() {
        FileUploadService uploads = mock(FileUploadService.class);
        var worker = new BackgroundBookUploadService(uploads, statuses);
        var input = source(new byte[] {1});
        input.setBookUploadId(7L);

        worker.process(input);

        verify(uploads).uploadFile(input);
        verifyNoInteractions(statuses); // The transactional pipeline owns the Success update.
    }

    private static UploadFileDtlsDto source(byte[] bytes) {
        UploadFileDtlsDto input = new UploadFileDtlsDto();
        input.setFileName("original.pdf");
        input.setContentType("application/pdf");
        input.setFileData(bytes);
        return input;
    }

    private static BookUploadStatus status(Long id, String value, String message) {
        BookUploadStatus status = new BookUploadStatus();
        status.setBookUploadId(id);
        status.setStatus(value);
        status.setMessage(message);
        return status;
    }
}
