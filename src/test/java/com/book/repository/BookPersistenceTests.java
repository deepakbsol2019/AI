package com.book.repository;

import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import tools.jackson.databind.json.JsonMapper;

import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.dto.BookExtractionResult;
import com.book.dto.PdfPageBatch;
import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfTopicBatchExtraction;
import com.book.extraction.BookMetadataExtractionService;
import com.book.extraction.BookPageAnalyzer;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "debug=false",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=INFO"
})
class BookPersistenceTests extends com.book.user.TenantAwareTest {
    @Autowired BookDetailsRepository books;
    @Autowired EntityManager entityManager;

    @Test
    void savesCompletedPrintedRangesAfterSubtopicsContinueInAnotherBatch() throws Exception {
        BookPageAnalyzer analyzer = mock(BookPageAnalyzer.class);
        JsonMapper mapper = JsonMapper.builder().build();
        String first = mapper.writeValueAsString(new PdfBatchExtraction(
                new PdfBatchExtraction.AuthorData("Writer"), new PdfBatchExtraction.BookData("Book", "Description"),
                List.of(new PdfBatchExtraction.TopicData("Variables", "Declaring Variables", 90, 95,
                        17, 20, "2", false)), true, null, true, false));
        String second = mapper.writeValueAsString(new PdfTopicBatchExtraction(List.of(
                new PdfBatchExtraction.TopicData("Variables", "Arithmetic Operators", 91, 96,
                        21, 31, "2", true),
                new PdfBatchExtraction.TopicData("Input and Output", "Reading Input", 97, 99,
                        33, 50, "3", false)), true, null, false, true));
        when(analyzer.analyze(any(), any())).thenReturn(first, second);
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 10; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 600);
                    content.showText("Fixture page " + (i + 1));
                    content.endText();
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        BookMetadataExtractionService service = new BookMetadataExtractionService(analyzer, books, mapper);

        var result = service.extract(pdf, "book.pdf");
        entityManager.flush();
        entityManager.clear();

