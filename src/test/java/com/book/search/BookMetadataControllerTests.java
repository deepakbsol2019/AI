package com.book.search;

import java.util.List;
import java.util.Optional;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.metadata.controller.BookMetadataController;
import com.book.metadata.service.BookMetadataService;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookMetadataControllerTests {
    private final WebApplicationContextRunner contexts = new WebApplicationContextRunner()
            .withUserConfiguration(WebConfiguration.class);

    @Test
    void savesOnlyOneFieldAndReturnsCompleteConfirmedBookWithoutStoragePaths() {
        contexts.run(context -> {
            arrange(context);
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(patch("/api/books/7/metadata").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"bookName\":\"  Renamed book  \"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.bookName").value("Renamed book"))
                    .andExpect(jsonPath("$.bookType").value("History"))
                    .andExpect(jsonPath("$.authorName").value("Original author"))
                    .andExpect(jsonPath("$.bookDetails").value("Original description"))
                    .andExpect(jsonPath("$.topics.length()").value(2))
                    .andExpect(jsonPath("$.topics[1].topicName").value("Renamed book_offset"))
                    .andExpect(jsonPath("$.topics[1].topicDetails").value("Renamed book_offset detail"))
                    .andExpect(jsonPath("$.topics[1].pdfPath").doesNotExist());
            verify(context.getBean(BookDetailsRepository.class)).flush();
        });
    }

    @Test
    void distinguishesExplicitNullAndEmptyFromOmissionAndPreservesParagraphs() {
        contexts.run(context -> {
            arrange(context);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(patch("/api/books/7/metadata").contentType(MediaType.APPLICATION_JSON).content("{\"bookDetails\":null}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.bookDetails").isEmpty())
                    .andExpect(jsonPath("$.bookName").value("Original book"));
            mvc.perform(patch("/api/books/7/metadata").contentType(MediaType.APPLICATION_JSON).content("{\"bookDetails\":\"\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.bookDetails").value(""));
            mvc.perform(patch("/api/books/7/metadata").contentType(MediaType.APPLICATION_JSON).content("{\"bookType\":null}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.bookType").value("Unknown"));
            mvc.perform(patch("/api/books/7/topics/11/metadata").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"topicDetails\":\"First paragraph.\\n\\nSecond paragraph.\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.topics[0].topicDetails").value("First paragraph.\n\nSecond paragraph."))
                    .andExpect(jsonPath("$.topics[0].topicName").value("Real chapter"))
                    .andExpect(jsonPath("$.topics[0].startPage").value(2));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"bookName\":\"A\",\"bookType\":\"Story\"}", "{\"pdfPath\":\"replace.pdf\"}",
            "{\"bookType\":123}", "{\"bookDetails\":[]}", "{\"bookName\":\"A\",\"bookName\":\"B\"}",
            "{\"fields\":{}}", "{\"duplicate\":true}", "{\"topicName\":\"Wrong scope\"}", "[]", "null"})
    void rejectsUnknownMultipleDuplicateOrWronglyTypedFields(String json) {
        contexts.run(context -> {
            arrange(context);
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(patch("/api/books/7/metadata").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").isNotEmpty());
            verify(context.getBean(BookDetailsRepository.class), never()).flush();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"bookName\":null}", "{\"bookName\":\"   \"}"})
    void rejectsMissingRequiredNameWithUsefulDetail(String json) {
        contexts.run(context -> {
            arrange(context);
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(patch("/api/books/7/metadata").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Book name is required."));
        });
    }

    @Test
    void protectsTechnicalFieldsAndRejectsForeignTopicOrMissingBook() {
        contexts.run(context -> {
            arrange(context);
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(patch("/api/books/7/topics/12/metadata").contentType(MediaType.APPLICATION_JSON).content("{\"topicName\":\"Changed\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("read-only")));
            mvc.perform(patch("/api/books/7/topics/999/metadata").contentType(MediaType.APPLICATION_JSON).content("{\"topicDetails\":null}"))
                    .andExpect(status().isNotFound());
            mvc.perform(patch("/api/books/999/metadata").contentType(MediaType.APPLICATION_JSON).content("{\"bookType\":\"Story\"}"))
                    .andExpect(status().isNotFound());
            verify(context.getBean(BookDetailsRepository.class), never()).flush();
        });
    }

    private static void arrange(ApplicationContext context) {
        AuthorDetails author = new AuthorDetails(); author.setAuthorId(8L); author.setAuthorName("Original author");
        BookDetails book = new BookDetails(); book.setBookId(7L); book.setBookName("Original book");
        book.setBookType("History"); book.setBookDetails("Original description");
        book.setAuthorDetails(author); book.setTotalPages(10);
        TopicDetails chapter = new TopicDetails(); chapter.setTopicId(11L); chapter.setTopicName("Real chapter");
        chapter.setDetails("Original topic description"); chapter.setStartPage(2); chapter.setEndPage(10); chapter.setPdfPath("existing/chapter.pdf");
        TopicDetails offset = new TopicDetails(); offset.setTopicId(12L); offset.setTopicName("Original book_offset");
        offset.setDetails("Original book_offset detail"); offset.setStartPage(1); offset.setEndPage(1); offset.setPdfPath("existing/prefix.pdf");
        book.addTopic(chapter); book.addTopic(offset);
        when(context.getBean(BookDetailsRepository.class).findLockedByBookId(7L)).thenReturn(Optional.of(book));
        when(context.getBean(TopicDetailsRepository.class).findAllLockedForBook(7L)).thenReturn(List.of(chapter, offset));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({BookMetadataController.class, BookMetadataService.class})
    static class WebConfiguration {
        @Bean BookDetailsRepository bookDetailsRepository() { return mock(BookDetailsRepository.class); }
        @Bean TopicDetailsRepository topicDetailsRepository() { return mock(TopicDetailsRepository.class); }
        @Bean EntityManager entityManager() { return mock(EntityManager.class); }
    }
}
