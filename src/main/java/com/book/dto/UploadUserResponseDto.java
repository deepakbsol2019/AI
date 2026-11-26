package com.book.dto;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

import com.book.constants.BookUploadStatus;

public class UploadUserResponseDto {
	private String fileName;
	private LocalDateTime fileUploadDateTime;
	private BookUploadStatus status;
	private BookExtractionResult extraction;

	public BookExtractionResult getExtraction() {
		return extraction;
	}
	public void setExtraction(BookExtractionResult extraction) {
		this.extraction = extraction;
	}
	public String getFileName() {
		return fileName;
	}
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}
	@JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
	public LocalDateTime getFileUploadDateTime() {
		return fileUploadDateTime;
	}
	public void setFileUploadDateTime(LocalDateTime fileUploadDateTime) {
		this.fileUploadDateTime = fileUploadDateTime;
	}
	public BookUploadStatus getStatus() {
		return status;
	}
	public void setStatus(BookUploadStatus status) {
		this.status = status;
	}
	public UploadUserResponseDto(String fileName, LocalDateTime fileUploadDateTime, BookUploadStatus status) {
		super();
		this.fileName = fileName;
		this.fileUploadDateTime = fileUploadDateTime;
		this.status = status;
	}
	public UploadUserResponseDto() {
		super();
		// TODO Auto-generated constructor stub
	}
	
	
}
