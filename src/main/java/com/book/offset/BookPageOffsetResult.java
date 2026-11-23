package com.book.offset;

import java.util.List;

/** A confirmed detection and a committed database update are deliberately separate outcomes. */
public record BookPageOffsetResult(Long bookId, Long selectedTopicId, String selectedTopicName,
        Integer offsetNumber, ChapterDetection detection, UpdateOutcome update, Integer printedPageOffset) {

    /** Older printed-page-1 callers have the same conversion delta and front-matter length. */
    public BookPageOffsetResult(Long bookId, Long selectedTopicId, String selectedTopicName,
            Integer offsetNumber, ChapterDetection detection, UpdateOutcome update) {
        this(bookId, selectedTopicId, selectedTopicName, offsetNumber, detection, update, offsetNumber);
    }

    public enum UpdateStatus { updated, unchanged, not_attempted, failed }

    public record Issue(Long topicId, String code, String message) { }

    public record UpdateOutcome(UpdateStatus status, int topicRecordsUpdated, Long offsetEntryId,
            String offsetEntryStatus, List<Issue> issues) {
        public UpdateOutcome {
            issues = List.copyOf(issues);
        }
    }
}
