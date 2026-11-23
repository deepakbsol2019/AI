package com.book.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import com.book.entity.BookDetails;

public interface BookDetailsRepository extends JpaRepository<BookDetails, Long> {
    @Query("select (count(b) > 0) from BookDetails b where lower(trim(b.bookName)) = lower(:bookName)")
    boolean existsByCanonicalBookName(@Param("bookName") String bookName);

    // Every offset writer locks the parent before checking/inserting its reserved row.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookDetails b where b.bookId = :bookId")
    Optional<BookDetails> findLockedByBookId(@Param("bookId") Long bookId);
}
