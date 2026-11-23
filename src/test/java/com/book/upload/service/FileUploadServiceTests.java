package com.book.upload.service;

import java.util.List;
import com.book.dto.BookExtractionResult;
import com.book.dto.UploadFileDtlsDto;
import com.book.entity.BookDetails;
import com.book.extraction.BookMetadataExtractionService;
import com.book.offset.BookPageOffsetService;
import com.book.offset.BookPageOffsetResult;
import com.book.offset.BookPageOffsetResult.UpdateOutcome;
import com.book.offset.BookPageOffsetResult.UpdateStatus;
import com.book.upload.topic.TopicPdfUploadService;
import com.book.upload.service.impl.FileUploadServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FileUploadServiceTests {
    @Test
    void passesTheSuccessfulSaveResponseAndSameUploadedPdfToThePostSaveFunction() {
        var extraction = mock(BookMetadataExtractionService.class);
        var offsets = mock(BookPageOffsetService.class);
        var topicPdfs = mock(TopicPdfUploadService.class);
        var uploadStatuses = mock(BookUploadStatusService.class);
        var service = new FileUploadServiceImpl(extraction, offsets, topicPdfs, uploadStatuses, mock(WholeBookFallbackService.class));
        var upload = new UploadFileDtlsDto();
        byte[] bytes = {1, 2, 3};
        upload.setFileData(bytes);
        upload.setFileName("book.pdf");
        var saved = new BookExtractionResult(null, new BookDetails(), List.of());
        var pageOffset = new BookPageOffsetResult(7L, 12L, "Opening", 20, null,
                new UpdateOutcome(UpdateStatus.updated, 1, 13L, "created", List.of()));
        var completed = new BookExtractionResult(null, saved.bookDetails(), List.of(), pageOffset);
        when(extraction.extract(bytes, "book.pdf")).thenReturn(saved);
        when(offsets.populatePdfPages(saved, bytes)).thenReturn(completed);
        when(topicPdfs.uploadTopic(any())).thenReturn(completed);

        assertThat(service.uploadFile(upload)).isSameAs(completed);
        var order = inOrder(extraction, offsets, topicPdfs);
        order.verify(extraction).extract(bytes, "book.pdf");
        order.verify(offsets).populatePdfPages(saved, bytes);
        var input = ArgumentCaptor.forClass(BookExtractionResult.class);
        order.verify(topicPdfs).uploadTopic(input.capture());
        assertThat(input.getValue().bookDetails()).isSameAs(saved.bookDetails());
        assertThat(input.getValue().pageOffset()).isSameAs(pageOffset);
        assertThat(input.getValue().uploadedFile()).isSameAs(upload);
        verifyNoInteractions(uploadStatuses);
    }

    @Test
    void mappingFailurePropagatesBeforeAnyTopicFilesAreDispatched() {
        var extraction = mock(BookMetadataExtractionService.class);
        var offsets = mock(BookPageOffsetService.class);
        var topicPdfs = mock(TopicPdfUploadService.class);
        var uploadStatuses = mock(BookUploadStatusService.class);
        var service = new FileUploadServiceImpl(extraction, offsets, topicPdfs, uploadStatuses, mock(WholeBookFallbackService.class));
        var upload = new UploadFileDtlsDto();
        byte[] bytes = {1};
        upload.setFileData(bytes);
        var saved = new BookExtractionResult(null, new BookDetails(), List.of());
        var failed = new BookExtractionResult(null, saved.bookDetails(), List.of(),
                new BookPageOffsetResult(7L, 12L, "Opening", null, null,
                        new UpdateOutcome(UpdateStatus.not_attempted, 0, null, null, List.of())));
        when(extraction.extract(bytes, null)).thenReturn(saved);
        when(offsets.populatePdfPages(saved, bytes)).thenReturn(failed);

        assertThatThrownBy(() -> service.uploadFile(upload)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(topicPdfs);
        verifyNoInteractions(uploadStatuses);
        verify(extraction, times(1)).extract(bytes, null);
    }

    @Test
    void linksTheUploadBeforeSavingAndMarksSuccessOnlyAfterAllTopicFilesSucceed() {
        var extraction = mock(BookMetadataExtractionService.class);
        var offsets = mock(BookPageOffsetService.class);
        var topicPdfs = mock(TopicPdfUploadService.class);
        var uploadStatuses = mock(BookUploadStatusService.class);
        var service = new FileUploadServiceImpl(extraction, offsets, topicPdfs, uploadStatuses, mock(WholeBookFallbackService.class));
        var upload = new UploadFileDtlsDto();
        byte[] bytes = {1, 2, 3};
        upload.setFileData(bytes);
        upload.setFileName("book.pdf");
        upload.setBookUploadId(44L);
        var book = new BookDetails();
        book.setBookName("Tracked Book");
        var saved = new BookExtractionResult(null, book, List.of());
        var pageOffset = new BookPageOffsetResult(7L, 12L, "Opening", 20, null,
                new UpdateOutcome(UpdateStatus.updated, 1, 13L, "created", List.of()));
        var mapped = new BookExtractionResult(null, book, List.of(), pageOffset);
        when(extraction.extract(bytes, "book.pdf", 44L)).thenReturn(saved);
        when(offsets.populatePdfPages(saved, bytes)).thenReturn(mapped);
        when(topicPdfs.uploadTopic(any())).thenReturn(mapped);

        assertThat(service.uploadFile(upload)).isSameAs(mapped);

        var order = inOrder(extraction, offsets, topicPdfs, uploadStatuses);
        order.verify(extraction).extract(bytes, "book.pdf", 44L);
        order.verify(offsets).populatePdfPages(saved, bytes);
        order.verify(topicPdfs).uploadTopic(any());
        order.verify(uploadStatuses).succeed(44L, "Tracked Book");
        verify(extraction, never()).extract(bytes, "book.pdf");
    }

    @Test
    void topicFileFailureCannotMarkTheUploadSuccessful() {
        var extraction = mock(BookMetadataExtractionService.class);
        var offsets = mock(BookPageOffsetService.class);
        var topicPdfs = mock(TopicPdfUploadService.class);
        var uploadStatuses = mock(BookUploadStatusService.class);
        var service = new FileUploadServiceImpl(extraction, offsets, topicPdfs, uploadStatuses, mock(WholeBookFallbackService.class));
        var upload = new UploadFileDtlsDto();
        byte[] bytes = {1};
        upload.setFileData(bytes);
        upload.setBookUploadId(44L);
        var saved = new BookExtractionResult(null, new BookDetails(), List.of());
        var mapped = new BookExtractionResult(null, saved.bookDetails(), List.of(),
                new BookPageOffsetResult(7L, 12L, "Opening", 20, null,
                        new UpdateOutcome(UpdateStatus.updated, 1, 13L, "created", List.of())));
        when(extraction.extract(bytes, null, 44L)).thenReturn(saved);
        when(offsets.populatePdfPages(saved, bytes)).thenReturn(mapped);
        when(topicPdfs.uploadTopic(any())).thenThrow(new IllegalStateException("Disk is full"));

        assertThatThrownBy(() -> service.uploadFile(upload)).hasMessage("Disk is full");
        verifyNoInteractions(uploadStatuses);
    }

    @Test
    void internalOriginalPdfContextIsNeverSerializedIntoTheUploadResponse() {
        var upload = new UploadFileDtlsDto();
        upload.setFileName("original.pdf");
        upload.setFileData(new byte[] {1, 2, 3});
        var response = new BookExtractionResult(null, new BookDetails(), List.of()).withUploadedFile(upload);

        String json = JsonMapper.builder().build().writeValueAsString(response);

        assertThat(json).contains("\"topicDetails\":[]").doesNotContain("uploadedFile", "fileData", "original.pdf");
    }
}
