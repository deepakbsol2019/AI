package com.etl.service.impl;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.JsonReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import com.etl.service.ETLExtractService;
@Service
public class ETLExtractServiceImpl implements ETLExtractService {

	public ETLExtractServiceImpl() {
	}
	@Override
	public List<Document> loadDataFromJsonFile() {
		String resource="C:\\backend\\doc-path\\upload-files\\ipl-match-data.json";
		JsonReader jsonReader=new JsonReader(new FileSystemResource(resource));
		return jsonReader.read();
	}
	@Override
	public List<Document> loadDataFromPdfFile() {
		String resource="C:\\backend\\doc-path\\upload-files\\NCERT_CLASS_1_MATHEMATICS_GANIT_KA_JADU.pdf";
		PagePdfDocumentReader pagePdfDocumentReader = new PagePdfDocumentReader(new FileSystemResource(resource),
				PdfDocumentReaderConfig.builder()
				.withPageTopMargin(0)
				.withPagesPerDocument(1)
				.build());
		return pagePdfDocumentReader.read();
	}

	
}
