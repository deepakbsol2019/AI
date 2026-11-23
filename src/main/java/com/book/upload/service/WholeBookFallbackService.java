package com.book.upload.service;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.book.dto.BookExtractionResult;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** Replaces this upload's unusable, still-unpublished chapter metadata with one full-book topic. */
@Service
public class WholeBookFallbackService {
    private static final Logger logger = LoggerFactory.getLogger(WholeBookFallbackService.class);
    private final BookDetailsRepository books;
    private final TopicDetailsRepository topics;
    private final EntityManager entityManager;

    public WholeBookFallbackService(BookDetailsRepository books, TopicDetailsRepository topics, EntityManager entityManager) {
        this.books = books;
        this.topics = topics;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public BookExtractionResult convert(BookExtractionResult saved, String reason) {
        if (saved == null || saved.bookDetails() == null || saved.bookDetails().getBookId() == null) {
            throw new IllegalArgumentException("A saved current book is required for whole-book fallback.");
        }
        Long bookId = saved.bookDetails().getBookId();
        String expectedName = saved.bookDetails().getBookName();
        int expectedPages = saved.bookDetails().getTotalPages();
        BookDetails book = books.findLockedByBookId(bookId)
                .orElseThrow(() -> new IllegalStateException("The current upload's book no longer exists."));
        entityManager.refresh(book, LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(book.getBookName(), expectedName) || book.getTotalPages() != expectedPages || expectedPages < 1) {
            throw new IllegalStateException("The current book changed before whole-book fallback.");
        }
        List<TopicDetails> current = topics.findAllLockedForBook(bookId);
        for (TopicDetails topic : current) {
            entityManager.refresh(topic, LockModeType.PESSIMISTIC_WRITE);
            if (!Objects.equals(topic.getBookDetails().getBookId(), bookId) || topic.getPdfPath() != null) {
                throw new IllegalStateException("Whole-book fallback cannot replace published or unrelated topic files.");
            }
        }
        // The offset writer may have inserted a row outside the parent's initialized collection.
        // Remove every locked current-book row explicitly as well as clearing the owning graph.
        book.getTopics().clear();
        topics.deleteAll(current);
        topics.flush();

        TopicDetails fullBook = new TopicDetails();
        fullBook.setTopicName(book.getBookName());
        fullBook.setDetails(book.getBookDetails());
        fullBook.setStartPage(1);
        fullBook.setEndPage(book.getTotalPages());
        book.addTopic(fullBook);
        topics.saveAndFlush(fullBook);
        logger.info("Book {} will upload as one complete PDF: {}", bookId, reason);
        return new BookExtractionResult(saved.authorDetails(), book, List.of(fullBook.responseCopy()),
                saved.pageOffset(), saved.uploadedFile(), true, reason);
    }
}
