package com.book.search;

import java.util.List;

import com.book.dto.BookSearchCriteria;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.search.controller.impl.SearchControllerImpl;
import com.book.search.repository.BookSearchQueryRepository;
import com.book.search.repository.BookSearchQueryRepository.SearchPage;
import com.book.search.service.impl.SearchServiceImpl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookSearchControllerTests {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(SearchWebConfiguration.class);

    @Test
    void getWithoutABodyListsBooksWithSharedServiceDefaults() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BookSearchQueryRepository repository = context.getBean(BookSearchQueryRepository.class);
            when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));

            mvc.perform(get("/api/search/books"))
                    .andExpect(handler().handlerType(SearchControllerImpl.class))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.books").isEmpty())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.totalPages").value(0));

            ArgumentCaptor<BookSearchCriteria> criteria = ArgumentCaptor.forClass(BookSearchCriteria.class);
            verify(repository).search(criteria.capture());
            assertThat(criteria.getValue().page()).isZero();
            assertThat(criteria.getValue().size()).isEqualTo(20);
            assertThat(criteria.getValue().query()).isNull();
        });
    }

    @Test
    void bindsEveryOptionalQueryParameterAndUsesTheSameServiceNormalization() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BookSearchQueryRepository repository = context.getBean(BookSearchQueryRepository.class);
            when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));

            mvc.perform(get("/api/search/books")
                            .queryParam("bookName", "  Java  ")
                            .queryParam("topicName", " Collections ")
                            .queryParam("authorName", " Asha Sharma ")
                            .queryParam("bookDetailsContains", " Practical Examples ")
                            .queryParam("topicDetailsContains", " Thread Pool ")
                            .queryParam("query", " Java ")
                            .queryParam("page", "3").queryParam("size", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(3))
                    .andExpect(jsonPath("$.size").value(7));

            ArgumentCaptor<BookSearchCriteria> captured = ArgumentCaptor.forClass(BookSearchCriteria.class);
            verify(repository).search(captured.capture());
            BookSearchCriteria criteria = captured.getValue();
            assertThat(criteria.bookName()).isEqualToIgnoringCase("Java");
            assertThat(criteria.topicName()).isEqualToIgnoringCase("Collections");
            assertThat(criteria.authorName()).isEqualToIgnoringCase("Asha Sharma");
            assertThat(criteria.bookDetailsContains()).isEqualToIgnoringCase("Practical Examples");
            assertThat(criteria.topicDetailsContains()).isEqualToIgnoringCase("Thread Pool");
            assertThat(criteria.query()).isEqualToIgnoringCase("Java");
            assertThat(criteria.page()).isEqualTo(3);
            assertThat(criteria.size()).isEqualTo(7);
        });
    }

    @Test
    void blankFiltersBecomeAnUnfilteredListing() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BookSearchQueryRepository repository = context.getBean(BookSearchQueryRepository.class);
            when(repository.search(any())).thenReturn(new SearchPage(List.of(), 0));

            mvc.perform(get("/api/search/books").queryParam("bookName", " ").queryParam("topicName", "")
                            .queryParam("authorName", "\t").queryParam("query", "  ")
                            .queryParam("bookDetailsContains", " ").queryParam("topicDetailsContains", ""))
                    .andExpect(status().isOk());

            ArgumentCaptor<BookSearchCriteria> captured = ArgumentCaptor.forClass(BookSearchCriteria.class);
            verify(repository).search(captured.capture());
            BookSearchCriteria criteria = captured.getValue();
            assertThat(criteria.bookName()).isNull();
            assertThat(criteria.topicName()).isNull();
            assertThat(criteria.authorName()).isNull();
            assertThat(criteria.bookDetailsContains()).isNull();
            assertThat(criteria.topicDetailsContains()).isNull();
            assertThat(criteria.query()).isNull();
        });
    }

    @Test
    void anEmptyPageRetainsTheMatchingBookCount() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            when(context.getBean(BookSearchQueryRepository.class).search(any()))
                    .thenReturn(new SearchPage(List.of(), 24));

            mvc.perform(get("/api/search/books").queryParam("page", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.books").isEmpty())
                    .andExpect(jsonPath("$.page").value(2))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalElements").value(24))
                    .andExpect(jsonPath("$.totalPages").value(2));
        });
    }

    @Test
    void aLargeNonnegativePageIsValidAndRetainsTheActualCount() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            when(context.getBean(BookSearchQueryRepository.class).search(any()))
                    .thenReturn(new SearchPage(List.of(), 24));

            mvc.perform(get("/api/search/books").queryParam("page", Integer.toString(Integer.MAX_VALUE))
                            .queryParam("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.books").isEmpty())
                    .andExpect(jsonPath("$.page").value(Integer.MAX_VALUE))
                    .andExpect(jsonPath("$.size").value(100))
                    .andExpect(jsonPath("$.totalElements").value(24))
                    .andExpect(jsonPath("$.totalPages").value(1));
        });
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,-1", "size,101"})
    void rejectsInvalidPaginationThroughTheSharedService(String parameter, String value) {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/search/books").queryParam(parameter, value)).andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(BookSearchQueryRepository.class));
        });
    }

    @Test
    void rejectsAnInvalidIntegerDuringQueryBinding() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/search/books").queryParam("page", "not-a-number"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(BookSearchQueryRepository.class));
        });
    }

    @Test
    void serializesStoredBookMetadataAndEveryTopicForATopicFilteredBook() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BookDetails book = new BookDetails();
            book.setBookId(101L);
            book.setBookName("Java");
            book.setBookType("Java Programming");
            book.setBookDetails("Introduction to Java programming.");
            book.setTotalPages(100);
            AuthorDetails author = new AuthorDetails();
            author.setAuthorId(77L);
            author.setAuthorName("Asha Sharma");
            book.setAuthorDetails(author);
            book.addTopic(topic(201L, "Java", "Language fundamentals."));
            book.addTopic(topic(202L, "Collections", "Lists, sets, and maps."));
            when(context.getBean(BookSearchQueryRepository.class).search(any()))
                    .thenReturn(new SearchPage(List.of(book), 1));

            mvc.perform(get("/api/search/books").queryParam("topicName", "Java"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.books[0].bookId").value(101))
                    .andExpect(jsonPath("$.books[0].bookName").value("Java"))
                    .andExpect(jsonPath("$.books[0].bookType").value("Java Programming"))
                    .andExpect(jsonPath("$.books[0].authorName").value("Asha Sharma"))
                    .andExpect(jsonPath("$.books[0].bookDetails").value("Introduction to Java programming."))
                    .andExpect(jsonPath("$.books[0].totalPages").value(100))
                    .andExpect(jsonPath("$.books[0].topicCount").value(2))
                    .andExpect(jsonPath("$.books[0].topics.length()").value(2))
                    .andExpect(jsonPath("$.books[0].topics[0].topicId").value(201))
                    .andExpect(jsonPath("$.books[0].topics[0].topicName").value("Java"))
                    .andExpect(jsonPath("$.books[0].topics[0].topicDetails").value("Language fundamentals."))
                    .andExpect(jsonPath("$.books[0].topics[1].topicName").value("Collections"))
                    .andExpect(jsonPath("$.books[0].topics[1].topicDetails").value("Lists, sets, and maps."))
                    .andExpect(jsonPath("$.books[0].topics[0].bookDetails").doesNotExist())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.totalPages").value(1));
        });
    }

    private static TopicDetails topic(Long id, String name, String details) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(id);
        topic.setTopicName(name);
        topic.setDetails(details);
        return topic;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SearchControllerImpl.class, SearchServiceImpl.class})
    static class SearchWebConfiguration {
        @Bean
        BookSearchQueryRepository bookSearchQueryRepository() { return mock(BookSearchQueryRepository.class); }
    }
}
