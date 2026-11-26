package com.book.upload.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import com.book.entity.BookUploadStatus;
import com.book.repository.BookUploadStatusRepository;
import com.book.user.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Receipts/failures survive book rollback; success commits together with the completed book. */
@Service
public class BookUploadStatusService {
    private final BookUploadStatusRepository statuses;

    public BookUploadStatusService(BookUploadStatusRepository statuses) {
        this.statuses = statuses;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookUploadStatus create(String originalFileName) {
        TenantContext.requireTenantId();
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("The original upload filename is required.");
        }
        BookUploadStatus status = new BookUploadStatus();
        status.setStatus(BookUploadStatus.IN_PROGRESS);
        status.setMessage(originalFileName
                + " upload service is uploading book topic by topic. please check final upload status after some time.");
        return statuses.saveAndFlush(status).responseCopy();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public BookUploadStatus get(Long uploadId) {
        return required(uploadId).responseCopy();
    }

    /** Called after the book transaction has ended unsuccessfully. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long uploadId, Throwable failure) {
        validateId(uploadId);
        if (failure == null) {
            throw new IllegalArgumentException("An upload failure is required.");
        }
        int changed = statuses.finishIfInProgress(uploadId, BookUploadStatus.IN_PROGRESS,
                BookUploadStatus.FAILED, failureMessage(failure));
        if (changed == 1) return;
        BookUploadStatus current = required(uploadId);
        if (BookUploadStatus.SUCCESS.equals(current.getStatus()) || BookUploadStatus.FAILED.equals(current.getStatus())) {
            return;
        }
        throw new IllegalStateException("Upload " + uploadId + " has an invalid status: " + current.getStatus());
    }

    /** The final status update must join the book/topic/path transaction, never commit ahead of it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void succeed(Long uploadId, String bookName) {
        validateId(uploadId);
        if (bookName == null || bookName.isBlank()) {
            throw new IllegalArgumentException("The successfully uploaded book name is required.");
        }
        int changed = statuses.finishIfInProgress(uploadId, BookUploadStatus.IN_PROGRESS,
                BookUploadStatus.SUCCESS, "uploaded " + bookName + " successfully.");
        if (changed != 1) {
            BookUploadStatus current = required(uploadId);
            throw new IllegalStateException("Upload " + uploadId + " is already " + current.getStatus()
                    + "; its final state cannot be replaced.");
        }
    }

    private BookUploadStatus required(Long uploadId) {
        validateId(uploadId);
        TenantContext.requireTenantId();
        BookUploadStatusRepository.CurrentStatus current = statuses.readCurrent(uploadId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No upload status exists for ID " + uploadId + "."));
        BookUploadStatus copy = new BookUploadStatus();
        copy.setBookUploadId(current.getBookUploadId());
        copy.setTenantId(current.getTenantId());
        copy.setStatus(current.getStatus());
        copy.setMessage(current.getMessage());
        return copy;
    }

    private static void validateId(Long uploadId) {
        if (uploadId == null || uploadId <= 0) {
            throw new IllegalArgumentException("A positive book upload ID is required.");
        }
    }

    private static String failureMessage(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (seen.add(current)) {
            if ((current instanceof CompletionException || current instanceof ExecutionException
                    || current instanceof InvocationTargetException || current instanceof UndeclaredThrowableException)
                    && current.getCause() != null && !seen.contains(current.getCause())) {
                current = current.getCause();
                continue;
            }
            if (current instanceof ResponseStatusException response && response.getReason() != null
                    && !response.getReason().isBlank()) {
                return response.getReason();
            }
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            if (current.getCause() == null || seen.contains(current.getCause())) break;
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }
}
