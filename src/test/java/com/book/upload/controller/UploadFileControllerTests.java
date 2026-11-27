package com.book.upload.controller;

import java.nio.charset.StandardCharsets;

import com.book.dto.UploadFileDtlsDto;
import com.book.entity.BookUploadStatus;
import com.book.upload.controller.impl.UploadFileControllerImpl;
import com.book.upload.service.BookUploadStatusService;
import com.book.upload.service.BookUploadSubmissionService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadFileControllerTests extends com.book.user.TenantAwareTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(UploadWebConfiguration.class);

    @ParameterizedTest
    @ValueSource(strings={"csv","xlsx","xls"})
    void routesSpreadsheetFormatsThroughTheExistingMultipartEntryPoint(String extension){
        contextRunner.run(context->{
            var service=context.getBean(com.book.spreadsheet.upload.SpreadsheetUploadService.class);
            var receipt=uploadStatus(52L,"inprogress","Background processing");
            when(service.submit(any(),any())).thenReturn(new com.book.spreadsheet.upload.SpreadsheetUploadService.UploadReceipt(receipt,71L,3,java.util.List.of()));
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(multipart("/api/upload-file").file(new MockMultipartFile("file","Report."+extension,"application/octet-stream","test content".getBytes(StandardCharsets.UTF_8)))
                    .param("tenantId","999").param("createdBy","999"))
                    .andExpect(status().isAccepted()).andExpect(header().string("Location","/api/upload-status/52"))
                    .andExpect(jsonPath("$.book_upload_id").value(52)).andExpect(jsonPath("$.fileId").value(71)).andExpect(jsonPath("$.sheetCount").value(3));
            verify(service).submit(any(),eq(com.book.spreadsheet.parse.SpreadsheetFormat.valueOf(extension.toUpperCase(java.util.Locale.ROOT))));
            verifyNoInteractions(context.getBean(BookUploadSubmissionService.class));
        });
    }

    @Test
    void acceptsUploadAndReturnsOnlyItsSavedInprogressStatusAndPollingLocation() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BookUploadSubmissionService submissions = context.getBean(BookUploadSubmissionService.class);
            String message = "book.pdf upload service is uploading book topic by topic. please check final upload status after some time.";
            when(submissions.submit(any())).thenReturn(uploadStatus(41L, "inprogress", message));
            byte[] source = "%PDF-1.7\nThe complete original PDF bytes".getBytes(StandardCharsets.UTF_8);

            mvc.perform(multipart("/api/upload-file").file(
                    new MockMultipartFile("file", "book.pdf", "application/pdf", source)))
                    .andExpect(handler().handlerType(UploadFileControllerImpl.class))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", "/api/upload-status/41"))
                    .andExpect(jsonPath("$.book_upload_id").value(41))
                    .andExpect(jsonPath("$.status").value("inprogress"))
                    .andExpect(jsonPath("$.message").value(message))
                    .andExpect(jsonPath("$.extraction").doesNotExist())
                    .andExpect(jsonPath("$.fileUploadDateTime").doesNotExist());

            ArgumentCaptor<UploadFileDtlsDto> captured = ArgumentCaptor.forClass(UploadFileDtlsDto.class);
            verify(submissions).submit(captured.capture());
            UploadFileDtlsDto detachedUpload = captured.getValue();
            assertThat(detachedUpload.getFileName()).isEqualTo("book.pdf");
            assertThat(detachedUpload.getFileData()).containsExactly(source);
            assertThat(detachedUpload.getContentType()).isEqualTo("application/pdf");
            assertThat(detachedUpload.getFileSize()).isEqualTo((long) source.length);
            assertThat(detachedUpload.getInputStream()).isNull();
            assertThat(detachedUpload.getResource()).isNull();
            verifyNoInteractions(context.getBean(BookUploadStatusService.class));
        });
    }

    @Test
    void contentDetectionAcceptsPdfWithUnhelpfulBrowserMimeAndRejectsNonPdfDisguisedAsPdf() {
        contextRunner.run(context->{
            MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).build();
            var submissions=context.getBean(BookUploadSubmissionService.class);
            when(submissions.submit(any())).thenReturn(uploadStatus(41L,"inprogress","Queued"));
            mvc.perform(multipart("/api/upload-file").file(new MockMultipartFile("file","document.bin","application/octet-stream","%PDF-1.7\nfixture".getBytes(StandardCharsets.UTF_8))))
                    .andExpect(status().isAccepted());
            ArgumentCaptor<UploadFileDtlsDto> captured=ArgumentCaptor.forClass(UploadFileDtlsDto.class);
            verify(submissions).submit(captured.capture());assertThat(captured.getValue().getContentType()).isEqualTo("application/pdf");
            clearInvocations(submissions);
            mvc.perform(multipart("/api/upload-file").file(new MockMultipartFile("file","disguised.pdf","application/pdf","PK unsupported archive".getBytes(StandardCharsets.UTF_8))))
                    .andExpect(status().isUnsupportedMediaType());
            verifyNoInteractions(submissions);
        });
    }
    @Test
    void historyUsesTheAuthenticatedScopeAndValidatesPaginationThroughItsService() {
        contextRunner.run(context->{
            MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).build();
            var statuses=context.getBean(BookUploadStatusService.class);
            when(statuses.history(0,20)).thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(uploadStatus(7L,"Success","Done"))));
            mvc.perform(get("/api/my-uploads").param("tenantId","999").param("userId","999"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].book_upload_id").value(7));
            verify(statuses).history(0,20);
        });
    }
    @Test
    void rejectedBackgroundSubmissionReturnsItsRecordedFailedStatus() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BookUploadSubmissionService submissions = context.getBean(BookUploadSubmissionService.class);
            when(submissions.submit(any())).thenReturn(uploadStatus(42L, "failed", "Upload queue is full"));

            mvc.perform(multipart("/api/upload-file").file(
                    new MockMultipartFile("file", "book.pdf", "application/pdf", "%PDF-1.7\n".getBytes(StandardCharsets.UTF_8))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Location", "/api/upload-status/42"))
                    .andExpect(jsonPath("$.book_upload_id").value(42))
                    .andExpect(jsonPath("$.status").value("failed"))
                    .andExpect(jsonPath("$.message").value("Upload queue is full"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"inprogress", "failed", "Success"})
    void pollingReturnsTheCurrentSavedStatus(String currentStatus) {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BookUploadStatusService statuses = context.getBean(BookUploadStatusService.class);
            when(statuses.get(41L)).thenReturn(uploadStatus(41L, currentStatus, "Latest upload message"));

            mvc.perform(get("/api/upload-status/41"))
                    .andExpect(handler().handlerType(UploadFileControllerImpl.class))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.book_upload_id").value(41))
                    .andExpect(jsonPath("$.status").value(currentStatus))
                    .andExpect(jsonPath("$.message").value("Latest upload message"));
            verifyNoInteractions(context.getBean(BookUploadSubmissionService.class));
        });
    }

    @Test
    void pollingAnUnknownUploadReturnsNotFound() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            when(context.getBean(BookUploadStatusService.class).get(999L))
                    .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown upload"));
            mvc.perform(get("/api/upload-status/999")).andExpect(status().isNotFound());
        });
    }

    @Test
    void pollingAnInvalidUploadIdIsRejectedBeforeLookingItUp() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/api/upload-status/0")).andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(BookUploadStatusService.class));
        });
    }

    @Test
    void rejectsUploadWithoutFilePartBeforeSubmission() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(multipart("/api/upload-file"))
                    .andExpect(handler().handlerType(UploadFileControllerImpl.class))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(BookUploadSubmissionService.class));
        });
    }

    @Test
    void rejectsEmptyFileBeforeCreatingAnUploadStatus() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(multipart("/api/upload-file").file(
                    new MockMultipartFile("file", "book.pdf", "application/pdf", new byte[0])))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(BookUploadSubmissionService.class));
        });
    }

    @Test
    void rejectsBlankOriginalFileNameBeforeCreatingAnUploadStatus() {
        contextRunner.run(context -> {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(multipart("/api/upload-file").file(
                    new MockMultipartFile("file", "  ", "application/pdf", new byte[] {1})))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(context.getBean(BookUploadSubmissionService.class));
        });
    }

    private static BookUploadStatus uploadStatus(Long id, String status, String message) {
        BookUploadStatus entity = new BookUploadStatus();
        entity.setBookUploadId(id);
        entity.setStatus(status);
        entity.setMessage(message);
        return entity;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @ComponentScan(basePackageClasses = UploadFileControllerImpl.class)
    static class UploadWebConfiguration {
        @Bean BookUploadSubmissionService bookUploadSubmissionService() { return mock(BookUploadSubmissionService.class); }
        @Bean BookUploadStatusService bookUploadStatusService() { return mock(BookUploadStatusService.class); }
        @Bean com.book.spreadsheet.upload.SpreadsheetUploadService spreadsheetUploadService(){return mock(com.book.spreadsheet.upload.SpreadsheetUploadService.class);}
    }
}
