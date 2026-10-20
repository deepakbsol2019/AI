package com.etl.controller;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.etl.service.ETLExtractService;
import com.etl.service.ETLPipelineService;

@RestController
@RequestMapping("/api")
public class ETLController {


	private ETLExtractService etlService;
	private final ETLPipelineService etlPipelineService;
	public ETLController(ETLExtractService etlService,ETLPipelineService etlPipelineService) {
		this.etlService=etlService;
		this.etlPipelineService=etlPipelineService;
	}
	@PostMapping("/upload-json-file")
	public List<Document> uploadJson(){
		return etlService.loadDataFromJsonFile();
	}
	
	@PostMapping("/upload-pdf-file")
	public List<Document> uploadDatafromPdfFile(){
		return etlService.loadDataFromPdfFile();
	}
	
	@PostMapping("/json-pipeline")
	public List<Document> executePipeline(){
		return etlPipelineService.executeETLPipeline();
	}
}
