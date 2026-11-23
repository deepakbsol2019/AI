package com.book.upload.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.book.dto.UploadFileDtlsDto;
import com.book.dto.BookExtractionResult;
import com.book.extraction.BookMetadataExtractionService;
import com.book.upload.service.FileUploadService;
@Service
public class FileUploadServiceImpl implements FileUploadService {

	private static final Logger logger = LoggerFactory.getLogger(FileUploadServiceImpl.class);
	private final BookMetadataExtractionService extractionService;

	public FileUploadServiceImpl(BookMetadataExtractionService extractionService) {
		this.extractionService = extractionService;
	}

	@Override
	public BookExtractionResult uploadFile(UploadFileDtlsDto uploadedFile) {
		logger.info("Received file '{}' ({} bytes)", uploadedFile.getFileName(), uploadedFile.getFileSize());
		return extractionService.extract(uploadedFile.getFileData(), uploadedFile.getFileName());
	}

}
