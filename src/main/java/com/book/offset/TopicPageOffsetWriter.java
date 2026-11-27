package com.book.offset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies an already validated book offset, joining an existing upload transaction when present. */
@Service
public class TopicPageOffsetWriter {
    private final BookDetailsRepository books;
    private final TopicDetailsRepository topics;
    private final EntityManager entityManager;

    public TopicPageOffsetWriter(BookDetailsRepository books, TopicDetailsRepository topics,
            EntityManager entityManager) {
        this.books = books;
        this.topics = topics;
        this.entityManager = entityManager;
    }

    @Transactional(noRollbackFor = ContentsMappingException.class)
    public WriterResult apply(Long bookId, String bookName, Set<Long> expectedRealTopicIds,
            int offset, int actualPdfPageCount) {
        return apply(bookId, bookName, expectedRealTopicIds, offset, actualPdfPageCount, null, offset);
    }

    @Transactional(noRollbackFor = ContentsMappingException.class)
    public WriterResult apply(Long bookId, String bookName, Set<Long> expectedRealTopicIds,
            int offset, int actualPdfPageCount, Map<Long, SourceSnapshot> expectedSources) {
        return apply(bookId, bookName, expectedRealTopicIds, offset, actualPdfPageCount, expectedSources, offset);
    }

    /** Prefix length and printed-number translation coincide only when the selected chapter starts on printed page 1. */
    @Transactional(noRollbackFor = ContentsMappingException.class)
    public WriterResult apply(Long bookId, String bookName, Set<Long> expectedRealTopicIds,
            int prefixLength, int actualPdfPageCount, Map<Long, SourceSnapshot> expectedSources,
            int printedPageOffset) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (bookId == null || bookId <= 0) issues.add(issue(null, "invalid_book_id", "A persisted current book ID is required."));
        if (bookName == null || bookName.isBlank()) issues.add(issue(null, "invalid_book_name", "The saved book name is required."));
        if (expectedRealTopicIds == null || expectedRealTopicIds.isEmpty()
                || expectedRealTopicIds.stream().anyMatch(id -> id == null || id <= 0)) {
            issues.add(issue(null, "incomplete_topic_response", "A complete nonempty set of persisted topic IDs is required."));
        }
        if (actualPdfPageCount <= 0 || prefixLength < 0 || prefixLength >= actualPdfPageCount) {
            issues.add(issue(null, "invalid_offset", "Prefix length must identify the pages before a chapter within the uploaded PDF."));
        }
        if (!issues.isEmpty()) throw new OffsetWriteException(issues);

        // The book lock serializes retries, including the case where no metadata row exists yet.
        BookDetails book = books.findLockedByBookId(bookId).orElseThrow(() -> new OffsetWriteException(
                List.of(issue(null, "book_not_found", "The current saved book ID no longer exists."))));
        entityManager.refresh(book, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(bookName, book.getBookName())) {
            issues.add(issue(null, "stale_book_response", "The returned book name no longer matches the saved book."));
        }
        if (book.getTotalPages() != actualPdfPageCount) {
            issues.add(issue(null, "pdf_page_count_mismatch", "The uploaded PDF page count differs from the saved book."));
        }
        String offsetName = bookName + "_offset";
        if (offsetName.length() > TopicDetails.MAX_TOPIC_NAME_LENGTH) {
            issues.add(issue(null, "offset_name_too_long", "The required offset topic name exceeds pdf_file_topic_details.topic_name capacity."));
        }

