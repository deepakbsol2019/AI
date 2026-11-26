package com.book.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.book.user.security.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Durable upload receipt, independent of the transaction that imports the book. */
@Entity(name = "Book_upload_status")
@Table(name = "book_upload_status")
public class BookUploadStatus {
    public static final String IN_PROGRESS = "inprogress";
    public static final String FAILED = "failed";
    public static final String SUCCESS = "Success";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "book_upload_id")
    @JsonProperty("book_upload_id")
    private Long bookUploadId;

    @Column(name = "tenant_id", updatable = false)
    @JsonIgnore
    private Long tenantId = TenantContext.currentTenantId();

    @Column(nullable = false, length = 16)
    private String status;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String message;

    public BookUploadStatus() { }

    public Long getBookUploadId() { return bookUploadId; }
    public void setBookUploadId(Long bookUploadId) { this.bookUploadId = bookUploadId; }
    @JsonIgnore public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    /** A running job cannot mutate the already-created HTTP acknowledgement. */
    public BookUploadStatus responseCopy() {
        BookUploadStatus copy = new BookUploadStatus();
        copy.bookUploadId = bookUploadId;
        copy.tenantId = tenantId;
        copy.status = status;
        copy.message = message;
        return copy;
    }
}
