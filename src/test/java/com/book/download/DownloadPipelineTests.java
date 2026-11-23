package com.book.download;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

import com.book.download.config.DownloadTemplateConfiguration;
import com.book.download.controller.impl.DownloadControllerImpl;
import com.book.download.pdf.BookPdfMerger;
import com.book.download.pdf.FrontMatterRenderer;
import com.book.download.pdf.TopicPdfSource;
import com.book.download.service.impl.DownloadServiceImpl;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DownloadPipelineTests {
    @TempDir Path storage;

    @Test
    void selectedHttpDownloadRendersRealFrontMatterAndMergesOriginalPagesInRequestOrder() throws Exception {
        BookDetails book = new BookDetails();
        book.setBookId(1L);
        book.setBookName("Algorithmes et structures de données pour construire des systèmes fiables — "
                + "Θεωρία αλγορίθμων και εφαρμογές — Алгоритмы и структуры данных");
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Example Author");
        book.setAuthorDetails(author);

        TopicDetails searching = topic(book, 100L, "Searching", "ORIGINAL SEARCHING PAGE");
        TopicDetails sorting = topic(book, 101L, "Sorting", "ORIGINAL SORTING PAGE");
        TopicDetails graphs = topic(book, 102L, "Graphs", "ORIGINAL GRAPHS PAGE");
        BookDetailsRepository books = mock(BookDetailsRepository.class);
        TopicDetailsRepository topics = mock(TopicDetailsRepository.class);
        List<Long> requested = List.of(102L, 100L, 101L);
        when(books.findById(1L)).thenReturn(Optional.of(book));
        // Deliberately return a different order from the request, as an SQL IN lookup may do.
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, requested))
                .thenReturn(List.of(searching, sorting, graphs));

        FrontMatterRenderer renderer = new FrontMatterRenderer(
                new DownloadTemplateConfiguration().downloadTemplateEngine());
        DownloadServiceImpl service = new DownloadServiceImpl(books, topics,
                new TopicPdfSource(storage.toString()), renderer, new BookPdfMerger());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DownloadControllerImpl(service)).build();

        byte[] response = mvc.perform(get("/api/download/book-topic-download")
                        .queryParam("bookId", "1").queryParam("topicIds", "102", "100", "101"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"book-1-selected-topics.pdf\""))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(response).startsWith(new byte[]{'%', 'P', 'D', 'F', '-'});
        Path preview = Files.createDirectories(Path.of("target", "download-preview"));
        Files.write(preview.resolve("selected-topics.pdf"), response);
        try (PDDocument downloaded = Loader.loadPDF(response)) {
            assertThat(downloaded.getNumberOfPages()).isEqualTo(2 + 3);
            assertThat(pageText(downloaded, 1)).contains("Algorithmes", "données", "Θεωρία", "Алгоритмы", "Example Author")
                    .doesNotContain("Contents", "ORIGINAL");
            assertThat(pageText(downloaded, 2).lines().map(String::strip).filter(line -> !line.isEmpty()).toList())
                    .containsExactly("Contents", "Graphs", "Searching", "Sorting");
            assertThat(pageText(downloaded, 3).strip()).isEqualTo("ORIGINAL GRAPHS PAGE");
            assertThat(pageText(downloaded, 4).strip()).isEqualTo("ORIGINAL SEARCHING PAGE");
            assertThat(pageText(downloaded, 5).strip()).isEqualTo("ORIGINAL SORTING PAGE");
            PDFRenderer pageRenderer = new PDFRenderer(downloaded);
            for (int page = 0; page < downloaded.getNumberOfPages(); page++) {
                var image = pageRenderer.renderImageWithDPI(page, 95);
                try {
                    ImageIO.write(image, "PNG", preview.resolve("selected-topics-page-" + (page + 1) + ".png").toFile());
                } finally {
                    image.flush();
                }
            }
        }
        verify(topics).findAllByBookDetailsBookIdAndTopicIdIn(1L, requested);
        verifyNoMoreInteractions(topics);
    }

    private TopicDetails topic(BookDetails book, Long id, String name, String pageMarker) throws Exception {
        Path pdf = storage.resolve(id + ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream text = new PDPageContentStream(document, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                text.newLineAtOffset(55, 700);
                text.showText(pageMarker);
                text.endText();
            }
            document.save(pdf.toFile());
        }
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(id);
        topic.setTopicName(name);
        topic.setBookDetails(book);
        topic.setStartPage(1);
        topic.setEndPage(1);
        topic.setPdfPath(pdf.toString());
        return topic;
    }

    private static String pageText(PDDocument document, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }
}
