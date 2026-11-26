package com.book.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false, properties = { "spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UniqueBookNamePersistenceTests extends com.book.user.TenantAwareTest {
    @Autowired BookDetailsRepository books;
    @Autowired JdbcTemplate jdbc;

    @Test
    void duplicateDatabaseInsertCannotCreateAnotherBookOrOrphanAuthor() {
        String name = "Unique " + UUID.randomUUID();
        BookDetails original = books.saveAndFlush(book(name));
        long beforeAuthors = authorCount();

        assertThatThrownBy(() -> books.saveAndFlush(book(name)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(books.findById(original.getBookId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from book_details where book_name = ?", Long.class, name))
                .isEqualTo(1);
        assertThat(authorCount()).isEqualTo(beforeAuthors);
    }

    @Test
    void friendlyDuplicateCheckIgnoresCaseAndLegacySurroundingSpaces() {
        String unique = "Legacy " + UUID.randomUUID();
        books.saveAndFlush(book("  " + unique + "  "));

        assertThat(books.existsByCanonicalBookName(unique.toUpperCase(java.util.Locale.ROOT))).isTrue();
        assertThat(books.existsByCanonicalBookName("Different " + unique)).isFalse();
    }

    @Test
    void canonicalNamesAreUniqueWithinTenantButMayRepeatAcrossTenants() {
        String name="Scoped "+UUID.randomUUID();books.saveAndFlush(book("  "+name+"  "));
        assertThatThrownBy(()->books.saveAndFlush(book(name.toUpperCase(java.util.Locale.ROOT)))).isInstanceOf(DataIntegrityViolationException.class);
        try(var another=com.book.user.security.TenantContext.use(2L,2L,false)) {
            assertThat(books.existsByCanonicalBookName(name)).isFalse();
            assertThat(books.saveAndFlush(book(name)).getBookId()).isPositive();
        }
        assertThat(books.existsByCanonicalBookName(name)).isTrue();
    }
    @Test
    void concurrentExactNameInsertsAreProtectedByTheDatabaseConstraint() throws Exception {
        String name = "Concurrent " + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> insert = () -> {
              try (var ignored = com.book.user.security.TenantContext.use(1L)) {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timed out");
                try {
                    books.saveAndFlush(book(name));
                    return true;
                }
                catch (DataIntegrityViolationException expected) {
                    return false;
                }
              }
            };
            var first = executor.submit(insert);
            var second = executor.submit(insert);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("select count(*) from book_details where book_name = ?", Long.class, name))
                .isEqualTo(1);
    }

    private long authorCount() {
        return jdbc.queryForObject("select count(*) from author_details", Long.class);
    }

    private static BookDetails book(String name) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Writer");
        BookDetails book = new BookDetails();
        book.setBookName(name);
        book.setExtractionId(UUID.randomUUID().toString());
        book.setTotalPages(10);
        book.setAuthorDetails(author);
        return book;
    }
}
