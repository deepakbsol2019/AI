package com.book.download.dto;

import java.util.List;

/** Authorized, immutable metadata for the local download template. */
public record DownloadBookModel(Book book, Author author, List<Topic> selectedTopics) {
    public DownloadBookModel {
        selectedTopics = List.copyOf(selectedTopics);
    }

    public record Book(Long id, String name) { }
    public record Author(String name) { }
    public record Topic(Long id, String name) { }
}
