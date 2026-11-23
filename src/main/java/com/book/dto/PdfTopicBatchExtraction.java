package com.book.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.book.dto.PdfBatchExtraction.TopicData;
import io.swagger.v3.oas.annotations.media.Schema;

// Continuation batches cannot change author/book metadata captured from the first batch.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PdfTopicBatchExtraction(List<TopicData> topicDetails, Boolean indexFound,
        @Schema(nullable = true) Integer firstTopicStartPage,
        Boolean requiredNextPageSet, Boolean contentsComplete) {
    public PdfBatchExtraction toBatchExtraction() {
        return new PdfBatchExtraction(null, null, topicDetails, indexFound,
                firstTopicStartPage, requiredNextPageSet, contentsComplete);
    }
}
