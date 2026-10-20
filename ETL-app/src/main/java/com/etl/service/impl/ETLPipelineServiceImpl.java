package com.etl.service.impl;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import com.etl.service.ETLExtractService;
import com.etl.service.ETLPipelineService;
import com.etl.service.ETLTransformService;
import com.etl.service.EtlLoaderService;
@Service
public class ETLPipelineServiceImpl implements ETLPipelineService {

	private final ETLExtractService etlExtractService;
	private final ETLTransformService etlTransformService;
	private final EtlLoaderService etlLoaderService;
	public ETLPipelineServiceImpl(ETLExtractService etlExtractService,ETLTransformService etlTransformService,EtlLoaderService etlLoaderService) {
		this.etlExtractService=etlExtractService;
		this.etlTransformService=etlTransformService;
		this.etlLoaderService=etlLoaderService;
	}
	@Override
	public List<Document> executeETLPipeline() {
		
		//read json data from file
		List<Document> dataFromJsonFile = etlExtractService.loadDataFromJsonFile();
		
		//transform data into chuncks
		List<Document> transformDataIntoChuncks = etlTransformService.transformDataIntoChuncks(dataFromJsonFile);
		
		etlLoaderService.loadDocumentToVectorDB(transformDataIntoChuncks);
		return transformDataIntoChuncks;
	}

}
