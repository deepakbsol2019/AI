package com.book.metadata.service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.book.dto.BookSearchResponse.AuthorResult;
import com.book.dto.BookSearchResponse.BookResult;
import com.book.dto.BookSearchResponse.TopicResult;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;
import com.book.entity.TopicDetails;
import com.book.metadata.dto.MetadataPatchRequest;
import com.book.metadata.dto.MetadataPatchRequest.Change;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** One submitted field is applied to freshly locked persisted metadata; PDFs and page boundaries are untouched. */
@Service
public class BookMetadataService {
    private final BookDetailsRepository books;
    private final TopicDetailsRepository topics;
    private final EntityManager entityManager;

    public BookMetadataService(BookDetailsRepository books, TopicDetailsRepository topics, EntityManager entityManager) {
        this.books = books;
        this.topics = topics;
        this.entityManager = entityManager;
    }

    @Transactional
    public BookResult patchBook(Long bookId, MetadataPatchRequest request) {
        Change change = requireRequest(request).bookChange();
        BookDetails book = lockedBook(bookId);
        List<TopicDetails> entries = currentTopics(bookId);
        TopicDetails legacy = legacyWholeBook(book, entries);
        switch (change.field()) {
            case "bookName" -> renameBook(book, entries, legacy,
                    requiredName(change.value(), "Book name", BookDetails.MAX_BOOK_NAME_LENGTH));
            case "bookType" -> {
                String value = trimmed(change.value());
                maximum(value, BookDetails.MAX_BOOK_TYPE_LENGTH, "Book type");
                book.setBookType(value);
            }
            case "authorName" -> changeAuthor(book, change.value());
            case "bookDetails" -> {
                book.setBookDetails(change.value());
                if (legacy != null) legacy.setDetails(change.value());
            }
            default -> throw new IllegalStateException("Unrecognized allowlisted field.");
        }
        return confirmed(book, entries);
    }

