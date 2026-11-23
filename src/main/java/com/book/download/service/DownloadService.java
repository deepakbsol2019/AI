package com.book.download.service;

import java.util.List;

public interface DownloadService {
    byte[] downloadBook(Long bookId);

    byte[] downloadTopics(Long bookId, List<Long> topicIds);
}
