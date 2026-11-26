package com.book.entity;

import java.time.Instant;

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

    // Numeric provenance intentionally survives account-only deletion; never cascade tenant content.
    @Column(name = "uploader_user_id", updatable = false)
    private Long uploadedBy = TenantContext.currentUserId();
    @Column(name = "file_name", length = 1024, updatable = false)
    private String fileName;
    @Column(name = "book_name", length = 255)
    private String bookName;
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "processing_state", length = 16)
    private String state;

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
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long value) { uploadedBy=value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { fileName=value; }
    public String getBookName() { return bookName; }
    public void setBookName(String value) { bookName=value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt=value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt=value; }
    public String getState() {
        if(state!=null)return state;
        return SUCCESS.equals(status)?"COMPLETED":FAILED.equals(status)?"FAILED":"PROCESSING";
    }
    public void setState(String value) { state=value; }
    public String getFailureReason() { return FAILED.equals(status)?message:null; }

    /** A running job cannot mutate the already-created HTTP acknowledgement. */
    public BookUploadStatus responseCopy() {
        BookUploadStatus copy = new BookUploadStatus();
        copy.bookUploadId = bookUploadId;
        copy.tenantId = tenantId;
        copy.status = status;
        copy.message = message;
        copy.uploadedBy=uploadedBy;copy.fileName=fileName;copy.bookName=bookName;
        copy.createdAt=createdAt;copy.updatedAt=updatedAt;copy.state=state;
        return copy;
    }
}