    @Transactional
    public BookResult patchTopic(Long bookId, Long topicId, MetadataPatchRequest request) {
        Change change = requireRequest(request).topicChange();
        requirePositive(topicId, "topicId");
        BookDetails book = lockedBook(bookId);
        List<TopicDetails> entries = currentTopics(bookId);
        TopicDetails topic = entries.stream().filter(entry -> Objects.equals(entry.getTopicId(), topicId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The topic does not exist or does not belong to this book."));
        if (isOffset(topic) || topic == legacyWholeBook(book, entries)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This technical PDF storage row is read-only. Edit the book metadata instead; its PDF must remain readable.");
        }
        if ("topicName".equals(change.field())) {
            String name = requiredName(change.value(), "Topic name", TopicDetails.MAX_TOPIC_NAME_LENGTH);
            if (name.endsWith("_offset")) throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Topic names ending in _offset are reserved for PDF storage metadata.");
            topic.setTopicName(name);
        } else {
            topic.setDetails(change.value());
        }
        return confirmed(book, entries);
    }

    private BookDetails lockedBook(Long bookId) {
        requirePositive(bookId, "bookId");
        BookDetails book = books.findLockedByBookId(bookId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Book " + bookId + " was not found."));
        // A caller may already have a stale managed instance; locking alone does not refresh that instance.
        entityManager.refresh(book, LockModeType.PESSIMISTIC_WRITE);
        if (book.getBookUploadStatus() != null) {
            entityManager.refresh(book.getBookUploadStatus());
            if (BookUploadStatus.IN_PROGRESS.equals(book.getBookUploadStatus().getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This book is still being uploaded. Wait for processing to finish before editing its metadata.");
            }
        }
        return book;
    }

    private List<TopicDetails> currentTopics(Long bookId) {
        List<TopicDetails> entries = topics.findAllLockedForBook(bookId);
        for (TopicDetails topic : entries) entityManager.refresh(topic, LockModeType.PESSIMISTIC_WRITE);
        return entries;
    }

    private void renameBook(BookDetails book, List<TopicDetails> entries, TopicDetails legacy, String name) {
        if (books.existsByCanonicalBookNameExcludingId(name, book.getBookId())) {
            throw duplicate(name, null);
        }
        String previous = book.getBookName();
        String offsetName = previous + "_offset";
        String newOffsetName = name + "_offset";
        List<TopicDetails> canonical = entries.stream().filter(topic -> offsetName.equals(topic.getTopicName())).toList();
        if (!canonical.isEmpty()) {
            maximum(newOffsetName, TopicDetails.MAX_TOPIC_NAME_LENGTH, "The reserved book offset name (book name plus _offset)");
            if (entries.stream().anyMatch(topic -> !canonical.contains(topic) && newOffsetName.equals(topic.getTopicName()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The renamed book would collide with an existing reserved offset row.");
            }
            for (TopicDetails offset : canonical) {
                offset.setTopicName(newOffsetName);
                if ((previous + "_offset detail").equals(offset.getDetails())) offset.setDetails(name + "_offset detail");
            }
        }
        if (legacy != null) legacy.setTopicName(name);
        book.setBookName(name);
    }

    private void changeAuthor(BookDetails book, String submitted) {
        String name = trimmed(submitted);
        if (name != null && name.isEmpty()) name = null;
        maximum(name, 200, "Author name");
        AuthorDetails author = book.getAuthorDetails();
        if (author != null) {
            entityManager.refresh(author, LockModeType.PESSIMISTIC_WRITE);
            if (Objects.equals(author.getAuthorName(), name)) return;
        }
        if (author == null || books.countOtherBooksForAuthor(author.getAuthorId(), book.getBookId()) > 0) {
            AuthorDetails replacement = new AuthorDetails();
            replacement.setAuthorName(name);
            entityManager.persist(replacement);
            book.setAuthorDetails(replacement);
        } else {
            author.setAuthorName(name);
        }
    }

    private void flush(String bookName) {
        try {
            books.flush();
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().toLowerCase(java.util.Locale.ROOT)
                        .contains("uk_book_details_book_name")) throw duplicate(bookName, failure);
            }
            throw failure;
        }
    }

    private BookResult confirmed(BookDetails book, List<TopicDetails> entries) {
        flush(book.getBookName());
        // Return persisted audit precision as well as the confirmed full metadata values.
        entityManager.refresh(book, LockModeType.PESSIMISTIC_WRITE);
        for (TopicDetails topic : entries) entityManager.refresh(topic, LockModeType.PESSIMISTIC_WRITE);
        if (book.getAuthorDetails() != null) entityManager.refresh(book.getAuthorDetails());
        return snapshot(book, entries);
    }

    private static TopicDetails legacyWholeBook(BookDetails book, List<TopicDetails> entries) {
        if (entries.size() != 1) return null;
        TopicDetails topic = entries.getFirst();
        return Objects.equals(topic.getTopicName(), book.getBookName())
                && Objects.equals(topic.getDetails(), book.getBookDetails())
                && Objects.equals(topic.getStartPage(), 1) && Objects.equals(topic.getEndPage(), book.getTotalPages())
                && topic.getPrintedStartPage() == null && topic.getPrintedEndPage() == null ? topic : null;
    }

    private static boolean isOffset(TopicDetails topic) {
        return topic.getTopicName() != null && topic.getTopicName().endsWith("_offset");
    }

    private static MetadataPatchRequest requireRequest(MetadataPatchRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide exactly one editable metadata field.");
        return request;
    }

    private static void requirePositive(Long id, String field) {
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a positive integer.");
    }

    private static String requiredName(String submitted, String label, int maximum) {
        String value = trimmed(submitted);
        if (value == null || value.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, label + " is required.");
        maximum(value, maximum, label);
        return value;
    }

    private static void maximum(String value, int maximum, String label) {
        if (value != null && value.length() > maximum) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                label + " must contain no more than " + maximum + " characters.");
    }

    private static String trimmed(String value) {
        return value == null ? null : value.strip().replaceAll("^[\\p{Z}]+|[\\p{Z}]+$", "").strip();
    }

    private static ResponseStatusException duplicate(String name, Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "A book named '" + name + "' already exists.", cause);
    }

    private static BookResult snapshot(BookDetails book, List<TopicDetails> entries) {
        AuthorDetails author = book.getAuthorDetails();
        AuthorResult authorResult = author == null ? null : new AuthorResult(author.getAuthorId(), author.getAuthorName(),
                author.getCreatedAt(), author.getUpdatedAt());
        List<TopicResult> topicResults = entries.stream().sorted(Comparator.comparing(TopicDetails::getTopicId))
                .map(topic -> new TopicResult(topic.getTopicId(), topic.getTopicName(), topic.getDetails(),
                        topic.getStartPage(), topic.getEndPage(), topic.getPrintedStartPage(), topic.getPrintedEndPage(),
                        topic.getCreatedAt(), topic.getUpdatedAt())).toList();
        return new BookResult(book.getBookId(), book.getBookName(), book.getBookType(),
                author == null ? null : author.getAuthorName(), book.getBookDetails(), book.getTotalPages(),
                book.getBookUploadId(), book.getCreatedAt(), book.getUpdatedAt(), authorResult,
                topicResults.size(), topicResults);
    }
}