        List<TopicDetails> saved = topics.findAllLockedForBook(bookId);
        // Refresh also handles an open persistence context left over from the initial book save.
        saved.forEach(topic -> entityManager.refresh(topic, LockModeType.PESSIMISTIC_WRITE));
        List<TopicDetails> metadata = saved.stream().filter(topic -> offsetName.equals(topic.getTopicName())).toList();
        List<TopicDetails> real = saved.stream().filter(topic -> !offsetName.equals(topic.getTopicName())).toList();
        Set<Long> actualIds = new HashSet<>();
        real.forEach(topic -> actualIds.add(topic.getTopicId()));
        if (!actualIds.equals(expectedRealTopicIds)) {
            Set<Long> missing = new HashSet<>(actualIds);
            missing.removeAll(expectedRealTopicIds);
            Set<Long> foreign = new HashSet<>(expectedRealTopicIds);
            foreign.removeAll(actualIds);
            missing.forEach(id -> issues.add(issue(id, "missing_topic_response", "Saved real topic is absent from the nested response.")));
            foreign.forEach(id -> issues.add(issue(id, "unexpected_topic_response", "Nested response topic does not belong to this book's real-topic set.")));
        }
        if (expectedSources != null && !expectedSources.keySet().equals(expectedRealTopicIds)) {
            issues.add(issue(null, "incomplete_source_snapshot", "The source snapshot must cover every real topic exactly."));
        }
        if (metadata.size() > 1) {
            metadata.forEach(topic -> issues.add(issue(topic.getTopicId(), "duplicate_offset_entry", "More than one reserved offset entry exists for this book.")));
        } else if (metadata.size() == 1 && !matchesMetadata(metadata.getFirst(), bookId, offsetName, prefixLength)) {
            issues.add(issue(metadata.getFirst().getTopicId(), "conflicting_offset_entry", "The reserved offset entry differs from the required exact values."));
        }

        real.stream().min(Comparator.comparing(TopicDetails::getTopicId)).ifPresent(first -> {
            if (first.getPrintedStartPage() != null
                    && (long) first.getPrintedStartPage() + printedPageOffset != (long) prefixLength + 1) {
                issues.add(issue(first.getTopicId(), "prefix_mapping_mismatch",
                        "The smallest-ID topic's mapped start must immediately follow the offset PDF prefix."));
            }
        });

        // Unnumbered closing material belongs to the final topic so the PDFs can reconstruct the complete book.
        Long lastTopicId = real.stream().filter(topic -> topic.getPrintedStartPage() != null)
                .max(Comparator.comparing(TopicDetails::getPrintedStartPage).thenComparing(TopicDetails::getTopicId))
                .map(TopicDetails::getTopicId).orElse(null);
        List<PageAssignment> assignments = new ArrayList<>();
        for (TopicDetails topic : real) {
            Long id = topic.getTopicId();
            if (topic.getBookDetails() == null || !bookId.equals(topic.getBookDetails().getBookId())) {
                issues.add(issue(id, "wrong_book", "Topic ownership differs from the current book ID."));
            }
            if (expectedSources != null) {
                SourceSnapshot expected = expectedSources.get(id);
                if (expected == null || !expected.matches(topic)) {
                    issues.add(issue(id, "stale_topic_response", "Topic title or printed source pages changed after chapter selection."));
                }
            }
            Integer printedStart = topic.getPrintedStartPage();
            Integer printedEnd = topic.getPrintedEndPage();
            if (printedStart == null || printedEnd == null || printedStart <= 0 || printedEnd <= 0) {
                issues.add(issue(id, "invalid_printed_pages", "Both original printed page values must be present and positive."));
                continue;
            }
            long start = (long) printedStart + printedPageOffset;
            long printedEndPosition = (long) printedEnd + printedPageOffset;
            long end = Objects.equals(id, lastTopicId) ? actualPdfPageCount : printedEndPosition;
            if (printedStart > printedEnd || start < 1 || start > end || printedEndPosition > actualPdfPageCount) {
                issues.add(issue(id, "invalid_page_range", "Calculated inclusive PDF range is reversed or outside the uploaded PDF."));
                continue;
            }
            if (topic.getStartPage() != null && topic.getStartPage() != (int) start) {
                issues.add(issue(id, "start_page_conflict", "Existing start_page differs from printed_start_page plus the validated printed-page translation."));
            }
            if (topic.getEndPage() != null && topic.getEndPage() != (int) end) {
                issues.add(issue(id, "end_page_conflict", "Existing end_page differs from the calculated inclusive PDF range."));
            }
            if (topic.getStartPage() == null || topic.getEndPage() == null) {
                assignments.add(new PageAssignment(topic, printedStart, printedEnd, (int) start, (int) end));
            }
        }
        // No DML is permitted until the complete book and reserved entry have passed validation.
        if (!issues.isEmpty()) {
            if (issues.stream().allMatch(issue -> switch (issue.code()) {
                case "invalid_printed_pages", "invalid_page_range", "prefix_mapping_mismatch", "offset_name_too_long" -> true;
                default -> false;
            })) {
                // No DML has happened: the caller may safely replace unusable contents in this transaction.
                throw new ContentsMappingException(issues);
            }
            throw new OffsetWriteException(issues);
        }

