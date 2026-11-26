package com.book.download.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.book.entity.TopicDetails;
import com.book.repository.TopicDetailsRepository;
import com.book.user.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reading follows verified physical source order; selected downloads retain their existing request order. */
@Service
public class BookReaderService {
    private final TopicDetailsRepository topics;
    private final DownloadService downloads;

    public BookReaderService(TopicDetailsRepository topics, DownloadService downloads) {
        this.topics = topics;
        this.downloads = downloads;
    }

    @Transactional(readOnly = true)
    public byte[] readTopics(Long bookId, List<Long> topicIds) {
        requirePositive(bookId, "bookId");
        TenantContext.requireTenantId();
        if (topicIds == null || topicIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topicIds must contain at least one topic ID.");
        }
        Set<Long> requested = new HashSet<>();
        for (Long topicId : topicIds) {
            requirePositive(topicId, "Each topic ID");
            if (!requested.add(topicId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "topicIds must not contain duplicate IDs.");
            }
        }
        // One stored topic needs no relative-order inference. Existing assembly validates ownership and its PDF.
        if (topicIds.size() == 1) return downloads.downloadTopics(bookId, List.copyOf(topicIds));

        List<TopicDetails> found = topics.findAllByBookDetailsBookIdAndTopicIdIn(bookId, topicIds);
        Map<Long, TopicDetails> byId = new HashMap<>();
        for (TopicDetails topic : found) {
            if (topic == null || topic.getTopicId() == null || topic.getTopicId() <= 0
                    || topic.getBookDetails() == null || byId.putIfAbsent(topic.getTopicId(), topic) != null) {
                throw unavailableOrder("The saved topic metadata is inconsistent.");
            }
            if (!Objects.equals(topic.getBookDetails().getBookId(), bookId)) throw missingSelection(bookId);
            TenantContext.requireOwned(topic.getBookDetails().getTenantId());
        }
        if (!byId.keySet().equals(requested)) throw missingSelection(bookId);
        for (TopicDetails topic : found) {
            Integer start = topic.getStartPage();
            Integer end = topic.getEndPage();
            int totalPages = topic.getBookDetails().getTotalPages();
            boolean emptyOffset = isReservedOffset(topic) && Objects.equals(start, 1) && Objects.equals(end, 0);
            if (start == null || end == null || start < 1 || totalPages < 1
                    || end > totalPages || (end < start && !emptyOffset)) {
                throw unavailableOrder("Topic " + topic.getTopicId()
                        + " has missing or invalid physical PDF page boundaries.");
            }
        }
        List<Long> ordered = found.stream()
                .sorted(Comparator.comparing(TopicDetails::getStartPage)
                        .thenComparing(TopicDetails::getEndPage)
                        .thenComparing(topic -> isReservedOffset(topic) ? 0 : 1)
                        .thenComparing(TopicDetails::getTopicId))
                .map(TopicDetails::getTopicId).toList();
        return downloads.downloadTopics(bookId, ordered);
    }

    private static boolean isReservedOffset(TopicDetails topic) {
        return Objects.equals(topic.getTopicName(), topic.getBookDetails().getBookName() + "_offset");
    }

    private static void requirePositive(Long id, String name) {
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be a positive integer.");
        }
    }

    private static ResponseStatusException missingSelection(Long bookId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Every requested topic must exist and belong to book " + bookId + ".");
    }

    private static ResponseStatusException unavailableOrder(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "These topics cannot be read in verified source order. " + reason
                        + " Open one topic at a time or use the existing selected-topic download.");
    }
}
