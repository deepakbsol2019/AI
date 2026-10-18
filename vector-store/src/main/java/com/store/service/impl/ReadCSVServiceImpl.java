package com.store.service.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.store.service.ReadCSVService;
import com.store.util.ReadCSV;
@Service
public class ReadCSVServiceImpl implements ReadCSVService{

	private final Path filePath;
	private final VectorStore vectorStore;
    public ReadCSVServiceImpl(@Value("${app.csv.file-path}") String filePath,VectorStore vectorStore) {
        this.filePath = Path.of(filePath);
        this.vectorStore=vectorStore;
    }
    
	@Override
	public List<String> readDataFromCsv() {
		List<String> statements =null;
		try {
			statements= ReadCSV.readStatements(filePath);
			saveDataInVector(statements);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return statements;
	}

	@Override
	public void saveDataInVector(List<String> data) {
		List<Document> doc = data.stream().map(Document::new).toList();
		vectorStore.add(doc);
	}

}
