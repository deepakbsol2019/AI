package com.book.upload.controller;
import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.book.entity.BookUploadStatus;
import com.book.dto.UploadHistoryPage;
@RequestMapping("/api")
public interface UploadFileController {

	@PostMapping(value = "/upload-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<BookUploadStatus> uploadFile(@RequestParam(name = "file") MultipartFile multipartFile)
			throws IOException;

	@GetMapping("/upload-status/{bookUploadId}")
	ResponseEntity<BookUploadStatus> uploadStatus(@PathVariable("bookUploadId") Long bookUploadId);

    @GetMapping("/my-uploads")
    ResponseEntity<UploadHistoryPage> history(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size);
}
