package com.book.repository;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;

import com.book.dto.BookSearchCriteria;
import com.book.dto.BookSearchResponse.BookResult;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.extraction.BookMetadataExtractionService;
import com.book.extraction.BookPageAnalyzer;
import com.book.search.repository.BookSearchQueryRepository;
import com.book.search.service.impl.SearchServiceImpl;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "debug=false", "spring.jpa.show-sql=false"})
class BookTypePersistenceTests extends com.book.user.TenantAwareTest {
    @Autowired BookDetailsRepository books;
    @Autowired EntityManager entityManager;

    /** Optional live smoke test: real local inference, isolated H2 and transaction rollback. */
    @Test
    @EnabledIfSystemProperty(named = "book.ai.live", matches = "true")
    @Timeout(180)
    void liveLocalModelClassifiesInitialPagesAndPersistsMetadataWithTechnicalOffset() throws Exception {
        String modelName = System.getProperty("book.ai.model", "qwen2.5-coder:7b");
        JdkClientHttpRequestFactory http = new JdkClientHttpRequestFactory();
        http.setReadTimeout(Duration.ofSeconds(150));
        OllamaApi api = OllamaApi.builder().baseUrl(System.getProperty("book.ai.url", "http://localhost:11434"))
                .restClientBuilder(RestClient.builder().requestFactory(http)).build();
        OllamaChatModel model = OllamaChatModel.builder().ollamaApi(api)
                .options(OllamaChatOptions.builder().model(modelName).build()).build();
        ChatClient client = ChatClient.builder(model).build();
        JsonMapper mapper = JsonMapper.builder().build();
        BookPageAnalyzer analyzer = new BookPageAnalyzer(client, mapper,
                new ClassPathResource("prompts/book-extraction-system.st"), 16384, 1024, 2, modelName);
        var result = new BookMetadataExtractionService(analyzer, books, mapper)
                .extract(pdf(), "live-python-proof.pdf");
        Long id = result.bookDetails().getBookId();
        entityManager.flush();
        entityManager.clear();
        BookDetails persisted = books.findById(id).orElseThrow();
        // Classification is evidence-based and free-form; a broader Programming label is valid.
        assertThat(persisted.getBookType()).containsIgnoringCase("Programming");
        assertThat(persisted.getBookDetails()).isNotBlank();
        assertOnlyTechnicalOffset(persisted);
        var response = new SearchServiceImpl(new BookSearchQueryRepository(entityManager)).searchBooks(
                new BookSearchCriteria(null, null, null, null, null, null, null, null));
        assertThat(response.books()).anySatisfy(book -> {
            assertThat(book.bookId()).isEqualTo(id);
            assertThat(book.bookType()).isEqualTo(persisted.getBookType());
            assertThat(book.bookDetails()).isEqualTo(persisted.getBookDetails());
            assertOnlyTechnicalOffset(book);
        });
    }

