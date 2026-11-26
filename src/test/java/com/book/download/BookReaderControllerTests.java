package com.book.download;

import java.util.List;
import com.book.download.controller.impl.BookReaderControllerImpl;
import com.book.download.service.DownloadService;
import com.book.download.service.BookReaderService;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.TopicDetailsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookReaderControllerTests extends com.book.user.TenantAwareTest {
    private final WebApplicationContextRunner contexts = new WebApplicationContextRunner()
            .withUserConfiguration(ReaderWebConfiguration.class);

    @Test
    void deliversCompleteBookBytesInlineWithoutChangingDownloadAssembly() {
        contexts.run(context -> {
            var service = context.getBean(DownloadService.class);
            byte[] pdf = "%PDF-complete".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            when(service.downloadBook(7L)).thenReturn(pdf);
            MockMvcBuilders.webAppContextSetup(context).build().perform(get("/api/read/book").param("bookId", "7"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"book-7.pdf\""))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(content().bytes(pdf));
            verify(service).downloadBook(7L);
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void bindsRepeatedIdsAndDeliversRequestedTopicsInPhysicalSourceOrderInline() {
        contexts.run(context -> {
            var service = context.getBean(DownloadService.class);
            byte[] pdf = "%PDF-selected".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var repository = context.getBean(TopicDetailsRepository.class);
            BookDetails book = new BookDetails();
            book.setBookId(1L);
            book.setBookName("Book");
            book.setTotalPages(20);
            TopicDetails later = topic(book, 1L, "Later", 5, 20);
            TopicDetails offset = topic(book, 3L, "Book_offset", 1, 4);
            when(repository.findAllByBookDetailsBookIdAndTopicIdIn(1L, List.of(1L, 3L)))
                    .thenReturn(List.of(later, offset));
            when(service.downloadTopics(1L, List.of(3L, 1L))).thenReturn(pdf);
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/read/topics").param("bookId", "1").param("topicIds", "1", "3"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"book-1-topics.pdf\""))
                    .andExpect(content().bytes(pdf));
            verify(service).downloadTopics(1L, List.of(3L, 1L));
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void acceptsIndividualTopicAndPreservesOwnershipAndSourceErrors() {
        contexts.run(context -> {
            var service = context.getBean(DownloadService.class);
            when(service.downloadTopics(1L, List.of(3L))).thenReturn(new byte[]{1});
            when(service.downloadTopics(1L, List.of(99L))).thenThrow(
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Topic does not belong to this book."));
            when(service.downloadBook(8L)).thenThrow(
                    new ResponseStatusException(HttpStatus.CONFLICT, "The original source is unavailable."));
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/read/topics").param("bookId", "1").param("topicIds", "3")).andExpect(status().isOk());
            mvc.perform(get("/api/read/topics").param("bookId", "1").param("topicIds", "99")).andExpect(status().isNotFound());
            mvc.perform(get("/api/read/book").param("bookId", "8")).andExpect(status().isConflict());
        });
    }

    @Test
    void returnsConflictWhenSelectedTopicsCannotBePlacedInVerifiedSourceOrder() {
        contexts.run(context -> {
            BookDetails book = new BookDetails();
            book.setBookId(1L);
            book.setBookName("Book");
            book.setTotalPages(20);
            when(context.getBean(TopicDetailsRepository.class)
                    .findAllByBookDetailsBookIdAndTopicIdIn(1L, List.of(1L, 3L)))
                    .thenReturn(List.of(topic(book, 1L, "Unknown range", null, null),
                            topic(book, 3L, "Known range", 5, 20)));
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/read/topics").param("bookId", "1").param("topicIds", "1", "3"))
                    .andExpect(status().isConflict());
            verifyNoInteractions(context.getBean(DownloadService.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/read/book", "/api/read/book?bookId=bad", "/api/read/topics?bookId=1",
            "/api/read/topics?topicIds=1", "/api/read/topics?bookId=1&topicIds=bad"})
    void rejectsInvalidRequestsBeforeContentAssembly(String url) {
        contexts.run(context -> {
            MockMvcBuilders.webAppContextSetup(context).build().perform(get(url)).andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(DownloadService.class));
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({BookReaderControllerImpl.class, BookReaderService.class})
    static class ReaderWebConfiguration {
        @Bean DownloadService downloadService() { return mock(DownloadService.class); }
        @Bean TopicDetailsRepository topicDetailsRepository() { return mock(TopicDetailsRepository.class); }
    }

    private static TopicDetails topic(BookDetails book, Long id, String name, Integer start, Integer end) {
        TopicDetails topic = new TopicDetails();
        topic.setBookDetails(book);
        topic.setTopicId(id);
        topic.setTopicName(name);
        topic.setStartPage(start);
        topic.setEndPage(end);
        return topic;
    }
}
