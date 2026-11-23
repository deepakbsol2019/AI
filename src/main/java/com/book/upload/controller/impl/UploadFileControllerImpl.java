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
		String originalFileName = multipartFile.getOriginalFilename();
		if (originalFileName == null || originalFileName.isBlank() || multipartFile.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A named, non-empty PDF upload is required.");
		}
		// Multipart resources belong to this request. Only independent bytes may outlive its cleanup.
		byte[] pdfBytes = multipartFile.getBytes();
		if (pdfBytes.length == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a non-empty PDF.");
		}
		UploadFileDtlsDto uploadedFile = new UploadFileDtlsDto(originalFileName, pdfBytes,
				multipartFile.getContentType(), null, null, (long) pdfBytes.length);
		BookUploadStatus savedStatus = submissions.submit(uploadedFile);
		URI statusLocation = URI.create("/api/upload-status/" + savedStatus.getBookUploadId());
		HttpStatus responseStatus = BookUploadStatus.FAILED.equals(savedStatus.getStatus())
				? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.ACCEPTED;
		return ResponseEntity.status(responseStatus).location(statusLocation).body(savedStatus);
	}

	@Override
	public ResponseEntity<BookUploadStatus> uploadStatus(Long bookUploadId) {
		if (bookUploadId == null || bookUploadId <= 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A positive book upload ID is required.");
		}
		return ResponseEntity.ok(statuses.get(bookUploadId));
	}
}
