package com.book.upload.controller.impl;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.book.dto.UploadFileDtlsDto;
import com.book.entity.BookUploadStatus;
import com.book.upload.controller.UploadFileController;
import com.book.upload.service.BookUploadStatusService;
import com.book.upload.service.BookUploadSubmissionService;
import com.book.user.security.TenantContext;
import com.book.dto.UploadHistoryPage;

@RestController
public class UploadFileControllerImpl implements UploadFileController {

	private final BookUploadSubmissionService submissions;
	private final BookUploadStatusService statuses;

	public UploadFileControllerImpl(BookUploadSubmissionService submissions, BookUploadStatusService statuses) {
		this.submissions = submissions;
		this.statuses = statuses;
	}

	@Override
	public ResponseEntity<BookUploadStatus> uploadFile(MultipartFile multipartFile) throws IOException {
		TenantContext.requireUserId();
		String originalFileName = multipartFile.getOriginalFilename();
		if (originalFileName == null || originalFileName.isBlank() || originalFileName.length()>1024 || multipartFile.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A named, non-empty PDF upload is required.");
		}
		// Multipart resources belong to this request. Only independent bytes may outlive its cleanup.
		byte[] pdfBytes = multipartFile.getBytes();
		if (pdfBytes.length == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a non-empty PDF.");
		}
        // Browser MIME/extension are hints; the server recognizes PDF content before acceptance.
        // Full parsing and all existing PDF processing remain in the background worker.
        boolean pdf=false;
        for(int offset=0;offset<=Math.min(1024,pdfBytes.length-5);offset++) {
            if(pdfBytes[offset]=='%' && pdfBytes[offset+1]=='P' && pdfBytes[offset+2]=='D'
                    && pdfBytes[offset+3]=='F' && pdfBytes[offset+4]=='-'){pdf=true;break;}
        }
        if(!pdf)throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only PDF documents are supported. The uploaded content is not a PDF.");
		UploadFileDtlsDto uploadedFile = new UploadFileDtlsDto(originalFileName, pdfBytes,
				"application/pdf", null, null, (long) pdfBytes.length);
		BookUploadStatus savedStatus = submissions.submit(uploadedFile);
		URI statusLocation = URI.create("/api/upload-status/" + savedStatus.getBookUploadId());
		HttpStatus responseStatus = BookUploadStatus.FAILED.equals(savedStatus.getStatus())
				? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.ACCEPTED;
		return ResponseEntity.status(responseStatus).location(statusLocation).body(savedStatus);
	}

	@Override
	public ResponseEntity<BookUploadStatus> uploadStatus(Long bookUploadId) {
		TenantContext.requireUserId();
		if (bookUploadId == null || bookUploadId <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive book upload ID is required.");
		}
		return ResponseEntity.ok(statuses.get(bookUploadId));
	}
    @Override
    public ResponseEntity<UploadHistoryPage> history(int page,int size) {
        return ResponseEntity.ok(UploadHistoryPage.from(statuses.history(page,size)));
    }
}
