package com.book.search.repository;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import com.book.dto.BookSearchCriteria;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;
import com.book.entity.TopicDetails;
import com.book.search.repository.BookSearchQueryRepository.SearchPage;
import com.book.search.controller.impl.SearchControllerImpl;
import com.book.search.service.impl.SearchServiceImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO"})
@Import(BookSearchQueryRepository.class)
class BookSearchQueryRepositoryTests extends com.book.user.TenantAwareTest {
    @Autowired BookSearchQueryRepository search;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void nameFiltersAreCaseInsensitiveLiteralSubstrings() {
        BookDetails java = book("Java", "Asha Sharma", null, topic("Collections", null));
        BookDetails effective = book("Effective Java", "Asha Sharma Jr", null, topic("Advanced Collections", null));
        BookDetails script = book("JavaScript", "Other Writer", null, topic("Collection", null));

        assertIds(run(criteria("jAvA", null, null, null, null, null, 0, 20)), java, effective, script);
        assertIds(run(criteria(null, "cOlLeCtIoNs", null, null, null, null, 0, 20)), java, effective);
        assertIds(run(criteria(null, null, "ASHA SHARMA", null, null, null, 0, 20)), java, effective);
        assertIds(run(criteria("Jav", null, null, null, null, null, 0, 20)), java, effective, script);
    }

    @Test
    void nameContainsFiltersEscapePercentUnderscoreEscapeAndBackslashLiterally() {
        BookDetails literal = book("Guide 100%_!\\ Supplement", "Dr 100%_!\\ Writer", null,
                topic("Intro 100%_!\\ Topic", null));
        book("Guide 100abcX!\\ Supplement", "Dr 100abcX!\\ Writer", null,
                topic("Intro 100abcX!\\ Topic", null));

        assertIds(run(criteria("100%_!\\", null, null, null, null, null, 0, 20)), literal);
        assertIds(run(criteria(null, "100%_!\\", null, null, null, null, 0, 20)), literal);
        assertIds(run(criteria(null, null, "100%_!\\", null, null, null, 0, 20)), literal);
        assertIds(run(criteria(null, null, null, null, null, "100%_!\\", 0, 20)), literal);
    }

    @Test
    void detailsFiltersUseCaseInsensitiveLiteralSubstringsIncludingLikeMetacharacters() {
        BookDetails literal = book("Literal", "Writer", "Tune 100%_! Throughput",
                topic("Concurrency", "Use a Thread POOL_%! for work"));
        book("Wildcard lookalike", "Writer", "Tune 100abcX! Throughput",
                topic("Concurrency", "Use a Thread POOLabcX! for work"));

        assertIds(run(criteria(null, null, null, "100%_! throughput", null, null, 0, 20)), literal);
        assertIds(run(criteria(null, null, null, null, "thread pool_%!", null, 0, 20)), literal);
        assertIds(run(criteria(null, null, null, "100%_!", "POOL_%!", null, 0, 20)), literal);
    }

    @Test
    void generalSearchMatchesEachOfItsFiveFieldsIncludingPartialNames() {
        BookDetails title = book("Java", "No other field", null);
        BookDetails author = book("Author match", "JAVA", null);
        BookDetails description = book("Details match", null, "An introduction to JAVA programming.");
        BookDetails topicName = book("Topic-name match", null, null, topic("JaVa", null));
        BookDetails topicDetails = book("Topic-details match", null, null, topic("Language", "Use Java for applications"));
        BookDetails partial = book("Effective Java", "Java Expert", null, topic("JavaScript", null));

        assertIds(run(criteria(null, null, null, null, null, "Java", 0, 20)),
                title, author, description, topicName, topicDetails, partial);
    }

    @Test
    void generalDetailsSearchEscapesWildcardsInsteadOfMatchingEverything() {
        BookDetails literal = book("Metacharacters", null, "Literal %_! input");
        book("No literal value", null, "Plain text is different", topic("Plain", "Several other words"));

        assertIds(run(criteria(null, null, null, null, null, "%_!", 0, 20)), literal);
    }

