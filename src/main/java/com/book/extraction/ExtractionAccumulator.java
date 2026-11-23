package com.book.extraction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.book.dto.BookExtractionResult;
import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfBatchExtraction.AuthorData;
import com.book.dto.PdfBatchExtraction.BookData;
import com.book.dto.PdfBatchExtraction.TopicData;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;

final class ExtractionAccumulator {
    private String authorName;
    private String bookName;
    private String bookDescription;
    private final Map<String, TopicData> topics = new LinkedHashMap<>();
    private final Map<String, Integer> explicitSourceEnds = new LinkedHashMap<>();
    private Integer lastPrintedPage;
    private boolean indexFound;
    private boolean contentsComplete;
    private String fallbackReason;

    void merge(PdfBatchExtraction response, boolean firstBatch) {
        captureMetadata(response, firstBatch);
        indexFound |= Boolean.TRUE.equals(response.indexFound());
        contentsComplete |= Boolean.TRUE.equals(response.contentsComplete());
        if (!indexFound || response.topicDetails() == null) {
            return;
        }
        mergeTopics(response);
    }

    void captureMetadata(PdfBatchExtraction response, boolean firstBatch) {
        if (firstBatch) {
            if (response.authorDetails() != null) {
                authorName = known(response.authorDetails().authorName(), null);
            }
            if (response.bookDetails() != null) {
                bookName = known(response.bookDetails().bookName(), null);
                bookDescription = known(response.bookDetails().bookDetails(), null);
            }
        }
    }

    private void mergeTopics(PdfBatchExtraction response) {
        String previousLastKey = topics.isEmpty() ? null : new ArrayList<>(topics.keySet()).getLast();
        int position = 0;
        for (TopicData incoming : response.topicDetails()) {
            if (incoming == null) {
                throw invalid("A topic is missing.");
            }
            String name = known(incoming.topicName(), null);
            boolean firstItem = position++ == 0;
            if (name == null && firstItem && Boolean.TRUE.equals(incoming.continuesPreviousTopic())
                    && previousLastKey != null) {
                name = topics.get(previousLastKey).topicName();
            }
            checkTopicName(name);
            Integer printedStart = incoming.printedStartPage();
            if (printedStart != null && printedStart < 1) {
                throw invalid("A printed parent start page must be positive.");
            }
            TopicData topic = new TopicData(name.trim(), incoming.details(), null, null, printedStart, null,
                    normalizeNumber(incoming.topicNumber()), incoming.continuesPreviousTopic());
            String key = findTopicKey(topic, firstItem, previousLastKey);
            TopicData old = key == null ? null : topics.get(key);
            if (old == null) {
                key = normalize(topic.topicName()) + "#" + topics.size();
                topics.put(key, new TopicData(topic.topicName(), topic.details(), null, null,
                        topic.printedStartPage(), null, topic.topicNumber(), null));
            }
            else {
                // A continuation's first child reference (e.g. 21) must not replace
                // its parent's original start (e.g. chapter 2 starts at 17).
                boolean continuationOfLast = firstItem && key.equals(previousLastKey)
                        && !Boolean.FALSE.equals(topic.continuesPreviousTopic());
                Integer parentStart = continuationOfLast ? old.printedStartPage()
                        : known(old.printedStartPage(), topic.printedStartPage());
                topics.put(key, new TopicData(old.topicName(), mergeDetails(old.details(), topic.details()),
                        null, null, parentStart, null,
                        known(old.topicNumber(), topic.topicNumber()), null));
            }
        }
    }

