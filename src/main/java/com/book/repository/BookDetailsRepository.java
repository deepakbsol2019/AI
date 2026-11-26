package com.book.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import com.book.entity.BookDetails;
import com.book.user.security.TenantContext;

public interface BookDetailsRepository extends JpaRepository<BookDetails, Long> {
    default boolean existsByCanonicalBookName(String bookName) {
        return existsByCanonicalBookNameInTenant(bookName, TenantContext.requireTenantId());
    }
    @Query("select (count(b) > 0) from BookDetails b where b.tenantId = :tenantId and lower(trim(b.bookName)) = lower(:bookName)")
    boolean existsByCanonicalBookNameInTenant(@Param("bookName") String bookName, @Param("tenantId") Long tenantId);

    // Every offset writer locks the parent before checking/inserting its reserved row.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookDetails b where b.bookId = :bookId")
    Optional<BookDetails> findLockedByBookId(@Param("bookId") Long bookId);

    default boolean existsByCanonicalBookNameExcludingId(String bookName, Long bookId) {
        return existsByCanonicalBookNameExcludingIdInTenant(bookName, bookId, TenantContext.requireTenantId());
    }
    @Query("select (count(b) > 0) from BookDetails b where b.tenantId = :tenantId and b.bookId <> :bookId and lower(trim(b.bookName)) = lower(:bookName)")
    boolean existsByCanonicalBookNameExcludingIdInTenant(@Param("bookName") String bookName, @Param("bookId") Long bookId, @Param("tenantId") Long tenantId);

    @Query("select count(b) from BookDetails b where b.authorDetails.authorId = :authorId and b.bookId <> :bookId")
    long countOtherBooksForAuthor(@Param("authorId") Long authorId, @Param("bookId") Long bookId);

}
