package com.book.upload.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.book.dto.UploadFileDtlsDto;
import com.book.entity.BookUploadStatus;
import com.book.user.security.TenantContext;

/** Commits the acknowledgement before dispatch, without waiting for model calls or topic PDF work. */
@Service
public class BookUploadSubmissionService {
    private static final Logger logger = LoggerFactory.getLogger(BookUploadSubmissionService.class);
    private final BookUploadStatusService statuses;
    private final BackgroundBookUploadService background;
    private final Executor executor;

    public BookUploadSubmissionService(BookUploadStatusService statuses, BackgroundBookUploadService background,
            @Qualifier("bookUploadExecutor") Executor executor) {
        this.statuses = statuses;
        this.background = background;
        this.executor = executor;
    }

    public BookUploadStatus submit(UploadFileDtlsDto uploadedFile) {
        long tenantId = TenantContext.requireTenantId();
        if (uploadedFile == null || uploadedFile.getFileName() == null || uploadedFile.getFileName().isBlank()
                || uploadedFile.getFileData() == null || uploadedFile.getFileData().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An original filename and a non-empty PDF are required.");
        }
        // Multipart resources/streams belong to the HTTP request. Only owned bytes and scalar metadata cross threads.
        UploadFileDtlsDto source = new UploadFileDtlsDto();
        source.setFileName(uploadedFile.getFileName());
        source.setContentType(uploadedFile.getContentType());
        source.setFileData(uploadedFile.getFileData().clone());
        source.setFileSize((long) source.getFileData().length);

        BookUploadStatus acknowledgement = statuses.create(source.getFileName());
        source.setBookUploadId(acknowledgement.getBookUploadId());
        try {
            CompletableFuture.runAsync(() -> {
                try (var ignored = TenantContext.use(tenantId)) { background.process(source); }
            }, executor)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            logger.error("Background processing could not finish for book upload {}",
                                    source.getBookUploadId(), failure);
                        }
                    });
        }
        catch (RejectedExecutionException failure) {
            statuses.fail(source.getBookUploadId(), failure);
            return statuses.get(source.getBookUploadId());
        }
        // A detached snapshot remains inprogress even if a very small job finishes before JSON serialization.
        return acknowledgement;
    }
}
