package com.book.upload.service;

import com.book.dto.BookExtractionResult;
import com.book.dto.UploadFileDtlsDto;

public interface FileUploadService {

	BookExtractionResult uploadFile(UploadFileDtlsDto uploadedFile);

	
}
