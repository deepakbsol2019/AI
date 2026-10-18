package com.store.service.impl;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.store.service.FindSimilarResult;

@Service
public class FindSimilarResultImpl implements FindSimilarResult{

	private final VectorStore vectorStore;
	public FindSimilarResultImpl(VectorStore vectorStore) {
		this.vectorStore=vectorStore;
	}
	@Override
	public List<String> findSimilarDataByQuestion(String question) {
		SearchRequest searchRequest = SearchRequest.builder()
		.topK(5)
		.similarityThreshold(0.4)
		.query(question)
		.build();
		List<Document> similaritySearch = vectorStore.similaritySearch(searchRequest);
		return similaritySearch.stream().map(d->d.getText()).toList();
	}

}