        List<TopicDetails> saved = books.findById(result.bookDetails().getBookId()).orElseThrow().getTopics();
        assertThat(saved).hasSize(2);
        assertThat(saved).extracting(TopicDetails::getPrintedStartPage).containsExactly(17, 33);
        assertThat(saved).extracting(TopicDetails::getPrintedEndPage).containsExactly(32, null);
        assertThat(saved.getFirst().getDetails()).isEqualTo("Declaring Variables, Arithmetic Operators");
        assertThat(saved).allSatisfy(topic -> {
            assertThat(topic.getStartPage()).isNull();
            assertThat(topic.getEndPage()).isNull();
            assertThat(topic.getPdfPath()).isNull();
        });
        verify(analyzer, times(2)).analyze(any(), any());
    }

    @Test
    @EnabledIfSystemProperty(named = "book.contents.fixture", matches = ".+")
    void uploadedThinkJavaPdfStopsAfterContentsAndSavesDespiteStaleModelFlags() throws Exception {
        BookPageAnalyzer analyzer = mock(BookPageAnalyzer.class);
        when(analyzer.analyze(any(), any())).thenReturn("""
                {"authorDetails":{"authorName":"Fixture Writer"},
                 "bookDetails":{"bookName":"Fixture Book","bookDetails":"Fixture description"},
                 "topicDetails":[{"topicName":"Computer Programming","details":"Basics",
                     "startPage":null,"endPage":null,"printedStartPage":null,"printedEndPage":null}],
                 "indexFound":true,"firstTopicStartPage":null,
                 "contentsComplete":false,"requiredNextPageSet":true}
                """);
        BookMetadataExtractionService service = new BookMetadataExtractionService(analyzer, books,
                JsonMapper.builder().build());
        byte[] pdf = Files.readAllBytes(Path.of(System.getProperty("book.contents.fixture")));
        int prefacePage = 0;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper text = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                text.setStartPage(page);
                text.setEndPage(page);
                if (text.getText(document).lines().anyMatch(line -> line.trim().equalsIgnoreCase("Preface"))) {
                    prefacePage = page;
                    break;
                }
            }
        }
        assertThat(prefacePage).isPositive();

        var result = service.extract(pdf, "fixture.pdf");
        entityManager.flush();
        entityManager.clear();

        List<TopicDetails> savedTopics = books.findById(result.bookDetails().getBookId()).orElseThrow().getTopics();
        assertThat(savedTopics.size()).isGreaterThanOrEqualTo(20);
        assertThat(savedTopics.get(0).getPrintedStartPage()).isEqualTo(1);
        assertThat(savedTopics.get(0).getPrintedEndPage()).isEqualTo(16);
        assertThat(savedTopics.get(1).getPrintedStartPage()).isEqualTo(17);
        assertThat(savedTopics.get(1).getPrintedEndPage()).isEqualTo(32);
        assertThat(savedTopics.get(2).getPrintedStartPage()).isEqualTo(33);
        assertThat(savedTopics.get(2).getPrintedEndPage()).isEqualTo(50);
        assertThat(savedTopics).allSatisfy(topic -> {
            assertThat(topic.getPrintedStartPage()).isNotNull();
            assertThat(topic.getPrintedEndPage()).isNotNull();
            assertThat(topic.getPrintedEndPage()).isGreaterThanOrEqualTo(topic.getPrintedStartPage());
            assertThat(topic.getStartPage()).isNull();
            assertThat(topic.getEndPage()).isNull();
        });
        assertThat(savedTopics.getLast().getTopicName()).isEqualToIgnoringCase("Index");
        assertThat(savedTopics.getLast().getPrintedEndPage()).isEqualTo(349);
        ArgumentCaptor<PdfPageBatch> pages = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer, times(3)).analyze(pages.capture(), any());
        assertThat(pages.getAllValues()).extracting(PdfPageBatch::startPage).containsExactly(1, 6, 11);
        assertThat(pages.getAllValues()).extracting(PdfPageBatch::endPage).containsExactly(5, 10, prefacePage - 1);
        assertThat(pages.getValue().text()).doesNotContain("\nPreface\n");
        verifyNoMoreInteractions(analyzer);
        System.out.println("Uploaded PDF check: model ranges 1-5, 6-10, 11-" + (prefacePage - 1)
                + "; stopped before Preface at PDF page " + prefacePage + "; "
                + savedTopics.size() + " topics saved with source-backed printed ranges; final printed page=349.");
        for (TopicDetails topic : savedTopics) {
            System.out.println("PRINTED_RANGE " + topic.getTopicName() + ": "
                    + topic.getPrintedStartPage() + "-" + topic.getPrintedEndPage());
        }
    }

    @Test
    void realPdfHeadingChangeSavesRowsEvenWhenModelAlwaysAsksForAnotherBatch() throws Exception {
        BookPageAnalyzer analyzer = mock(BookPageAnalyzer.class);
        when(analyzer.analyze(any(), any())).thenReturn("""
                {"authorDetails":{"authorName":"Saved Writer"},
                 "bookDetails":{"bookName":"Saved Book","bookDetails":"Saved description"},
                 "topicDetails":[{"topicName":"Opening","details":"Basics, Setup",
                     "startPage":null,"endPage":null,"printedStartPage":1,"printedEndPage":16}],
                 "indexFound":true,"firstTopicStartPage":null,
                 "contentsComplete":false,"requiredNextPageSet":true}
                """);
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int pageNumber = 1; pageNumber <= 10; pageNumber++) {
                PDPage page = new PDPage();
                document.addPage(page);
                String text = pageNumber == 5 ? "Contents\n1 Opening ....... 1\n1.1 Basics ....... 1"
                        : pageNumber == 6 ? "Preface\nDO_NOT_SEND_BODY" : "Page " + pageNumber;
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 600);
                    for (String line : text.split("\\R")) {
                        content.showText(line);
                        content.newLineAtOffset(0, -16);
                    }
                    content.endText();
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        BookMetadataExtractionService service = new BookMetadataExtractionService(analyzer, books,
                JsonMapper.builder().build());

        var result = service.extract(pdf, "book.pdf");
        entityManager.flush();
        entityManager.clear();

        BookDetails saved = books.findById(result.bookDetails().getBookId()).orElseThrow();
        assertThat(saved.getBookName()).isEqualTo("Saved Book");
        assertThat(saved.getAuthorDetails().getAuthorName()).isEqualTo("Saved Writer");
        assertThat(saved.getTopics()).hasSize(1);
        assertThat(saved.getTopics().getFirst().getDetails()).isEqualTo("Basics, Setup");
        assertThat(saved.getTopics().getFirst().getPrintedStartPage()).isEqualTo(1);
        assertThat(saved.getTopics().getFirst().getPdfPath()).isNull();
        ArgumentCaptor<PdfPageBatch> sent = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer).analyze(sent.capture(), any());
        assertThat(sent.getValue().endPage()).isEqualTo(5);
        assertThat(sent.getValue().text()).doesNotContain("Preface", "DO_NOT_SEND_BODY");
        verifyNoMoreInteractions(analyzer);
    }

    @Test
    void persistsAndSerializesCompletedAuthorBookAndTopicsWithoutCycles() {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Author");
        BookDetails book = new BookDetails();
        book.setExtractionId(UUID.randomUUID().toString());
        book.setBookName("Book");
        book.setBookDetails("Description");
        book.setTotalPages(10);
        book.setAuthorDetails(author);
        TopicDetails topic = new TopicDetails();
        topic.setTopicName("Opening");
        topic.setDetails("One, Two");
        topic.setStartPage(4);
        topic.setEndPage(10);
        book.addTopic(topic);

        books.saveAndFlush(book);
        assertThat(author.getAuthorId()).isNotNull();
        assertThat(topic.getTopicId()).isNotNull();
        assertThat(book.getCreatedAt()).isNotNull();
        assertThat(book.getUpdatedAt()).isNotNull();
        entityManager.clear();

        BookDetails loaded = books.findById(book.getBookId()).orElseThrow();
        assertThat(loaded.getAuthorDetails().getAuthorName()).isEqualTo("Author");
        assertThat(loaded.getTopics()).hasSize(1);
        assertThat(loaded.getTopics().getFirst().getBookDetails()).isSameAs(loaded);
        String json = JsonMapper.builder().build().writeValueAsString(
                new BookExtractionResult(loaded.getAuthorDetails(), loaded, List.copyOf(loaded.getTopics())));
        assertThat(json).contains("\"authorName\":\"Author\"", "\"bookName\":\"Book\"",
                "\"startPage\":4", "\"endPage\":10");
    }

    @Test
    void savesPrintedPageReferencesWithoutInventingUnknownPhysicalPages() {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Author");
        BookDetails book = new BookDetails();
        book.setExtractionId(UUID.randomUUID().toString());
        book.setBookName("Book");
        book.setTotalPages(100);
        book.setAuthorDetails(author);
        TopicDetails topic = new TopicDetails();
        topic.setTopicName("Opening");
        topic.setDetails("Basics, Setup");
        topic.setPrintedStartPage(1);
        topic.setPrintedEndPage(16);
        book.addTopic(topic);

        books.saveAndFlush(book);
        entityManager.clear();

        TopicDetails loaded = books.findById(book.getBookId()).orElseThrow().getTopics().getFirst();
        assertThat(loaded.getStartPage()).isNull();
        assertThat(loaded.getEndPage()).isNull();
        assertThat(loaded.getPrintedStartPage()).isEqualTo(1);
        assertThat(loaded.getPrintedEndPage()).isEqualTo(16);
        assertThat(loaded.getPdfPath()).isNull();
        assertThat(loaded.getDetails()).isEqualTo("Basics, Setup");
    }

}
