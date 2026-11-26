package com.book.repository;

import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.book.entity.BookUploadStatus;
import com.book.user.security.TenantContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookUploadStatusRepository extends JpaRepository<BookUploadStatus, Long> {
    /** Scalar reads bypass any stale managed receipt left in an HTTP OpenEntityManagerInView context. */
    default Optional<CurrentStatus> readCurrent(Long uploadId) {
        return readCurrentForActor(uploadId, TenantContext.requireTenantId(), TenantContext.currentUserId());
    }
    default Optional<CurrentStatus> readCurrentInScope(Long uploadId, Long tenantId) {
        return readCurrentForActor(uploadId,tenantId,TenantContext.currentUserId());
    }
    @Query("""
            select s.bookUploadId as bookUploadId, s.status as status, s.message as message, s.tenantId as tenantId,
              s.uploadedBy as uploadedBy, s.fileName as fileName, s.bookName as bookName,
              s.createdAt as createdAt, s.updatedAt as updatedAt, s.state as state
              from Book_upload_status s where s.bookUploadId = :uploadId and s.tenantId is not null
               and s.tenantId = :tenantId
               and ((:userId is not null and s.uploadedBy=:userId) or (:userId is null and s.uploadedBy is null))
            """)
    Optional<CurrentStatus> readCurrentForActor(@Param("uploadId") Long uploadId,
            @Param("tenantId") Long tenantId,@Param("userId") Long userId);

    @Query("select s from Book_upload_status s where s.tenantId=:tenantId and s.uploadedBy=:userId order by s.createdAt desc,s.bookUploadId desc")
    Page<BookUploadStatus> history(@Param("tenantId") Long tenantId,@Param("userId") Long userId,Pageable pageable);

    /** Terminal state changes are guarded so a late/repeated job cannot replace a final outcome. */
    default int finishIfInProgress(Long uploadId, String expectedStatus, String status, String message) {
        return finishOwned(uploadId,expectedStatus,status,message,null,
                BookUploadStatus.SUCCESS.equals(status)?"COMPLETED":"FAILED",Instant.now(),TenantContext.requireTenantId(),TenantContext.currentUserId());
    }
    default int finishIfInProgressInScope(Long uploadId,String expectedStatus,String status,String message,Long tenantId) {
        return finishOwned(uploadId,expectedStatus,status,message,null,BookUploadStatus.SUCCESS.equals(status)?"COMPLETED":"FAILED",Instant.now(),tenantId,TenantContext.currentUserId());
    }
    @Modifying
    @Query("""
            update Book_upload_status s set s.status = :status, s.message = :message,
              s.state=:state,s.updatedAt=:updatedAt,s.bookName=coalesce(:bookName,s.bookName)
             where s.bookUploadId = :uploadId and s.status = :expectedStatus and s.tenantId is not null
               and s.tenantId = :tenantId
               and ((:userId is not null and s.uploadedBy=:userId) or (:userId is null and s.uploadedBy is null))
            """)
    int finishOwned(@Param("uploadId") Long uploadId,@Param("expectedStatus") String expectedStatus,@Param("status") String status,
            @Param("message") String message,@Param("bookName") String bookName,@Param("state") String state,@Param("updatedAt") Instant updatedAt,
            @Param("tenantId") Long tenantId,@Param("userId") Long userId);

    @Modifying
    @Query("""
            update Book_upload_status s set s.state='PROCESSING',s.updatedAt=:now
            where s.bookUploadId=:id and s.tenantId=:tenantId and s.status='inprogress'
              and ((:userId is not null and s.uploadedBy=:userId) or (:userId is null and s.uploadedBy is null))
            """)
    int markProcessing(@Param("id") Long id,@Param("tenantId") Long tenantId,@Param("userId") Long userId,@Param("now") Instant now);

    interface CurrentStatus {
        Long getBookUploadId();
        Long getTenantId();
        String getStatus();
        String getMessage();
        Long getUploadedBy();
        String getFileName();
        String getBookName();
        Instant getCreatedAt();
        Instant getUpdatedAt();
        String getState();
    }
}
