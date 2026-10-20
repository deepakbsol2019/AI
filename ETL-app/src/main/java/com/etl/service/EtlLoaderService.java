package com.etl.service;

import java.util.List;

import org.springframework.ai.document.Document;

public interface EtlLoaderService {

	public void loadDocumentToVectorDB(List<Document> doc) ;
}