    private String findTopicKey(TopicData topic, boolean firstItem, String previousLastKey) {
        if (topic.topicNumber() != null) {
            for (var entry : topics.entrySet()) {
                if (topic.topicNumber().equals(entry.getValue().topicNumber())) {
                    return entry.getKey();
                }
            }
        }
        TopicData previousLast = previousLastKey == null ? null : topics.get(previousLastKey);
        boolean compatibleLast = previousLast != null && !conflictingNumbers(previousLast, topic);
        if (firstItem && Boolean.TRUE.equals(topic.continuesPreviousTopic()) && compatibleLast) {
            return previousLastKey;
        }
        if (Boolean.FALSE.equals(topic.continuesPreviousTopic())) {
            return null;
        }
        for (var entry : topics.entrySet()) {
            TopicData old = entry.getValue();
            if (!conflictingNumbers(old, topic) && normalize(old.topicName()).equals(normalize(topic.topicName()))
                    && (old.printedStartPage() == null || topic.printedStartPage() == null
                            || old.printedStartPage().equals(topic.printedStartPage()))) {
                return entry.getKey();
            }
        }
        // Backward-compatible continuation handling when older model responses omit
        // the marker: only the first item can continue the previous last parent.
        if (firstItem && topic.continuesPreviousTopic() == null && compatibleLast
                && normalize(previousLast.topicName()).equals(normalize(topic.topicName()))) {
            return previousLastKey;
        }
        return null;
    }

    PdfBatchExtraction snapshot() {
        // Batch ends are deliberately unknown until every parent has been merged.
        return new PdfBatchExtraction(new AuthorData(authorName), new BookData(bookName, bookDescription),
                List.copyOf(topics.values()), indexFound, null, indexFound && !contentsComplete, contentsComplete);
    }

    void markContentsComplete() {
        contentsComplete = true;
    }

    void useWholeBook(String reason) {
        fallbackReason = reason == null || reason.isBlank() ? "Contents are unavailable or unusable." : reason;
        topics.clear();
        explicitSourceEnds.clear();
        indexFound = false;
        contentsComplete = true;
    }

    void applyContentsReferences(List<ContentsPageReferenceParser.Entry> references) {
        if (references.isEmpty()) {
            return;
        }
        List<TopicData> modelTopics = new ArrayList<>(topics.values());
        Map<String, TopicData> sourced = new LinkedHashMap<>();
        explicitSourceEnds.clear();
        for (ContentsPageReferenceParser.Entry reference : references) {
            String number = normalizeNumber(reference.topicNumber());
            String details = reference.details();
            for (TopicData modelTopic : modelTopics) {
                boolean sameNumber = number != null && number.equals(modelTopic.topicNumber());
                boolean sameName = normalize(reference.topicName()).equals(normalize(modelTopic.topicName()));
                if (sameNumber || sameName) {
                    details = mergeDetails(details, modelTopic.details());
                }
            }
            String key = normalize(reference.topicName()) + "#" + sourced.size();
            sourced.put(key, new TopicData(reference.topicName(), details, null, null,
                    reference.printedStartPage(), null, number, null));
            if (reference.explicitPrintedEndPage() != null) {
                explicitSourceEnds.put(key, reference.explicitPrintedEndPage());
            }
        }
        // Once the PDF supplies an actual parent list, it defines topic identities
        // and page references. AI contributes details without inventing extra parents.
        topics.clear();
        topics.putAll(sourced);
        indexFound = true;
    }

    void setLastPrintedPage(Integer page) {
        lastPrintedPage = page;
    }