    @Test
    void singleMetadataResponsePersistsTypeAndDescriptionWithoutSemanticChapters() throws Exception {
        BookPageAnalyzer analyzer = mock(BookPageAnalyzer.class);
        when(analyzer.analyze(any(), any())).thenReturn("""
                {"authorDetails":{"authorName":"A Writer"},
                 "bookDetails":{"bookName":"Python Guide",
                   "bookDetails":"An introduction to Python programming fundamentals.","bookType":"Python Programming"},
                 "topicDetails":[],"indexFound":false,"firstTopicStartPage":null,
                 "requiredNextPageSet":false,"contentsComplete":false}
                """);
        JsonMapper mapper = JsonMapper.builder().build();
        BookMetadataExtractionService extraction = new BookMetadataExtractionService(analyzer, books, mapper);

        var result = extraction.extract(pdf(), "python-guide.pdf");
        Long id = result.bookDetails().getBookId();
        entityManager.flush();
        entityManager.clear();

        BookDetails stored = books.findById(id).orElseThrow();
        assertThat(stored.getBookType()).isEqualTo("Python Programming");
        assertThat(stored.getBookDetails()).isEqualTo("An introduction to Python programming fundamentals.");
        assertOnlyTechnicalOffset(stored);
        var response = new SearchServiceImpl(new BookSearchQueryRepository(entityManager)).searchBooks(
                new BookSearchCriteria(null, null, null, null, null, null, null, null));
        var book = response.books().stream().filter(candidate -> id.equals(candidate.bookId())).findFirst().orElseThrow();
        assertThat(book.bookType()).isEqualTo(stored.getBookType());
        assertThat(book.bookDetails()).isEqualTo(stored.getBookDetails());
        assertOnlyTechnicalOffset(book);
        assertThat(mapper.writeValueAsString(book)).contains("\"bookType\":\"Python Programming\"",
                "\"bookDetails\":\"An introduction to Python programming fundamentals.\"");
        verify(analyzer, times(1)).analyze(any(), any());
        verifyNoMoreInteractions(analyzer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Physics", "Chemistry", "Math", "Poetry", "Story", "Computer Programming",
            "Interdisciplinary Science & Art"})
    void freeformBookTypesRoundTripThroughDatabaseAndSearch(String type) {
        BookDetails book = book("Stored " + type);
        book.setBookType("  " + type + "  ");
        Long id = books.saveAndFlush(book).getBookId();
        entityManager.clear();

        BookDetails found = books.findById(id).orElseThrow();
        assertThat(found.getBookType()).isEqualTo(type);
        var response = new SearchServiceImpl(new BookSearchQueryRepository(entityManager)).searchBooks(
                new BookSearchCriteria(found.getBookName(), null, null, null, null, null, null, null));
        assertThat(response.books()).singleElement().satisfies(result -> {
            assertThat(result.bookId()).isEqualTo(id);
            assertThat(result.bookType()).isEqualTo(type);
        });
    }

    @Test
    void missingBlankOrUnsupportedLengthTypesPersistAsUnknown() {
        String[] missingTypes = {null, "", "  ", "unknown", "x".repeat(121)};
        for (int index = 0; index < missingTypes.length; index++) {
            BookDetails book = book("Unknown type " + index);
            book.setBookType(missingTypes[index]);
            Long id = books.saveAndFlush(book).getBookId();
            entityManager.clear();
            assertThat(books.findById(id).orElseThrow().getBookType()).isEqualTo("Unknown");
        }
    }

    private static BookDetails book(String name) {
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Writer");
        BookDetails book = new BookDetails();
        book.setExtractionId(UUID.randomUUID().toString());
        book.setBookName(name);
        book.setAuthorDetails(author);
        book.setTotalPages(1);
        return book;
    }

    private static void assertOnlyTechnicalOffset(BookDetails book) {
        assertThat(book.getTopics()).singleElement().satisfies(topic -> {
            assertThat(topic.getTopicName()).isEqualTo(book.getBookName() + "_offset");
            assertThat(topic.getDetails()).isEqualTo(book.getBookName() + "_offset detail");
            assertThat(topic.getStartPage()).isEqualTo(1);
            assertThat(topic.getEndPage()).isEqualTo(book.getTotalPages());
            assertThat(topic.getPrintedStartPage()).isNull();
            assertThat(topic.getPrintedEndPage()).isNull();
        });
    }

    private static void assertOnlyTechnicalOffset(BookResult book) {
        assertThat(book.topicCount()).isEqualTo(1);
        assertThat(book.topics()).singleElement().satisfies(topic -> {
            assertThat(topic.topicName()).isEqualTo(book.bookName() + "_offset");
            assertThat(topic.topicDetails()).isEqualTo(book.bookName() + "_offset detail");
            assertThat(topic.startPage()).isEqualTo(1);
            assertThat(topic.endPage()).isEqualTo(book.totalPages());
            assertThat(topic.printedStartPage()).isNull();
            assertThat(topic.printedEndPage()).isNull();
        });
    }

    private byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Introduction. This guide explains Python programming fundamentals.");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
