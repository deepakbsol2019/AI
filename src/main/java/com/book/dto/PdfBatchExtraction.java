package com.book.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record PdfBatchExtraction(@Schema(nullable = true) AuthorData authorDetails,
        @Schema(nullable = true, description = "Initial-page metadata, category and description supplied together in this existing extraction response, including when there are no contents topics.") BookData bookDetails,
        List<TopicData> topicDetails, Boolean indexFound,
        @Schema(nullable = true, description = "Always null during contents extraction; physical pages are assigned later.")
        Integer firstTopicStartPage,
        Boolean requiredNextPageSet, Boolean contentsComplete) {
    public record AuthorData(@Schema(nullable = true) String authorName) { }
    public record BookData(@Schema(nullable = true) String bookName,
            @Schema(nullable = true, description = "Short factual book description generated in this same initial extraction JSON. Ground in the supplied title, category and PDF text; known category alone permits only a general category description. No invented chapters, characters or events; null if insufficient evidence.") String bookDetails,
            @Schema(nullable = true, description = "Book category grounded in the supplied initial PDF pages, such as Computer Programming, Story, Poetry, Physics, Chemistry or Math. Unknown when evidence is insufficient; these examples are not an enum. Provide alongside bookDetails in the same response.")
            String bookType) {
        public BookData(String bookName, String bookDetails) {
            this(bookName, bookDetails, null);
        }
    }
    public record TopicData(String topicName,
            @Schema(nullable = true, description = "Topic description/details in this same extraction response: preserve observed comma-separated subtopic names; otherwise give only a factual explanation grounded in this actual topic name and related supplied text. Continuations add newly observed subtopics without regenerating existing details. Null if evidence is insufficient.") String details,
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
