package com.etl.service.impl;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.etl.service.EtlLoaderService;

@Service
public class EtlLoaderServiceImpl implements EtlLoaderService {

	private VectorStore vectorStore;
	public EtlLoaderServiceImpl(VectorStore vectorStore) {
		this.vectorStore=vectorStore;
	}
	@Override
	public void loadDocumentToVectorDB(List<Document> doc) {
		vectorStore.add(doc);
	}

}