        for (PageAssignment assignment : assignments) {
            int changed = topics.fillMissingPdfPages(bookId, assignment.topic().getTopicId(),
                    assignment.printedStart(), assignment.printedEnd(), assignment.start(), assignment.end());
            if (changed != 1) {
                throw new OffsetWriteException(List.of(issue(assignment.topic().getTopicId(), "concurrent_topic_change",
                        "A guarded page update did not update exactly one current-book record.")));
            }
        }
        // Bulk updates do not change managed entity state. Refresh without setters or audit callbacks.
        real.forEach(entityManager::refresh);

        TopicDetails offsetEntry;
        OffsetEntryStatus entryStatus;
        if (metadata.isEmpty()) {
            offsetEntry = new TopicDetails();
            offsetEntry.setBookDetails(book);
            offsetEntry.setTopicName(offsetName);
            offsetEntry.setDetails(bookName + "_offset detail");
            offsetEntry.setStartPage(1);
            offsetEntry.setEndPage(prefixLength);
            // Nullable printed page and path fields intentionally retain Java/database null.
            offsetEntry = topics.saveAndFlush(offsetEntry);
            entryStatus = OffsetEntryStatus.created;
        } else {
            offsetEntry = metadata.getFirst();
            entryStatus = OffsetEntryStatus.reused;
        }
        UpdateStatus status = !assignments.isEmpty() || entryStatus == OffsetEntryStatus.created
                ? UpdateStatus.updated : UpdateStatus.unchanged;
        return new WriterResult(status, assignments.size(), offsetEntry.getTopicId(), entryStatus, real);
    }

    private static boolean matchesMetadata(TopicDetails topic, Long bookId, String name, int offset) {
        return topic.getBookDetails() != null && bookId.equals(topic.getBookDetails().getBookId())
                && name.equals(topic.getTopicName()) && (name + " detail").equals(topic.getDetails())
                && Objects.equals(1, topic.getStartPage()) && Objects.equals(offset, topic.getEndPage())
                && topic.getPrintedStartPage() == null && topic.getPrintedEndPage() == null
                && (topic.getPdfPath() == null || !topic.getPdfPath().isBlank());
    }

    private static ValidationIssue issue(Long id, String code, String message) {
        return new ValidationIssue(id, code, message);
    }

    private record PageAssignment(TopicDetails topic, int printedStart, int printedEnd, int start, int end) { }

    public enum UpdateStatus { updated, unchanged }
    public enum OffsetEntryStatus { created, reused }
    public record ValidationIssue(Long topicId, String code, String message) { }
    public record SourceSnapshot(Long topicId, String topicName, Integer printedStartPage, Integer printedEndPage) {
        public static SourceSnapshot of(TopicDetails topic) {
            return new SourceSnapshot(topic.getTopicId(), topic.getTopicName(), topic.getPrintedStartPage(), topic.getPrintedEndPage());
        }
        private boolean matches(TopicDetails topic) {
            return Objects.equals(topicId, topic.getTopicId()) && Objects.equals(topicName, topic.getTopicName())
                    && Objects.equals(printedStartPage, topic.getPrintedStartPage()) && Objects.equals(printedEndPage, topic.getPrintedEndPage());
        }
    }
    public record WriterResult(UpdateStatus status, int recordsUpdated, Long offsetEntryId,
            OffsetEntryStatus offsetEntryStatus, List<TopicDetails> realTopics) {
        public WriterResult { realTopics = realTopics.stream().map(TopicDetails::responseCopy).toList(); }
    }
    public static class OffsetWriteException extends RuntimeException {
        private final List<ValidationIssue> issues;
        public OffsetWriteException(List<ValidationIssue> issues) {
            super("Book PDF pages were not changed: " + issues);
            this.issues = List.copyOf(issues);
        }
        public List<ValidationIssue> issues() { return issues; }
    }

    public static class ContentsMappingException extends OffsetWriteException {
        public ContentsMappingException(List<ValidationIssue> issues) { super(issues); }
    }
}
