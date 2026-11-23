package com.book.upload.controller.impl;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.book.constants.BookUploadStatus;
import com.book.dto.BookExtractionResult;
import com.book.dto.UploadFileDtlsDto;
import com.book.dto.UploadUserResponseDto;
import com.book.upload.controller.UploadFileController;
import com.book.upload.service.FileUploadService;

@RestController
public class UploadFileControllerImpl implements UploadFileController {

	private final FileUploadService fileUploadService;
	public UploadFileControllerImpl(FileUploadService fileUploadService) {
		this.fileUploadService=fileUploadService;
	}
	@Override
	public ResponseEntity<UploadUserResponseDto> uploadFile(MultipartFile multipartFile) throws IOException{
		try (InputStream inputStream = multipartFile.getInputStream()) {
			UploadFileDtlsDto uploadedFile = new UploadFileDtlsDto(multipartFile.getOriginalFilename(),
					multipartFile.getBytes(), multipartFile.getContentType(), inputStream,
					multipartFile.getResource(), multipartFile.getSize());
			BookExtractionResult extraction = fileUploadService.uploadFile(uploadedFile);
			UploadUserResponseDto response = new UploadUserResponseDto(multipartFile.getOriginalFilename(),
					LocalDateTime.now(), BookUploadStatus.SUCCESS);
			response.setExtraction(extraction);
			return ResponseEntity.ok(response);
		}
		
	}
}
