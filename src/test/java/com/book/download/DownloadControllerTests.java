package com.book.download;

import java.io.ByteArrayOutputStream;
import java.util.List;

import com.book.download.controller.impl.DownloadControllerImpl;
import com.book.download.service.DownloadService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DownloadControllerTests {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(DownloadWebConfiguration.class);

    @Test
    void returnsTheFullPdfAsRawBytesWithSafeAttachmentHeaders() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            byte[] pdf = pdf();
            DownloadService service = context.getBean(DownloadService.class);
            when(service.downloadBook(1L)).thenReturn(pdf);

            byte[] body = mvc.perform(get("/api/download/book-download").queryParam("bookId", "1"))
                    .andExpect(handler().handlerType(DownloadControllerImpl.class))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"book-1.pdf\""))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, pdf.length))
                    .andExpect(content().bytes(pdf))
                    .andReturn().getResponse().getContentAsByteArray();

            try (PDDocument result = Loader.loadPDF(body)) {
                assertThat(result.getNumberOfPages()).isEqualTo(1);
            }
            verify(service).downloadBook(1L);
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void preservesRepeatedTopicIdsInTheirRequestOrderAndReturnsOneBinaryBody() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            byte[] pdf = pdf();
            DownloadService service = context.getBean(DownloadService.class);
            when(service.downloadTopics(1L, List.of(102L, 100L, 101L))).thenReturn(pdf);

            mvc.perform(get("/api/download/book-topic-download")
                            .queryParam("bookId", "1")
                            .queryParam("topicIds", "102", "100", "101"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"book-1-selected-topics.pdf\""))
                    .andExpect(content().bytes(pdf));

            verify(service).downloadTopics(1L, List.of(102L, 100L, 101L));
            verifyNoMoreInteractions(service);
        });
    }

    @Test
    void acceptsOneTopicThroughTheSameSelectedEndpoint() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            byte[] pdf = pdf();
            DownloadService service = context.getBean(DownloadService.class);
            when(service.downloadTopics(1L, List.of(100L))).thenReturn(pdf);

            mvc.perform(get("/api/download/book-topic-download")
                            .queryParam("bookId", "1").queryParam("topicIds", "100"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(pdf));
            verify(service).downloadTopics(1L, List.of(100L));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/download/book-download",
            "/api/download/book-topic-download?bookId=1",
            "/api/download/book-topic-download?topicIds=100"
    })
    void rejectsMissingRequiredParametersBeforeCallingTheService(String url) {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get(url)).andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(DownloadService.class));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/download/book-download?bookId=not-a-number",
            "/api/download/book-topic-download?bookId=1&topicIds=invalid"
    })
    void rejectsInvalidNumericParameterBindings(String url) {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get(url)).andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(DownloadService.class));
        });
    }

    @Test
    void preservesExistingNotFoundAndBadRequestServiceErrors() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            DownloadService service = context.getBean(DownloadService.class);
            when(service.downloadBook(99L)).thenThrow(
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found"));
            when(service.downloadTopics(1L, List.of(100L, 100L))).thenThrow(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate topic IDs"));

            mvc.perform(get("/api/download/book-download").queryParam("bookId", "99"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/download/book-topic-download").queryParam("bookId", "1")
                            .queryParam("topicIds", "100", "100"))
                    .andExpect(status().isBadRequest());
        });
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(DownloadControllerImpl.class)
    static class DownloadWebConfiguration {
        @Bean
        DownloadService downloadService() {
            return mock(DownloadService.class);
        }
    }
}
