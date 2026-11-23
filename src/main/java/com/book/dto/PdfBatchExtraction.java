package com.book.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record PdfBatchExtraction(@Schema(nullable = true) AuthorData authorDetails,
        @Schema(nullable = true) BookData bookDetails,
        List<TopicData> topicDetails, Boolean indexFound,
        @Schema(nullable = true, description = "Always null during contents extraction; physical pages are assigned later.")
        Integer firstTopicStartPage,
        Boolean requiredNextPageSet, Boolean contentsComplete) {
    public record AuthorData(@Schema(nullable = true) String authorName) { }
    public record BookData(@Schema(nullable = true) String bookName,
            @Schema(nullable = true) String bookDetails) { }
    public record TopicData(String topicName, @Schema(nullable = true) String details,
            @Schema(nullable = true, description = "Always null during extraction. This is not the printed book page.")
            Integer startPage,
            @Schema(nullable = true, description = "Always null during extraction; physical PDF positions are assigned later.")
            Integer endPage,
            @Schema(nullable = true, description = "Printed start of the PARENT chapter in the contents, e.g. 17, not a subtopic's page such as 21.")
            Integer printedStartPage,
            @Schema(nullable = true, description = "Always null in batch responses; the service calculates it after merging ALL contents.")
            Integer printedEndPage,
            @Schema(nullable = true, description = "Stable parent chapter/topic number, e.g. 2, never the child's number 2.5.")
            String topicNumber,
            @Schema(nullable = true, description = "True for the first item when continuing the previous batch's last parent topic; false for a new parent.")
            Boolean continuesPreviousTopic) {
        public TopicData(String topicName, String details, Integer startPage, Integer endPage,
                Integer printedStartPage, Integer printedEndPage) {
            this(topicName, details, startPage, endPage, printedStartPage, printedEndPage, null, null);
        }
    }
}
