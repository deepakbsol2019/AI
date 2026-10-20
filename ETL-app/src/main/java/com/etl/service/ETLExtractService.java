package com.etl.service;

import java.util.List;

import org.springframework.ai.document.Document;

public interface ETLExtractService {

	public List<Document> loadDataFromJsonFile();
	
	public List<Document> loadDataFromPdfFile();
}
