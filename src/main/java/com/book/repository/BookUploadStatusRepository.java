package com.book.repository;

import java.util.Optional;
import com.book.entity.BookUploadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookUploadStatusRepository extends JpaRepository<BookUploadStatus, Long> {
    /** Scalar reads bypass any stale managed receipt left in an HTTP OpenEntityManagerInView context. */
    @Query("""
            select s.bookUploadId as bookUploadId, s.status as status, s.message as message
              from Book_upload_status s where s.bookUploadId = :uploadId
            """)
    Optional<CurrentStatus> readCurrent(@Param("uploadId") Long uploadId);

    /** Terminal state changes are guarded so a late/repeated job cannot replace a final outcome. */
    @Modifying
    @Query("""
            update Book_upload_status s set s.status = :status, s.message = :message
             where s.bookUploadId = :uploadId and s.status = :expectedStatus
            """)
    int finishIfInProgress(@Param("uploadId") Long uploadId, @Param("expectedStatus") String expectedStatus,
            @Param("status") String status, @Param("message") String message);

    interface CurrentStatus {
        Long getBookUploadId();
        String getStatus();
        String getMessage();
    }
}
