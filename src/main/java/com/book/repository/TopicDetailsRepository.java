package com.book.repository;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.book.entity.TopicDetails;

public interface TopicDetailsRepository extends JpaRepository<TopicDetails, Long> {
    List<TopicDetails> findAllByBookDetailsBookIdOrderByTopicIdAsc(Long bookId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TopicDetails t where t.bookDetails.bookId = :bookId order by t.topicId asc")
    List<TopicDetails> findAllLockedForBook(@Param("bookId") Long bookId);

    /** Bulk DML deliberately avoids entity lifecycle callbacks: only these two columns may change. */
    @Modifying
    @Query("""
            update TopicDetails t
               set t.startPage = coalesce(t.startPage, :startPage),
                   t.endPage = coalesce(t.endPage, :endPage)
             where t.bookDetails.bookId = :bookId and t.topicId = :topicId
               and t.printedStartPage = :printedStartPage and t.printedEndPage = :printedEndPage
               and (t.startPage is null or t.startPage = :startPage)
               and (t.endPage is null or t.endPage = :endPage)
               and (t.startPage is null or t.endPage is null)
            """)
    int fillMissingPdfPages(@Param("bookId") Long bookId, @Param("topicId") Long topicId,
            @Param("printedStartPage") int printedStartPage, @Param("printedEndPage") int printedEndPage,
            @Param("startPage") int startPage, @Param("endPage") int endPage);

    /** Called only by the transaction-owning upload thread after every file task succeeded. */
    @Modifying
    @Query("""
            update TopicDetails t set t.pdfPath = :pdfPath
             where t.bookDetails.bookId = :bookId and t.topicId = :topicId
               and t.startPage = :startPage and t.endPage = :endPage and t.pdfPath is null
            """)
    int assignPdfPath(@Param("bookId") Long bookId, @Param("topicId") Long topicId,
            @Param("startPage") int startPage, @Param("endPage") int endPage,
            @Param("pdfPath") String pdfPath);
}
