package com.book.dto;

import java.util.List;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;

public record BookExtractionResult(AuthorDetails authorDetails, BookDetails bookDetails,
        List<TopicDetails> topicDetails) { }