    @Test
    void multipleMatchingFieldsAndTopicsProduceOnePersistentBookId() {
        BookDetails matched = book("Java", "Java", "Java details",
                topic("Java", "Java examples"), topic("More", "More Java examples"));

        SearchPage page = run(criteria(null, null, null, null, null, "java", 0, 20));

        assertIds(page, matched);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.books().getFirst().getTopics()).hasSize(2);
    }

    @Test
    void explicitFiltersAreAndedOutsideTheWholeGeneralOrGroup() {
        BookDetails correct = book("Architecture", "Asha Sharma", "Practical Java examples",
                topic("Concurrency", "Uses a thread pool"));
        book("Java", "Other Writer", "No explicit author match",
                topic("Concurrency", "Uses a thread pool"));
        book("Unrelated", "Asha Sharma", "No general term",
                topic("Concurrency", "Uses a thread pool"));

        assertIds(run(criteria(null, "Concurrency", "asha sharma", null, "thread pool", "java", 0, 20)), correct);
        assertThat(run(criteria("Architecture", null, "Other Writer", null, null, null, 0, 20)).books()).isEmpty();
    }

    @Test
    void explicitTopicNameAndDetailsMustBelongToOneTopic() {
        book("Split topic match", null, null,
                topic("Practical Concurrency", "Locks"), topic("Other", "A thread pool"));
        BookDetails sameTopic = book("Same topic match", null, null,
                topic("Practical Concurrency", "A thread pool"), topic("Other", "Locks"));

        assertIds(run(criteria(null, "concur", null, null, "thread pool", null, 0, 20)), sameTopic);
    }

    @Test
    void actualHttpListingDefaultsToFiveAndContainsTopicMatchesStayDistinctAcrossPages() throws Exception {
        List<BookDetails> matches = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            matches.add(book("Effective Java " + i, "Test Writer " + i, null,
                    topic("Java foundations", null), topic("Advanced Java", null), topic("Unrelated topic", null)));
        }
        book("Unrelated title", null, null, topic("Other subject", null));
        entityManager.flush();
        entityManager.clear();
        var mvc = MockMvcBuilders.standaloneSetup(new SearchControllerImpl(new SearchServiceImpl(search))).build();
        JsonMapper json = JsonMapper.builder().build();
        var unfiltered = json.readTree(mvc.perform(get("/api/search/books"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(unfiltered.path("size").asInt()).isEqualTo(5);
        assertThat(unfiltered.path("books").size()).isEqualTo(5);
        assertThat(unfiltered.path("totalElements").asLong()).isEqualTo(8);
        String firstBody = mvc.perform(get("/api/search/books").queryParam("bookName", "  jAvA  ")
                        .queryParam("authorName", " WRITER ").queryParam("topicName", " java "))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var first = json.readTree(firstBody);
        assertThat(first.path("size").asInt()).isEqualTo(5);
        assertThat(first.path("totalElements").asLong()).isEqualTo(7);
        assertThat(first.path("totalPages").asLong()).isEqualTo(2);
        assertThat(first.path("books").size()).isEqualTo(5);
        assertThat(first.path("books").get(0).path("bookId").asLong()).isEqualTo(matches.getFirst().getBookId());
        assertThat(first.path("books").get(0).path("topics").size()).isEqualTo(3);
        var second = json.readTree(mvc.perform(get("/api/search/books").queryParam("topicName", "java")
                        .queryParam("page", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second.path("books").size()).isEqualTo(2);
        assertThat(second.path("books").get(0).path("bookId").asLong()).isEqualTo(matches.get(5).getBookId());
        assertThat(second.path("totalElements").asLong()).isEqualTo(7);
        var larger = json.readTree(mvc.perform(get("/api/search/books").queryParam("bookName", "java")
                        .queryParam("size", "20"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(larger.path("size").asInt()).isEqualTo(20);
        assertThat(larger.path("books").size()).isEqualTo(7);
        var absent = json.readTree(mvc.perform(get("/api/search/books").queryParam("bookName", "never appears"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(absent.path("books").size()).isZero();
        assertThat(absent.path("totalElements").asLong()).isZero();
    }

    @Test
    void generalTopicSearchIsIndependentOfTheTopicSatisfyingExplicitFilters() {
        BookDetails matched = book("Independent topic conditions", null, null,
                topic("Concurrency", "Locks"), topic("Java", "Language syntax"));

        assertIds(run(criteria(null, "concurrency", null, null, "locks", "java", 0, 20)), matched);
    }

    @Test
    void missingAuthorNameDescriptionsAndTopicsDoNotHideOtherMatches() {
        BookDetails sparse = book("Java", null, null);
        book("Unrelated", null, null);

        SearchPage result = run(criteria(null, null, null, null, null, "java", 0, 20));

        assertIds(result, sparse);
        assertThat(result.books().getFirst().getAuthorDetails().getAuthorName()).isNull();
        assertThat(result.books().getFirst().getBookDetails()).isNull();
        assertThat(result.books().getFirst().getTopics()).isEmpty();
        assertThat(run(criteria(null, null, "unknown", null, null, null, 0, 20)).totalElements()).isZero();
    }

    @Test
    void topicFilterReturnsAllTopicsIncludingUnmatchedAndReservedOffsetRows() {
        BookDetails book = book("Complete Topics", "Writer", "Description",
                topic("Java", "Selected"), topic("Unmatched", "Still returned"),
                topic("Complete Topics_offset", "Front matter"));

        SearchPage page = run(criteria(null, "java", null, null, null, null, 0, 20));

        assertIds(page, book);
        assertThat(page.books().getFirst().getTopics()).extracting(TopicDetails::getTopicName)
                .containsExactly("Java", "Unmatched", "Complete Topics_offset");
    }

    @Test
    void distinctCountingAndIdOrderingHappenBeforePagination() {
        BookDetails first = book("First", null, "Java", topic("Java", "Java"), topic("Extra", "Java"));
        BookDetails second = book("Second", null, "Java", topic("Java", "Java"));
        BookDetails third = book("Third", null, "Java");
        book("No match", null, null);

        SearchPage page0 = run(criteria(null, null, null, null, null, "java", 0, 1));
        SearchPage page1 = run(criteria(null, null, null, null, null, "java", 1, 1));
        SearchPage page2 = run(criteria(null, null, null, null, null, "java", 2, 1));
        SearchPage after = run(criteria(null, null, null, null, null, "java", 3, 1));

        assertIds(page0, first);
        assertIds(page1, second);
        assertIds(page2, third);
        assertThat(List.of(page0.totalElements(), page1.totalElements(), page2.totalElements(), after.totalElements()))
                .containsOnly(3L);
        assertThat(after.books()).isEmpty();
        assertThat(page0.books().getFirst().getTopics()).hasSize(2);
    }

    @Test
    void noFiltersListAllAndUnsuccessfulFiltersNeverFallbackToTheListing() {
        BookDetails first = book("First listed", null, null);
        BookDetails second = book("Second listed", null, null);

        assertIds(run(criteria(null, null, null, null, null, null, 0, 20)), first, second);
        SearchPage missing = run(criteria("Absent", null, null, null, null, null, 0, 20));
        assertThat(missing.books()).isEmpty();
        assertThat(missing.totalElements()).isZero();
        SearchPage hugePage = run(criteria(null, null, null, null, null, null, Integer.MAX_VALUE, 100));
        assertThat(hugePage.books()).isEmpty();
        assertThat(hugePage.totalElements()).isEqualTo(2);
    }

    @Test
    void longLobDetailsRemainSearchablePastTypicalVarcharLengths() {
        String longDescription = "prefix ".repeat(600) + "BOOK_NEEDLE%_!";
        String longTopic = "body ".repeat(700) + "TOPIC_NEEDLE%_!";
        BookDetails match = book("Long descriptions", null, longDescription, topic("Long topic", longTopic));

        assertIds(run(criteria(null, null, null, "book_needle%_!", null, null, 0, 20)), match);
        assertIds(run(criteria(null, null, null, null, "topic_needle%_!", null, 0, 20)), match);
        assertIds(run(criteria(null, null, null, null, null, "topic_needle%_!", 0, 20)), match);
    }

    @Test
    void bulkLoadingUsesThreeQueriesRegardlessOfBookAndTopicCounts() {
        for (int i = 0; i < 4; i++) {
            BookDetails book = book("Fetched " + i, "Writer " + i, "Java", topic("Java", "First"), topic("Extra", "Second"));
            BookUploadStatus status = new BookUploadStatus();
            status.setStatus(BookUploadStatus.SUCCESS);
            status.setMessage("Uploaded");
            entityManager.persist(status);
            book.setBookUploadStatus(status);
        }
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            SearchPage result = search.search(criteria(null, null, null, null, null, "java", 0, 20));
            assertThat(result.books()).hasSize(4);
            result.books().forEach(book -> {
                assertThat(book.getAuthorDetails().getAuthorName()).startsWith("Writer");
                assertThat(book.getTopics()).hasSize(2);
                assertThat(book.getBookUploadId()).isNotNull();
            });
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        }
        finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void differentPersistentIdsRemainSeparateEvenForIdenticalTitles() {
        jdbc.execute("alter table pdf_file_details drop constraint uk_book_tenant_name");
        try {
            // This fixture deliberately represents legacy duplicate titles; production keeps both indexes.
            jdbc.execute("alter table pdf_file_details drop constraint uk_book_tenant_canonical_name");
            try {
                new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                    BookDetails first = book("Legacy Same Title", "Same Writer", null, topic("Same Topic", null));
                    BookDetails second = book("Legacy Same Title", "Same Writer", null, topic("Same Topic", null));
                    SearchPage result = run(criteria("legacy same title", null, null, null, null, null, 0, 20));
                    assertIds(result, first, second);
                    assertThat(result.totalElements()).isEqualTo(2);
                    transaction.setRollbackOnly();
                });
            }
            finally {
                jdbc.execute("alter table pdf_file_details add constraint uk_book_tenant_canonical_name unique (tenant_id, canonical_book_name)");
            }
        }
        finally {
            jdbc.execute("alter table pdf_file_details add constraint uk_book_tenant_name unique (tenant_id, book_name)");
        }
    }

    private SearchPage run(BookSearchCriteria criteria) {
        entityManager.flush();
        entityManager.clear();
        return search.search(criteria);
    }

    private BookDetails book(String name, String authorName, String details, TopicDetails... topics) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName(authorName);
        BookDetails book = new BookDetails();
        book.setBookName(name);
        book.setBookDetails(details);
        book.setTotalPages(10);
        book.setExtractionId(UUID.randomUUID().toString());
        book.setAuthorDetails(author);
        for (TopicDetails topic : topics) book.addTopic(topic);
        entityManager.persist(book);
        return book;
    }

    private static TopicDetails topic(String name, String details) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicName(name);
        topic.setDetails(details);
        return topic;
    }

    private static BookSearchCriteria criteria(String bookName, String topicName, String authorName,
            String bookContains, String topicContains, String query, int page, int size) {
        return new BookSearchCriteria(bookName, topicName, authorName, bookContains, topicContains, query, page, size);
    }

    private static void assertIds(SearchPage page, BookDetails... expected) {
        assertThat(page.books()).extracting(BookDetails::getBookId)
                .containsExactly(java.util.Arrays.stream(expected).map(BookDetails::getBookId).toArray(Long[]::new));
    }
}
