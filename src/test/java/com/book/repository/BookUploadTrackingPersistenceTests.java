package com.book.repository;

import java.util.UUID;

import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false, properties = { "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
class BookUploadTrackingPersistenceTests {
    @Autowired BookDetailsRepository books;
    @Autowired BookUploadStatusRepository uploads;
    @Autowired EntityManager entityManager;

    @Test
    void linksTheCommittedStatusWithoutCascadingItsIdOnlyReference() {
        BookUploadStatus upload = new BookUploadStatus();
        upload.setStatus(BookUploadStatus.IN_PROGRESS);
        upload.setMessage("original.pdf upload service is uploading book topic by topic. please check final upload status after some time.");
        uploads.saveAndFlush(upload);
        Long uploadId = upload.getBookUploadId();
        entityManager.clear();

        BookUploadStatus reference = new BookUploadStatus();
        reference.setBookUploadId(uploadId);
        BookDetails book = book("Tracked " + UUID.randomUUID());
        book.setBookUploadStatus(reference);
        books.saveAndFlush(book);
        entityManager.clear();

        BookDetails stored = books.findById(book.getBookId()).orElseThrow();
        assertThat(stored.getBookUploadId()).isEqualTo(uploadId);
        BookUploadStatus unchanged = uploads.findById(uploadId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(BookUploadStatus.IN_PROGRESS);
        assertThat(unchanged.getMessage()).startsWith("original.pdf upload service");
        String json = JsonMapper.builder().build().writeValueAsString(stored);
        assertThat(json).contains("\"bookUploadId\":" + uploadId).doesNotContain("bookUploadStatus", "upload service");
    }

    @Test
    void aTrackingRecordCannotBeAttachedToTwoDifferentBooks() {
        BookUploadStatus upload = new BookUploadStatus();
        upload.setStatus(BookUploadStatus.IN_PROGRESS);
        upload.setMessage("Uploading");
        uploads.saveAndFlush(upload);
        BookDetails first = book("First " + UUID.randomUUID());
        first.setBookUploadStatus(upload);
        books.saveAndFlush(first);
        BookDetails second = book("Second " + UUID.randomUUID());
        second.setBookUploadStatus(upload);

        assertThatThrownBy(() -> books.saveAndFlush(second)).isInstanceOf(RuntimeException.class);
    }

    private static BookDetails book(String name) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Writer");
        BookDetails book = new BookDetails();
        book.setExtractionId(UUID.randomUUID().toString());
        book.setBookName(name);
        book.setBookDetails("Description");
        book.setTotalPages(10);
        book.setAuthorDetails(author);
        return book;
    }
}
