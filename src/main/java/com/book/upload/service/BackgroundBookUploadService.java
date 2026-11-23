package com.book.upload.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.book.dto.UploadFileDtlsDto;

/** Runs on the book executor; the proxied upload service owns this job's database transaction. */
@Service
public class BackgroundBookUploadService {
    private static final Logger logger = LoggerFactory.getLogger(BackgroundBookUploadService.class);
    private final FileUploadService uploads;
    private final BookUploadStatusService statuses;

    public BackgroundBookUploadService(FileUploadService uploads, BookUploadStatusService statuses) {
        this.uploads = uploads;
        this.statuses = statuses;
    }

    public void process(UploadFileDtlsDto source) {
        try {
            // Success is recorded inside this transaction, which must commit before the call returns.
            uploads.uploadFile(source);
        }
        catch (RuntimeException | Error failure) {
            // This method has no transaction: the upload proxy has already rolled back and cleaned its files.
            logger.error("Book upload {} failed", source.getBookUploadId(), failure);
            try {
                statuses.fail(source.getBookUploadId(), failure);
            }
            catch (RuntimeException statusFailure) {
                failure.addSuppressed(statusFailure);
                logger.error("Could not record failure for book upload {}", source.getBookUploadId(), statusFailure);
                throw failure;
            }
            if (failure instanceof Error error) throw error;
        }
    }
}
