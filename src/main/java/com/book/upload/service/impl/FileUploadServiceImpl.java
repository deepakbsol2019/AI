package com.book.upload.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.book.dto.UploadFileDtlsDto;
import com.book.dto.BookExtractionResult;
import com.book.extraction.BookMetadataExtractionService;
import com.book.offset.BookPageOffsetService;
import com.book.offset.BookPageOffsetResult;
import com.book.offset.BookPageOffsetResult.UpdateStatus;
import com.book.upload.service.FileUploadService;
import com.book.upload.service.BookUploadStatusService;
import com.book.upload.topic.TopicPdfUploadService;
@Service
public class FileUploadServiceImpl implements FileUploadService {

	private static final Logger logger = LoggerFactory.getLogger(FileUploadServiceImpl.class);
	private final BookMetadataExtractionService extractionService;
	private final BookPageOffsetService pageOffsetService;
	private final TopicPdfUploadService topicPdfUploadService;
	private final BookUploadStatusService uploadStatusService;

	public FileUploadServiceImpl(BookMetadataExtractionService extractionService, BookPageOffsetService pageOffsetService,
			TopicPdfUploadService topicPdfUploadService, BookUploadStatusService uploadStatusService) {
		this.extractionService = extractionService;
		this.pageOffsetService = pageOffsetService;
		this.topicPdfUploadService = topicPdfUploadService;
		this.uploadStatusService = uploadStatusService;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public BookExtractionResult uploadFile(UploadFileDtlsDto uploadedFile) {
		logger.info("Received file '{}' ({} bytes)", uploadedFile.getFileName(), uploadedFile.getFileSize());
		BookExtractionResult saved = uploadedFile.getBookUploadId() == null
				? extractionService.extract(uploadedFile.getFileData(), uploadedFile.getFileName())
				: extractionService.extract(uploadedFile.getFileData(), uploadedFile.getFileName(), uploadedFile.getBookUploadId());
		BookExtractionResult mapped = pageOffsetService.populatePdfPages(saved, uploadedFile.getFileData());
		if (mapped.pageOffset() == null || mapped.pageOffset().update() == null
				|| (mapped.pageOffset().update().status() != UpdateStatus.updated
						&& mapped.pageOffset().update().status() != UpdateStatus.unchanged)) {
			throw new IllegalStateException(mappingFailure(mapped.pageOffset()));
		}
		// The repository flushes above join this transaction; no commit happens before all files and paths succeed.
		BookExtractionResult savedBookDetails = mapped.withUploadedFile(uploadedFile);
		BookExtractionResult completed = topicPdfUploadService.uploadTopic(savedBookDetails);
		if (uploadedFile.getBookUploadId() != null) {
			uploadStatusService.succeed(uploadedFile.getBookUploadId(), completed.bookDetails().getBookName());
		}
		return completed;
	}

	private static String mappingFailure(BookPageOffsetResult outcome) {
		if (outcome == null || outcome.update() == null) {
			return "Book upload cannot complete: the PDF page-mapping result is missing.";
		}
		String target = outcome.selectedTopicName() == null ? "the first topic"
				: "first topic '" + outcome.selectedTopicName() + "'";
		String issues = outcome.update().issues().stream()
				.map(issue -> "[" + issue.code() + "] " + issue.message())
				.collect(java.util.stream.Collectors.joining(" "));
		return "Book upload cannot prepare PDFs for " + target + ": "
				+ (issues.isBlank() ? outcome.update().status() : issues);
	}

}
