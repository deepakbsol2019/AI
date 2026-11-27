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
	private final org.springframework.beans.factory.ObjectProvider<com.book.spreadsheet.upload.SpreadsheetUploadService> spreadsheets;

	public UploadFileControllerImpl(BookUploadSubmissionService submissions, BookUploadStatusService statuses,
            org.springframework.beans.factory.ObjectProvider<com.book.spreadsheet.upload.SpreadsheetUploadService> spreadsheets) {
		this.submissions = submissions;
		this.statuses = statuses;
        this.spreadsheets=spreadsheets;
	}

	@Override
	public ResponseEntity<BookUploadStatus> uploadFile(MultipartFile multipartFile) throws IOException {
		TenantContext.requireUserId();
		String originalFileName = multipartFile.getOriginalFilename();
		if (originalFileName == null || originalFileName.isBlank() || originalFileName.length()>1024 || multipartFile.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A named, non-empty document is required.");
		}
        if(multipartFile.getSize()>100L*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"The document exceeds 100 MiB.");
        byte[] header;
        try(var input=multipartFile.getInputStream()){header=input.readNBytes(1029);}
        // Browser MIME/extension are hints; the server recognizes PDF content before acceptance.
        // Full parsing and all existing PDF processing remain in the background worker.
        boolean pdf=false;
        for(int offset=0;offset<=Math.min(1024,header.length-5);offset++) {
            if(header[offset]=='%' && header[offset+1]=='P' && header[offset+2]=='D'
                    && header[offset+3]=='F' && header[offset+4]=='-'){pdf=true;break;}
        }
        if(!pdf){
            var format=com.book.spreadsheet.parse.SpreadsheetFormat.fromFilename(originalFileName);
            var result=spreadsheets.getObject().submit(multipartFile,format);
            BookUploadStatus receipt=result.upload();receipt.setFileId(result.fileId());receipt.setSheetCount(result.sheetCount());
            return accepted(receipt);
        }
		// Preserve the existing PDF worker's detached-byte contract.
		byte[] pdfBytes = multipartFile.getBytes();
		UploadFileDtlsDto uploadedFile = new UploadFileDtlsDto(originalFileName, pdfBytes,
				"application/pdf", null, null, (long) pdfBytes.length);
		BookUploadStatus savedStatus = submissions.submit(uploadedFile);
        return accepted(savedStatus);
	}
    private ResponseEntity<BookUploadStatus> accepted(BookUploadStatus savedStatus){
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
