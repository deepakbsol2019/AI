package com.etl.service.impl;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import com.etl.service.ETLTransformService;
@Service
public class ETLTransformServiceImpl implements ETLTransformService {

	@Override
	public List<Document> transformDataIntoChuncks(List<Document> data) {
		DocumentTransformer documentTransformer=TokenTextSplitter.builder()
				.withChunkSize(300)
		        .withMinChunkSizeChars(250)
		        .withMinChunkLengthToEmbed(5)
		        .withMaxNumChunks(500)
		        .withKeepSeparator(true)
		        .build();
		return documentTransformer.transform(data);
	}

}