    BookExtractionResult toEntities(String extractionId, String fileName, int totalPages) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName(authorName);
        BookDetails book = new BookDetails();
        book.setExtractionId(extractionId);
        boolean wholeBook = !indexFound || fallbackReason != null;
        book.setBookName((bookName == null || bookName.isBlank()
                ? wholeBook ? fallbackBookName(fileName) : fileName : bookName).trim());
        book.setBookDetails(bookDescription);
        book.setTotalPages(totalPages);
        book.setAuthorDetails(author);
        checkName(book.getBookName());
        if (book.getBookName().length() > BookDetails.MAX_BOOK_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The book name must contain 1 to " + BookDetails.MAX_BOOK_NAME_LENGTH + " characters.");
        }
        if (authorName != null) {
            checkName(authorName);
        }
        if (wholeBook) {
            TopicDetails topic = topic(book.getBookName(), bookDescription);
            topic.setStartPage(1);
            topic.setEndPage(totalPages);
            book.addTopic(topic);
        }
        else {
            resolvePrintedRanges(book);
        }
        return new BookExtractionResult(author, book, List.copyOf(book.getTopics()), null, null, wholeBook,
                wholeBook ? fallbackReason == null ? "No usable contents were found in the first batch." : fallbackReason : null);
    }

    private static String fallbackBookName(String fileName) {
        String name = fileName == null ? "" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("(?i)\\.pdf$", "").strip();
        return name.isBlank() ? "Untitled book" : name;
    }

    private void resolvePrintedRanges(BookDetails book) {
        if (!contentsComplete || topics.isEmpty()) {
            throw invalid("The contents section is incomplete or contains no extracted topics.");
        }
        List<TopicData> entries = new ArrayList<>(topics.values());
        List<String> keys = new ArrayList<>(topics.keySet());
        Integer lastKnownStart = null;
        for (TopicData entry : entries) {
            if (entry.printedStartPage() != null) {
                if (lastKnownStart != null && entry.printedStartPage() < lastKnownStart) {
                    throw invalid("Printed parent start pages decrease in contents order.");
                }
                lastKnownStart = entry.printedStartPage();
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            TopicData entry = entries.get(i);
            Integer start = entry.printedStartPage();
            Integer nextStart = i + 1 < entries.size() ? entries.get(i + 1).printedStartPage() : null;
            Integer inferredEnd = nextStart == null ? null
                    : start != null && start.equals(nextStart) ? start : Math.max(1, nextStart - 1);
            Integer end = known(explicitSourceEnds.get(keys.get(i)), inferredEnd);
            if (i == entries.size() - 1 && end == null && lastPrintedPage != null
                    && start != null && lastPrintedPage >= start) {
                end = lastPrintedPage;
            }
            if (end != null && ((start != null && end < start)
                    || (nextStart != null && end > nextStart))) {
                throw invalid("The printed topic range conflicts with the PDF contents.");
            }
            TopicDetails topic = topic(entry.topicName(), entry.details());
            topic.setPrintedStartPage(start);
            topic.setPrintedEndPage(end);
            book.addTopic(topic);
        }
    }

    private static TopicDetails topic(String name, String details) {
        checkTopicName(name);
        TopicDetails topic = new TopicDetails();
        topic.setTopicName(name);
        topic.setDetails(details);
        // Physical startPage/endPage and pdfPath are intentionally left null.
        return topic;
    }

    private static boolean conflictingNumbers(TopicData first, TopicData second) {
        return first.topicNumber() != null && second.topicNumber() != null
                && !first.topicNumber().equals(second.topicNumber());
    }

    private static String normalizeNumber(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        return number.trim().toLowerCase(Locale.ROOT).replaceFirst("^(?:chapter|part|unit|lesson)\\s+", "")
                .replaceFirst("[.:]$", "");
    }

    private static String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
                .replaceFirst("^(?:(?:chapter|part|unit|lesson)\\s+)?[0-9]+[.:]?\\s+", "");
    }

    private static String mergeDetails(String first, String second) {
        Map<String, String> names = new LinkedHashMap<>();
        for (String text : Arrays.asList(first, second)) {
            if (text != null) {
                Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                        .forEach(s -> names.putIfAbsent(s.toLowerCase(Locale.ROOT), s));
            }
        }
        return names.isEmpty() ? null : String.join(", ", names.values());
    }

    private static <T> T known(T preferred, T fallback) {
        if (preferred instanceof String text && text.isBlank()) {
            return fallback;
        }
        return preferred == null ? fallback : preferred;
    }

    private static void checkName(String value) {
        if (value == null || value.isBlank() || value.length() > 1024) {
            throw invalid("A metadata name is blank or longer than 1024 characters.");
        }
    }

    private static void checkTopicName(String value) {
        if (value == null || value.isBlank() || value.length() > TopicDetails.MAX_TOPIC_NAME_LENGTH) {
            throw invalid("A topic name is missing or exceeds the supported length.");
        }
    }

    static UnusableContentsException invalid(String message) {
        return new UnusableContentsException("Invalid model extraction: " + message);
    }
}
