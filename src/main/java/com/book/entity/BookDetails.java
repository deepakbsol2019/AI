package com.book.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "book_details")
public class BookDetails extends AuditedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long bookId;

    @Column(nullable = false, unique = true, length = 36)
    private String extractionId;

    @Column(nullable = false, length = 1024)
    private String bookName;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String bookDetails;

    @Column(nullable = false)
    private int totalPages;

    @ManyToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "author_id", nullable = false)
    @JsonIgnore
    private AuthorDetails authorDetails;

    @OneToMany(mappedBy = "bookDetails", cascade = CascadeType.ALL, orphanRemoval = true)
    // Generated IDs preserve insertion order even when physical page ranges are unknown.
    @OrderBy("topicId ASC")
    @JsonIgnore
    private List<TopicDetails> topics = new ArrayList<>();

    public BookDetails() { }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public String getExtractionId() { return extractionId; }
    public void setExtractionId(String extractionId) { this.extractionId = extractionId; }
    public String getBookName() { return bookName; }
    public void setBookName(String bookName) { this.bookName = bookName; }
    public String getBookDetails() { return bookDetails; }
    public void setBookDetails(String bookDetails) { this.bookDetails = bookDetails; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public AuthorDetails getAuthorDetails() { return authorDetails; }
    public void setAuthorDetails(AuthorDetails authorDetails) { this.authorDetails = authorDetails; }
    public List<TopicDetails> getTopics() { return topics; }
    public void setTopics(List<TopicDetails> topics) { this.topics = topics; }

    public void addTopic(TopicDetails topic) {
        topics.add(topic);
        topic.setBookDetails(this);
    }
}
