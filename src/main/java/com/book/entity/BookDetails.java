package com.book.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.book.user.security.TenantContext;

@Entity
@Table(name = "book_details", uniqueConstraints = {
        @UniqueConstraint(name = "uk_book_tenant_name", columnNames = {"tenant_id", "book_name"}),
        @UniqueConstraint(name = "uk_book_tenant_canonical_name", columnNames = {"tenant_id", "canonical_book_name"})})
public class BookDetails extends AuditedEntity {
    public static final int MAX_BOOK_NAME_LENGTH = 255;
    public static final int MAX_BOOK_TYPE_LENGTH = 120;
    public static final String UNKNOWN_BOOK_TYPE = "Unknown";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long bookId;

    @Column(name = "tenant_id", updatable = false)
    @JsonIgnore
    private Long tenantId = TenantContext.currentTenantId();

    @Column(nullable = false, unique = true, length = 36)
    private String extractionId;

    @Column(name = "book_name", nullable = false, length = MAX_BOOK_NAME_LENGTH)
    private String bookName;
    @JsonIgnore
    @Column(name="canonical_book_name",insertable=false,updatable=false,
            columnDefinition="varchar(255) generated always as (lower(trim(book_name)))")
    private String canonicalBookName;

    @Column(name = "book_type", nullable = false, length = MAX_BOOK_TYPE_LENGTH)
    private String bookType = UNKNOWN_BOOK_TYPE;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String bookDetails;

    @Column(nullable = false)
    private int totalPages;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_upload_id", unique = true)
    @JsonIgnore
    private BookUploadStatus bookUploadStatus;

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
    @JsonIgnore public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    @PrePersist private void assignNewBookTenant() {
        if (tenantId == null) tenantId = TenantContext.currentTenantId();
    }
    public String getExtractionId() { return extractionId; }
    public void setExtractionId(String extractionId) { this.extractionId = extractionId; }
    public String getBookName() { return bookName; }
    public void setBookName(String bookName) { this.bookName = bookName; }
    public String getBookType() { return normalizeBookType(bookType); }
    public void setBookType(String bookType) { this.bookType = normalizeBookType(bookType); }
    private static String normalizeBookType(String value) {
        if (value == null || value.isBlank()) return UNKNOWN_BOOK_TYPE;
        String normalized = value.strip();
        return normalized.length() > MAX_BOOK_TYPE_LENGTH || UNKNOWN_BOOK_TYPE.equalsIgnoreCase(normalized)
                ? UNKNOWN_BOOK_TYPE : normalized;
    }
    public String getBookDetails() { return bookDetails; }
    public void setBookDetails(String bookDetails) { this.bookDetails = bookDetails; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    @JsonIgnore
    public BookUploadStatus getBookUploadStatus() { return bookUploadStatus; }
    public void setBookUploadStatus(BookUploadStatus bookUploadStatus) { this.bookUploadStatus = bookUploadStatus; }
    public Long getBookUploadId() { return bookUploadStatus == null ? null : bookUploadStatus.getBookUploadId(); }
    public AuthorDetails getAuthorDetails() { return authorDetails; }
    public void setAuthorDetails(AuthorDetails authorDetails) { this.authorDetails = authorDetails; }
    public List<TopicDetails> getTopics() { return topics; }
    public void setTopics(List<TopicDetails> topics) { this.topics = topics; }

    public void addTopic(TopicDetails topic) {
        topics.add(topic);
        topic.setBookDetails(this);
    }
}
