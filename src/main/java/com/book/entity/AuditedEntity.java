package com.book.entity;

import java.time.LocalDateTime;
import com.book.time.UtcDates;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

@MappedSuperclass
public abstract class AuditedEntity {
    @Column(nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime createdAt;
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    private LocalDateTime updatedAt;

    protected AuditedEntity() { }

    /** Copies audit values for detached response snapshots, without triggering persistence callbacks. */
    protected AuditedEntity(AuditedEntity source) {
        createdAt = source.createdAt;
        updatedAt = source.updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = UtcDates.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = UtcDates.now();
    }

    @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
    public LocalDateTime getCreatedAt() { return createdAt; }
    @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
