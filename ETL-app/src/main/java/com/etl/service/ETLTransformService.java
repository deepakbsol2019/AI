package com.etl.service;

import java.util.List;

import org.springframework.ai.document.Document;

public interface ETLTransformService {

	public List<Document> transformDataIntoChuncks(List<Document> data);
}
