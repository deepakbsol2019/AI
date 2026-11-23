package com.book.offset;

import com.book.dto.BookExtractionResult;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Validates the actual saved response before choosing its first persisted real topic. */
@Component
public class SavedBookTopicSelector {
    private final BookDetailsRepository books;
    private final TopicDetailsRepository topics;

    public SavedBookTopicSelector(BookDetailsRepository books, TopicDetailsRepository topics) {
        this.books = books;
        this.topics = topics;
    }

    @Transactional(readOnly = true)
    public Selection select(BookExtractionResult saved) {
        List<String> issues = new ArrayList<>();
        if (saved == null || saved.bookDetails() == null) {
            throw new SelectionException(List.of("A successful saved book response is required."));
        }
        BookDetails responseBook = saved.bookDetails();
        Long bookId = responseBook.getBookId();
        String bookName = responseBook.getBookName();
        if (bookId == null || bookId <= 0) {
            issues.add("The saved response has no valid persisted book ID.");
        }
        if (bookName == null || bookName.isBlank()) {
            issues.add("The saved response has no book name.");
        }
        if (responseBook.getTotalPages() <= 0) {
            issues.add("The saved response has no valid PDF page count.");
        }
        if (saved.topicDetails() == null || saved.topicDetails().isEmpty()) {
            issues.add("The saved response must contain the complete nested topicDetails list.");
        }
        failIfAny(issues);

        // The returned ID is authoritative. Names are never used to locate another book.
        BookDetails persistedBook = books.findById(bookId).orElseThrow(() ->
                new SelectionException(List.of("Book " + bookId + " is not persisted.")));
        if (!Objects.equals(persistedBook.getBookName(), bookName)) {
            issues.add("Book " + bookId + " name differs from the saved response.");
        }
        if (persistedBook.getTotalPages() != responseBook.getTotalPages()) {
            issues.add("Book " + bookId + " PDF page count differs from the saved response.");
        }
        String reservedName = bookName + "_offset";
        Map<Long, TopicDetails> responseTopics = collect(saved.topicDetails(), bookId, reservedName,
                "saved response", issues);
        // A fresh scoped query detects rows absent from an already initialized OSIV book collection.
        Map<Long, TopicDetails> persistedTopics = collect(
                topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(bookId), bookId, reservedName,
                "database", issues);
        if (responseTopics.isEmpty()) {
            issues.add("The saved response contains no real chapter/topic records.");
        }
        if (!responseTopics.keySet().equals(persistedTopics.keySet())) {
            Set<Long> missing = new HashSet<>(persistedTopics.keySet());
            missing.removeAll(responseTopics.keySet());
            Set<Long> unexpected = new HashSet<>(responseTopics.keySet());
            unexpected.removeAll(persistedTopics.keySet());
            issues.add("Book " + bookId + " topicDetails is incomplete or stale; missing topic IDs="
                    + missing.stream().sorted().toList() + ", unexpected topic IDs="
                    + unexpected.stream().sorted().toList() + ".");
        }
        for (Map.Entry<Long, TopicDetails> entry : responseTopics.entrySet()) {
            TopicDetails persisted = persistedTopics.get(entry.getKey());
            if (persisted == null) {
                continue;
            }
            TopicDetails response = entry.getValue();
            if (!Objects.equals(response.getTopicName(), persisted.getTopicName())) {
                issues.add("Topic " + entry.getKey() + " title differs from the persisted topic.");
            }
            if (!Objects.equals(response.getPrintedStartPage(), persisted.getPrintedStartPage())
                    || !Objects.equals(response.getPrintedEndPage(), persisted.getPrintedEndPage())) {
                issues.add("Topic " + entry.getKey() + " printed source range differs from the persisted topic.");
            }
        }
        failIfAny(issues);
        List<TopicDetails> sorted = responseTopics.values().stream()
                .sorted(Comparator.comparing(TopicDetails::getTopicId))
                .map(TopicDetails::responseCopy).toList();
        return new Selection(bookId, bookName, responseBook.getTotalPages(), sorted.getFirst(),
                sorted, Set.copyOf(responseTopics.keySet()));
    }

    private static Map<Long, TopicDetails> collect(List<TopicDetails> topics, Long bookId,
            String reservedName, String source, List<String> issues) {
        Map<Long, TopicDetails> realTopics = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        if (topics == null) {
            issues.add("Book " + bookId + " has no topic collection in the " + source + ".");
            return realTopics;
        }
        for (TopicDetails topic : topics) {
            if (topic == null) {
                issues.add("A null topic occurs in the " + source + ".");
                continue;
            }
            Long topicId = topic.getTopicId();
            if (topicId == null || topicId <= 0) {
                issues.add("A topic in the " + source + " has no valid persisted topic ID.");
                continue;
            }
            if (!seen.add(topicId)) {
                issues.add("Duplicate topic ID " + topicId + " occurs in the " + source + ".");
            }
            BookDetails owner = topic.getBookDetails();
            if (owner == null || !Objects.equals(owner.getBookId(), bookId)) {
                issues.add("Topic " + topicId + " in the " + source + " does not belong to book " + bookId + ".");
                continue;
            }
            // A suffix on some other title is not enough: only this exact reserved name is metadata.
            if (Objects.equals(reservedName, topic.getTopicName())) {
                continue;
            }
            if (topic.getTopicName() == null || topic.getTopicName().isBlank()) {
                issues.add("Topic " + topicId + " in the " + source + " has no title.");
            }
            realTopics.put(topicId, topic);
        }
        return realTopics;
    }

    private static void failIfAny(List<String> issues) {
        if (!issues.isEmpty()) {
            throw new SelectionException(issues);
        }
    }

    public record Selection(Long bookId, String bookName, int totalPages, TopicDetails firstTopic,
            List<TopicDetails> realTopics, Set<Long> realTopicIds) {
        public Selection {
            realTopics = List.copyOf(realTopics);
            realTopicIds = Set.copyOf(realTopicIds);
        }
    }

    public static class SelectionException extends IllegalArgumentException {
        private final List<String> issues;

        public SelectionException(List<String> issues) {
            super(String.join(" ", issues));
            this.issues = List.copyOf(issues);
        }

        public List<String> issues() {
            return issues;
        }
    }
}
