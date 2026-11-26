package com.book.repository;

import java.util.Optional;
import com.book.entity.BookUploadStatus;
import com.book.user.security.TenantContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookUploadStatusRepository extends JpaRepository<BookUploadStatus, Long> {
    /** Scalar reads bypass any stale managed receipt left in an HTTP OpenEntityManagerInView context. */
    default Optional<CurrentStatus> readCurrent(Long uploadId) {
        return readCurrentInScope(uploadId, TenantContext.requireTenantId());
    }
    @Query("""
            select s.bookUploadId as bookUploadId, s.status as status, s.message as message, s.tenantId as tenantId
              from Book_upload_status s where s.bookUploadId = :uploadId and s.tenantId is not null
               and s.tenantId = :tenantId
            """)
    Optional<CurrentStatus> readCurrentInScope(@Param("uploadId") Long uploadId,
            @Param("tenantId") Long tenantId);

    /** Terminal state changes are guarded so a late/repeated job cannot replace a final outcome. */
    default int finishIfInProgress(Long uploadId, String expectedStatus, String status, String message) {
        return finishIfInProgressInScope(uploadId, expectedStatus, status, message,
                TenantContext.requireTenantId());
    }
    @Modifying
    @Query("""
            update Book_upload_status s set s.status = :status, s.message = :message
             where s.bookUploadId = :uploadId and s.status = :expectedStatus and s.tenantId is not null
               and s.tenantId = :tenantId
            """)
    int finishIfInProgressInScope(@Param("uploadId") Long uploadId, @Param("expectedStatus") String expectedStatus,
            @Param("status") String status, @Param("message") String message,
            @Param("tenantId") Long tenantId);

    interface CurrentStatus {
        Long getBookUploadId();
        Long getTenantId();
        String getStatus();
        String getMessage();
    }
}
