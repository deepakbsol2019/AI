package com.book.dto;

import java.io.InputStream;

import org.springframework.core.io.Resource;

public class UploadFileDtlsDto {

	private String fileName;
	private byte[] fileData;
	private String contentType;
	private InputStream inputStream;
	private Resource resource;
	private Long fileSize;
	public String getFileName() {
		return fileName;
	}
	public void setFileName(String fileName) {
		this.fileName = fileName;
	}
	public byte[] getFileData() {
		return fileData;
	}
	public void setFileData(byte[] fileData) {
		this.fileData = fileData;
	}
	public String getContentType() {
		return contentType;
	}
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}
	public InputStream getInputStream() {
		return inputStream;
	}
	public void setInputStream(InputStream inputStream) {
		this.inputStream = inputStream;
	}
	public Resource getResource() {
		return resource;
	}
	public void setResource(Resource resource) {
		this.resource = resource;
	}
	public Long getFileSize() {
		return fileSize;
	}
	public void setFileSize(Long fileSize) {
		this.fileSize = fileSize;
	}
	public UploadFileDtlsDto(String fileName, byte[] fileData, String contentType, InputStream inputStream,
			Resource resource, Long fileSize) {
		super();
		this.fileName = fileName;
		this.fileData = fileData;
		this.contentType = contentType;
		this.inputStream = inputStream;
		this.resource = resource;
		this.fileSize = fileSize;
	}
	public UploadFileDtlsDto() {
		super();
		// TODO Auto-generated constructor stub
	}
	
	
}
