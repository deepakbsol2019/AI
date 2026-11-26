package com.book.search.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.book.dto.BookSearchCriteria;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.user.security.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.ParameterExpression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.hibernate.query.criteria.JpaCriteriaQuery;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.stereotype.Repository;

/** Database-side matching and ID pagination keep topic matches separate from complete topic loading. */
@Repository
public class BookSearchQueryRepository {
    private static final char LIKE_ESCAPE = '!';
    private final EntityManager entityManager;

    public BookSearchQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Receives already normalized and validated criteria from the shared search service. */
    public SearchPage search(BookSearchCriteria criteria) {
        long tenantId = TenantContext.requireTenantId();
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> countCriteria = builder.createQuery(Long.class);
        Root<BookDetails> countBook = countCriteria.from(BookDetails.class);
        Filters countFilters = new Filters(builder, countCriteria, countBook, criteria, tenantId);
        countCriteria.select(builder.countDistinct(countBook.get("bookId")))
                .where(countFilters.predicate());
        long total = countFilters.bind(entityManager.createQuery(countCriteria)).getSingleResult();
        long offset = (long) criteria.page() * criteria.size();
        if (total == 0 || offset >= total) {
            return new SearchPage(List.of(), total);
        }

        CriteriaQuery<Long> idCriteria = builder.createQuery(Long.class);
        Root<BookDetails> idBook = idCriteria.from(BookDetails.class);
        Filters idFilters = new Filters(builder, idCriteria, idBook, criteria, tenantId);
        idCriteria.select(idBook.get("bookId")).distinct(true)
                .where(idFilters.predicate()).orderBy(builder.asc(idBook.get("bookId")));
        // Hibernate's Number offsets preserve the full valid Integer page/size range.
        // Standard JPA setFirstResult(int) would overflow for large, valid page numbers.
        ((JpaCriteriaQuery<Long>) idCriteria).offset(offset).fetch(criteria.size());
        List<Long> ids = idFilters.bind(entityManager.createQuery(idCriteria)).getResultList();
        if (ids.isEmpty()) return new SearchPage(List.of(), total);

        CriteriaQuery<BookDetails> completeCriteria = builder.createQuery(BookDetails.class);
        Root<BookDetails> completeBook = completeCriteria.from(BookDetails.class);
        completeBook.fetch("authorDetails", JoinType.LEFT);
        completeBook.fetch("bookUploadStatus", JoinType.LEFT);
        completeBook.fetch("topics", JoinType.LEFT);
        ParameterExpression<List> pageIds = builder.parameter(List.class, "pageIds");
        completeCriteria.select(completeBook).where(builder.and(completeBook.get("bookId").in(pageIds),
                        visibleTenant(builder, completeBook, tenantId)))
                .orderBy(builder.asc(completeBook.get("bookId")));
        List<BookDetails> fetched = entityManager.createQuery(completeCriteria)
                .setParameter(pageIds, ids).getResultList();
        Map<Long, BookDetails> byId = new HashMap<>();
        fetched.forEach(book -> byId.putIfAbsent(book.getBookId(), book));
        List<BookDetails> ordered = new ArrayList<>(ids.size());
        ids.forEach(id -> {
            BookDetails book = byId.get(id);
            if (book != null) ordered.add(book);
        });
        return new SearchPage(ordered, total);
    }

    public record SearchPage(List<BookDetails> books, long totalElements) {
        public SearchPage { books = List.copyOf(books); }
    }

    private static Predicate visibleTenant(CriteriaBuilder builder, Root<BookDetails> book,
            long tenantId) {
        return builder.equal(book.get("tenantId"), tenantId);
    }

    private static final class Filters {
        private final CriteriaBuilder builder;
        private final AbstractQuery<?> query;
        private final Root<BookDetails> book;
        private final BookSearchCriteria criteria;
        private final long tenantId;
        private final Map<String, String> parameters = new LinkedHashMap<>();

        private Filters(CriteriaBuilder builder, AbstractQuery<?> query, Root<BookDetails> book,
                BookSearchCriteria criteria, long tenantId) {
            this.builder = builder;
            this.query = query;
            this.book = book;
            this.criteria = criteria;
            this.tenantId = tenantId;
        }

        private Predicate predicate() {
            List<Predicate> all = new ArrayList<>();
            all.add(visibleTenant(builder, book, tenantId));
            Join<BookDetails, AuthorDetails> author = criteria.authorName() != null || criteria.query() != null
                    ? book.join("authorDetails", JoinType.LEFT) : null;
            if (criteria.bookName() != null) {
                all.add(contains(book.get("bookName"), parameter("bookNamePattern", pattern(criteria.bookName()))));
            }
            if (criteria.authorName() != null) {
                all.add(contains(author.get("authorName"), parameter("authorNamePattern", pattern(criteria.authorName()))));
            }
            if (criteria.bookDetailsContains() != null) {
                all.add(contains(book.get("bookDetails"),
                        parameter("bookDetailsPattern", pattern(criteria.bookDetailsContains()))));
            }
            if (criteria.topicName() != null || criteria.topicDetailsContains() != null) {
                Subquery<Integer> matchingTopic = query.subquery(Integer.class);
                Root<TopicDetails> topic = matchingTopic.from(TopicDetails.class);
                List<Predicate> sameTopic = new ArrayList<>();
                sameTopic.add(builder.equal(topic.get("bookDetails").get("bookId"), book.get("bookId")));
                if (criteria.topicName() != null) {
                    sameTopic.add(contains(topic.get("topicName"), parameter("topicNamePattern", pattern(criteria.topicName()))));
                }
                if (criteria.topicDetailsContains() != null) {
                    sameTopic.add(contains(topic.get("details"),
                            parameter("topicDetailsPattern", pattern(criteria.topicDetailsContains()))));
                }
                matchingTopic.select(builder.literal(1)).where(builder.and(sameTopic.toArray(Predicate[]::new)));
                all.add(builder.exists(matchingTopic));
            }
            if (criteria.query() != null) {
                ParameterExpression<String> general = parameter("generalPattern", pattern(criteria.query()));
                Subquery<Integer> anyMatchingTopic = query.subquery(Integer.class);
                Root<TopicDetails> topic = anyMatchingTopic.from(TopicDetails.class);
                anyMatchingTopic.select(builder.literal(1)).where(
                        builder.equal(topic.get("bookDetails").get("bookId"), book.get("bookId")),
                        builder.or(contains(topic.get("topicName"), general),
                                contains(topic.get("details"), general)));
                all.add(builder.or(contains(book.get("bookName"), general),
                        contains(author.get("authorName"), general),
                        contains(book.get("bookDetails"), general),
                        builder.exists(anyMatchingTopic)));
            }
            return builder.and(all.toArray(Predicate[]::new));
        }

        private ParameterExpression<String> parameter(String name, String value) {
            parameters.put(name, value.toLowerCase(Locale.ROOT));
            return builder.parameter(String.class, name);
        }

        private Predicate contains(Expression<String> field, ParameterExpression<String> value) {
            // ilike renders the dialect's case-insensitive comparison without narrowing the LONGTEXT/CLOB.
            // A semantic LOWER(CLOB) call is rejected by Hibernate, while VARCHAR casts can truncate data.
            return ((HibernateCriteriaBuilder) builder).ilike(field, value, LIKE_ESCAPE);
        }

        private <T> TypedQuery<T> bind(TypedQuery<T> typedQuery) {
            parameters.forEach(typedQuery::setParameter);
            return typedQuery;
        }
    }

    private static String pattern(String literal) {
        return "%" + literal.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
