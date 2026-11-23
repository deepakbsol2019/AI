package com.book.upload.controller;
import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.book.dto.UploadUserResponseDto;
@RequestMapping("/api")
public interface UploadFileController {

	@PostMapping(value = "/upload-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<UploadUserResponseDto> uploadFile(@RequestParam(name = "file") MultipartFile multipartFile)
			throws IOException;
}
