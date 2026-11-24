package com.book.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.book.dto.PdfBatchExtraction.TopicData;
import io.swagger.v3.oas.annotations.media.Schema;

// Continuation batches cannot change author/book metadata captured from the first batch.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PdfTopicBatchExtraction(
        @Schema(description = "Actual contents topics and their descriptions/details from this continuation batch. Preserve earlier nonblank details and return newly observed subtopics; do not regenerate book metadata or request extra batches just for descriptions.")
        List<TopicData> topicDetails, Boolean indexFound,
        @Schema(nullable = true) Integer firstTopicStartPage,
        Boolean requiredNextPageSet, Boolean contentsComplete) {
    public PdfBatchExtraction toBatchExtraction() {
        return new PdfBatchExtraction(null, null, topicDetails, indexFound,
                firstTopicStartPage, requiredNextPageSet, contentsComplete);
    }
}
