package com.book.extraction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.book.dto.BookExtractionResult;
import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfBatchExtraction.AuthorData;
import com.book.dto.PdfBatchExtraction.BookData;
import com.book.dto.PdfBatchExtraction.TopicData;
import com.book.dto.PdfPageBatch;
import com.book.dto.PdfTopicBatchExtraction;
import com.book.repository.BookDetailsRepository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookMetadataExtractionServiceTests {
    private final BookPageAnalyzer analyzer = mock(BookPageAnalyzer.class);
    private final BookDetailsRepository books = mock(BookDetailsRepository.class);
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private BookMetadataExtractionService service;

    @BeforeEach
    void setUp() {
        service = new BookMetadataExtractionService(analyzer, books, mapper);
    }

    @Test
    void mergesContentsStartingOnPageFiveAndSavesOnlyAfterAllBatchesComplete() throws Exception {
        String first = json(new PdfBatchExtraction(new AuthorData("Ada Author"),
                new BookData("Example Book", "A book."), List.of(printed("Introduction", "Basics", 1)),
                true, null, true, false));
        String second = json(new PdfTopicBatchExtraction(
                List.of(printed(" introduction ", "Setup", 1), printed("Advanced", "Tools", 4)),
                true, null, true, false));
        String third = json(new PdfTopicBatchExtraction(
                List.of(new TopicData("Advanced", "Examples", null, null, null, null),
                        printed("Reference", "Glossary", 6)), true, 12, false, true));
        List<String> responses = List.of(first, second, third);
        AtomicInteger callIndex = new AtomicInteger();
        when(analyzer.analyze(any(), any())).thenAnswer(call -> {
            verifyNoInteractions(books);
            return responses.get(callIndex.getAndIncrement());
        });

        BookExtractionResult result = service.extract(pdf(20, true, Map.of(
                5, "Contents: Introduction 1; Basics",
                6, "Setup; Advanced 4",
                7, "Tools; Reference 6")), "book.pdf");

        ArgumentCaptor<PdfPageBatch> pageSets = ArgumentCaptor.forClass(PdfPageBatch.class);
        ArgumentCaptor<PdfBatchExtraction> previous = ArgumentCaptor.forClass(PdfBatchExtraction.class);
        verify(analyzer, times(3)).analyze(pageSets.capture(), previous.capture());
        assertThat(pageSets.getAllValues()).extracting(PdfPageBatch::startPage).containsExactly(1, 6, 11);
        assertThat(pageSets.getAllValues()).extracting(PdfPageBatch::endPage).containsExactly(5, 10, 15);
        assertThat(pageSets.getAllValues().get(1).text()).contains("--- PDF PAGE 6 ---", "Page 10")
                .doesNotContain("--- PDF PAGE 5 ---", "--- PDF PAGE 11 ---");
        assertThat(previous.getAllValues().get(2).topicDetails()).hasSize(2);
        assertThat(pageSets.getAllValues().getFirst().text())
                .contains("--- PDF PAGE 5 ---", "Contents: Introduction");
        assertThat(pageSets.getAllValues().get(1).text()).contains("Setup; Advanced", "Tools; Reference");
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Ada Author");
        assertThat(result.bookDetails().getBookName()).isEqualTo("Example Book");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("A book.");
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedStartPage()).containsExactly(1, 4, 6);
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedEndPage()).containsExactly(3, 5, null);
        assertThat(result.topicDetails()).allSatisfy(topic -> {
            assertThat(topic.getStartPage()).isNull();
            assertThat(topic.getEndPage()).isNull();
        });
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        assertThat(result.topicDetails().get(1).getDetails()).isEqualTo("Tools, Examples");
        assertThat(result.topicDetails()).allSatisfy(topic ->
                assertThat(topic.getBookDetails()).isSameAs(result.bookDetails()));
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
        verifyNoMoreInteractions(books);
    }

    @Test
    void missingIndexInFirstBatchUsesWholeBookFallbackAndDoesNotReadLaterPages() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Author"), new BookData("No Contents", "Description"),
                List.of(new TopicData(null, null, null, null, null, null)), false, null, true, false)));

        BookExtractionResult result = service.extract(pdf(7, true), "book.pdf");

        verify(analyzer).analyze(any(), any());
        assertWholeBook(result, 7);
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("Description");
        assertThat(result.wholeBook()).isTrue();
        assertThat(result.bookDetails().getTotalPages()).isEqualTo(7);
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
    }

    @Test
    void handlesFinalBatchSmallerThanFivePages() throws Exception {
        String keepReading = json(new PdfBatchExtraction(null, new BookData("Book", null),
                List.of(printed("Opening", null, 1)), true, null, true, false));
        String done = json(new PdfBatchExtraction(null, null, List.of(), true, 12, false, true));
        when(analyzer.analyze(any(), any())).thenReturn(keepReading, keepReading, done);

        var result = service.extract(pdf(12, true), "book.pdf");

        ArgumentCaptor<PdfPageBatch> pages = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer, times(3)).analyze(pages.capture(), any());
        assertThat(pages.getValue().startPage()).isEqualTo(11);
        assertThat(pages.getValue().endPage()).isEqualTo(12);
        assertThat(result.topicDetails().getFirst().getStartPage()).isNull();
        assertThat(result.topicDetails().getFirst().getEndPage()).isNull();
        assertThat(result.topicDetails().getFirst().getPrintedStartPage()).isEqualTo(1);
        assertThat(result.topicDetails().getFirst().getPrintedEndPage()).isNull();
    }

    @Test
    void stopsImmediatelyWhenFirstTopicIsInFirstBatch() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Book", null), List.of(printed("Opening", null, 1)), true, 4, false, true)));

        var result = service.extract(pdf(9, true), "book.pdf");

        verify(analyzer).analyze(any(), any());
        assertThat(result.topicDetails().getFirst().getStartPage()).isNull();
        assertThat(result.topicDetails().getFirst().getEndPage()).isNull();
    }

    @Test
    void uploadsTheWholeBookWhenContentsNeverBecomeComplete() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Book", null), List.of(printed("Opening", null, 1)), true, null, true, false)));

        var result = service.extract(pdf(7, true), "book.pdf");

        verify(analyzer, times(2)).analyze(any(), any());
        assertWholeBook(result, 7);
        assertThat(result.bookDetails().getBookName()).isEqualTo("Book");
        verify(books).saveAndFlush(result.bookDetails());
    }

    @Test
    void uploadsTheWholeBookWhenTheModelReturnsMalformedJson() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn("not JSON");
        var result = service.extract(pdf(3, true), "book.pdf");
        assertWholeBook(result, 3);
        assertThat(result.bookDetails().getBookName()).isEqualTo("book");
        verify(books).saveAndFlush(result.bookDetails());
    }

    @Test
    void continuesAfterPrematureStopFlagWithoutConfirmedOpeningPage() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null, null,
                List.of(printed("Opening", null, 1)), true, null, false, false)),
                json(new PdfTopicBatchExtraction(List.of(), true, 6, false, true)));

        var result = service.extract(pdf(8, true), "book.pdf");

        verify(analyzer, times(2)).analyze(any(), any());
        assertThat(result.topicDetails().getFirst().getStartPage()).isNull();
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
    }

    @Test
    void missingControlFlagsUseWholeBookFallback() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn("{}");
        var result = service.extract(pdf(1, true), "book.pdf");
        assertWholeBook(result, 1);
        verify(books).saveAndFlush(result.bookDetails());
    }

    @Test
    void rejectsInvalidPdfButUploadsScansWithoutCallingModel() throws Exception {
        assertThatThrownBy(() -> service.extract(new byte[] {1, 2, 3}, "bad.pdf"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        var result = service.extract(pdf(2, false), "scan.pdf");
        assertWholeBook(result, 2);
        assertThat(result.bookDetails().getBookName()).isEqualTo("scan");
        verifyNoInteractions(analyzer);
        verify(books).saveAndFlush(result.bookDetails());
    }

    @Test
    void preservesDistinctTopicsWithTheSameTitleOnDifferentPages() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Book", null), List.of(printed("Practice", "Basics", 1),
                        printed("Practice", "Advanced", 3)), true, 3, false, true)));

        var result = service.extract(pdf(7, true), "book.pdf");

        assertThat(result.topicDetails()).hasSize(2);
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedStartPage()).containsExactly(1, 3);
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedEndPage()).containsExactly(2, null);
    }

    @Test
    void doesNotLimitPrintedReferencesToPhysicalPdfLength() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Book", null), List.of(printed("Opening", null, 1),
                        printed("Impossible", null, 100)), true, 3, false, true)));
        var result = service.extract(pdf(5, true), "book.pdf");
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedStartPage()).containsExactly(1, 100);
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedEndPage()).containsExactly(99, null);
        assertThat(result.topicDetails()).allSatisfy(topic -> {
            assertThat(topic.getStartPage()).isNull();
            assertThat(topic.getEndPage()).isNull();
        });
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
    }

    private TopicData printed(String name, String details, int start) {
        return new TopicData(name, details, null, null, start, null);
    }

    @Test
    void invalidContinuationUsesWholeBookAndPreservesEarlierBookMetadata() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(new AuthorData("Author"),
                new BookData("Book", "Description"), List.of(printed("Opening", "Basics", 1)),
                true, null, true, false)), "invalid continuation JSON");

        var result = service.extract(pdf(10, true), "book.pdf");

        verify(analyzer, times(2)).analyze(any(), any());
        assertWholeBook(result, 10);
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Author");
        assertThat(result.bookDetails().getBookName()).isEqualTo("Book");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("Description");
        verify(books).saveAndFlush(result.bookDetails());
    }

    @Test
    void laterBatchCannotOverwriteAuthorAndBookFromFirstBatch() throws Exception {
        String first = json(new PdfBatchExtraction(new AuthorData("First Author"),
                new BookData("First Book", "First description", "Java Programming"), List.of(printed("Opening", "Basics", 1)),
                true, null, true, false));
        // Even if the model repeats metadata against instructions, later schemas discard it.
        String later = json(new PdfBatchExtraction(new AuthorData("Wrong Author"),
                new BookData("Wrong Book", "Wrong description", "Story"), List.of(printed("Opening", "Setup", 1)),
                true, 6, false, true));
        when(analyzer.analyze(any(), any())).thenReturn(first, later);

        var result = service.extract(pdf(8, true), "book.pdf");

        assertThat(result.authorDetails().getAuthorName()).isEqualTo("First Author");
        assertThat(result.bookDetails().getBookName()).isEqualTo("First Book");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("First description");
        assertThat(result.bookDetails().getBookType()).isEqualTo("Java Programming");
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        verify(analyzer, times(2)).analyze(any(), any());
        verifyNoMoreInteractions(analyzer);
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
    }

    private String json(Object response) {
        return mapper.writeValueAsString(response);
    }

    @Test
    void incompleteContentsOverridesFalseStopAndIgnoresAnOpeningReportedInsideContents() throws Exception {
        String first = json(new PdfBatchExtraction(new AuthorData("Writer One, Writer Two"),
                new BookData("Book", "A short description."),
                List.of(printed("Opening", "Basics", 1)), true, 5, false, false));
        String second = json(new PdfTopicBatchExtraction(
                List.of(printed("Opening", "Setup", 1), printed("Advanced", "Exercises", 5)),
                true, null, false, false));
        String third = json(new PdfTopicBatchExtraction(
                List.of(new TopicData("Advanced", "Review", null, null, null, null)),
                true, 12, false, true));
        List<String> responses = List.of(first, second, third);
        AtomicInteger calls = new AtomicInteger();
        when(analyzer.analyze(any(), any())).thenAnswer(call -> {
            verifyNoInteractions(books);
            return responses.get(calls.getAndIncrement());
        });

        var result = service.extract(pdf(20, true, Map.of(
                5, "Contents: Opening 1; Basics",
                6, "Setup; Advanced 5",
                10, "Exercises; Review",
                12, "Opening")), "book.pdf");

        ArgumentCaptor<PdfBatchExtraction> previous = ArgumentCaptor.forClass(PdfBatchExtraction.class);
        verify(analyzer, times(3)).analyze(any(), previous.capture());
        assertThat(previous.getAllValues().get(1).contentsComplete()).isFalse();
        assertThat(previous.getAllValues().get(1).requiredNextPageSet()).isTrue();
        assertThat(previous.getAllValues().get(1).firstTopicStartPage()).isNull();
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Writer One, Writer Two");
        assertThat(result.topicDetails()).hasSize(2);
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        assertThat(result.topicDetails().get(1).getDetails()).isEqualTo("Exercises, Review");
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedStartPage()).containsExactly(1, 5);
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedEndPage()).containsExactly(4, null);
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
        verifyNoMoreInteractions(books);
    }

    @Test
    void completeContentsFollowedByPrefaceStopsAndSavesWithoutReadingFirstChapter() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Book", "Description"), List.of(printed("Opening", "Basics", 1)),
                true, null, false, true)));

        var result = service.extract(pdf(10, true, Map.of(5, "Preface", 8, "Opening")), "book.pdf");

        verify(analyzer).analyze(any(), any());
        assertThat(result.topicDetails().getFirst().getStartPage()).isNull();
        assertThat(result.topicDetails().getFirst().getEndPage()).isNull();
        assertThat(result.topicDetails().getFirst().getPrintedStartPage()).isEqualTo(1);
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
    }

    @Test
    void headingChangeOverridesStaleNextFlagAndSavesMergedTopicsAtSecondBatch() throws Exception {
        String first = json(new PdfBatchExtraction(new AuthorData("Author"),
                new BookData("Book", "Description"), List.of(printed("Opening", "Basics", 1)),
                true, null, true, false));
        // Preface starts in this batch: stop even though the model still asks for more
        // pages and repeats a printed opening-page guess from an earlier batch.
        String second = json(new PdfTopicBatchExtraction(
                List.of(printed("Opening", "Setup", 1), printed("Advanced", "Exercises", 17)),
                true, 1, true, true));
        when(analyzer.analyze(any(), any())).thenAnswer(call -> {
            verifyNoInteractions(books);
            PdfPageBatch batch = call.getArgument(0);
            return batch.startPage() == 1 ? first : second;
        });

        var result = service.extract(pdf(30, true, Map.of(
                5, "Contents: Opening 1; Basics",
                6, "Setup; Advanced 17",
                8, "Preface",
                21, "Opening")), "book.pdf");

        verify(analyzer, times(2)).analyze(any(), any());
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Author");
        assertThat(result.topicDetails()).hasSize(2);
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        assertThat(result.topicDetails()).extracting(t -> t.getPrintedStartPage()).containsExactly(1, 17);
        assertThat(result.topicDetails().getFirst().getPrintedEndPage()).isEqualTo(16);
        assertThat(result.topicDetails().getLast().getPrintedEndPage()).isNull();
        assertThat(result.topicDetails()).allSatisfy(topic -> {
            assertThat(topic.getStartPage()).isNull();
            assertThat(topic.getEndPage()).isNull();
            assertThat(topic.getPdfPath()).isNull();
        });
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
        verifyNoMoreInteractions(books);
    }

    static byte[] pdf(int pages, boolean readable) throws IOException {
        return pdf(pages, readable, Map.of());
    }

    @Test
    void changedHeadingAtNextBatchStartSkipsModelAndSavesPreviousResponse() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Writer"), new BookData("Book", "Description"),
                List.of(printed("Opening", "Basics, Setup", 1)), true, null, true, false)));

        var result = service.extract(pdf(30, true, Map.of(
                4, "Contents\n1 Opening ........ 1\n1.1 Basics ........ 1",
                5, "CONTENTS v\n1.2 Setup ........ 3",
                6, "Preface\nDO_NOT_SEND_BODY",
                11, "Chapter 1\nDO_NOT_SEND_BODY")), "book.pdf");

        ArgumentCaptor<PdfPageBatch> pages = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer).analyze(pages.capture(), any());
        assertThat(pages.getValue().endPage()).isEqualTo(5);
        assertThat(pages.getValue().text()).doesNotContain("DO_NOT_SEND_BODY");
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
        verifyNoMoreInteractions(analyzer, books);
    }

    @Test
    void sourceHeadingChangeTrimsBatchAndOverridesModelThatNeverMarksContentsComplete() throws Exception {
        String first = json(new PdfBatchExtraction(new AuthorData("Writer"), new BookData("Book", "Description"),
                List.of(printed("Opening", "Basics", 1)), true, null, true, false));
        String second = json(new PdfTopicBatchExtraction(List.of(printed("Opening", "Setup", 1),
                printed("Advanced", "Exercises", 17)), true, null, true, false));
        when(analyzer.analyze(any(), any())).thenReturn(first, second);

        var result = service.extract(pdf(30, true, Map.of(
                5, "Contents\n1 Opening ........ 1\n1.1 Basics ........ 1",
                6, "vi CONTENTS\n1.2 Setup ........ 3",
                7, "CONTENTS vii\n2 Advanced ........ 17\n2.1 Exercises ........ 18",
                8, "Preface\nDO_NOT_SEND_BODY",
                11, "DO_NOT_SEND_BODY")), "book.pdf");

        ArgumentCaptor<PdfPageBatch> pages = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer, times(2)).analyze(pages.capture(), any());
        assertThat(pages.getValue().startPage()).isEqualTo(6);
        assertThat(pages.getValue().endPage()).isEqualTo(7);
        assertThat(pages.getAllValues()).allSatisfy(batch ->
                assertThat(batch.text()).doesNotContain("DO_NOT_SEND_BODY"));
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        assertThat(result.topicDetails()).hasSize(2);
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
        verifyNoMoreInteractions(analyzer, books);
    }

    @Test
    void samePageHeadingChangeSendsOnlyContentsPrefixThenSaves() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(
                json(new PdfBatchExtraction(null, new BookData("Book", "Description"),
                        List.of(printed("Opening", "Basics", 1)), true, null, true, false)),
                json(new PdfTopicBatchExtraction(List.of(printed("Opening", "Setup", 1)),
                        true, null, true, false)));

        var result = service.extract(pdf(20, true, Map.of(
                5, "Contents\n1 Opening ........ 1\n1.1 Basics ........ 1",
                6, "CONTENTS vi\n1.2 Setup ........ 3\nPreface\nDO_NOT_SEND_BODY")), "book.pdf");

        ArgumentCaptor<PdfPageBatch> pages = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer, times(2)).analyze(pages.capture(), any());
        assertThat(pages.getValue().endPage()).isEqualTo(6);
        assertThat(pages.getValue().text()).contains("Setup").doesNotContain("Preface", "DO_NOT_SEND_BODY");
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
    }

    @Test
    void repeatedContentsHeadingPreventsPrematureModelCompletionFromDroppingContinuation() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(
                json(new PdfBatchExtraction(null, new BookData("Book", "Description"),
                        List.of(printed("Opening", "Basics", 1)), true, null, false, true)),
                json(new PdfTopicBatchExtraction(List.of(printed("Opening", "Setup", 1)),
                        true, null, true, false)));

        var result = service.extract(pdf(20, true, Map.of(
                5, "Contents\n1 Opening ........ 1\n1.1 Basics ........ 1",
                6, "vi CONTENTS\n1.2 Setup ........ 3",
                7, "Preface\nDO_NOT_SEND_BODY")), "book.pdf");

        ArgumentCaptor<PdfBatchExtraction> previous = ArgumentCaptor.forClass(PdfBatchExtraction.class);
        verify(analyzer, times(2)).analyze(any(), previous.capture());
        assertThat(previous.getValue().contentsComplete()).isFalse();
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Basics, Setup");
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName(result.bookDetails().getBookName());
    }

    @Test
    void duplicateBookNameReturnsConflictBeforeAnyAuthorOrBookSave() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Writer"), new BookData("  Existing Book  ", "Description"),
                List.of(), false, null, false, true)));
        when(books.existsByCanonicalBookName("Existing Book")).thenReturn(true);

        assertThatThrownBy(() -> service.extract(pdf(1, true, Map.of()), "source.pdf"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode().value()).isEqualTo(409);
                    assertThat(failure.getReason()).contains("Existing Book", "already exists");
                });
        verify(books).existsByCanonicalBookName("Existing Book");
        verify(books, never()).saveAndFlush(any());
    }

    @Test
    void normalizesOnlySurroundingBookNameWhitespaceBeforeCheckingAndSaving() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Writer"), new BookData("  Original Book Name  ", "Description"),
                List.of(), false, null, false, true)));

        var result = service.extract(pdf(1, true, Map.of()), "source.pdf");

        assertThat(result.bookDetails().getBookName()).isEqualTo("Original Book Name");
        verify(books).existsByCanonicalBookName("Original Book Name");
        verify(books).saveAndFlush(result.bookDetails());
    }

    @Test
    void duplicateNameConstraintRaceReturnsConflictWithoutRetryingSave() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Writer"), new BookData("Concurrent Book", "Description"),
                List.of(), false, null, false, true)));
        when(books.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "Unique constraint uk_book_details_book_name violated"));

        assertThatThrownBy(() -> service.extract(pdf(1, true, Map.of()), "source.pdf"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode().value()).isEqualTo(409));
        verify(books, times(1)).saveAndFlush(any());
    }

    @Test
    void unrelatedIntegrityFailureIsNotMisreportedAsDuplicateBookName() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Writer"), new BookData("Another Book", "Description"),
                List.of(), false, null, false, true)));
        DataIntegrityViolationException integrity = new DataIntegrityViolationException("Other constraint failure");
        when(books.saveAndFlush(any())).thenThrow(integrity);

        assertThatThrownBy(() -> service.extract(pdf(1, true, Map.of()), "source.pdf"))
                .isSameAs(integrity);
    }

    @Test
    void bookNameBeyondUniqueColumnCapacityFailsBeforeDatabaseWork() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Writer"), new BookData("B".repeat(256), "Description"),
                List.of(), false, null, false, true)));

        assertThatThrownBy(() -> service.extract(pdf(1, true, Map.of()), "source.pdf"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode().value()).isEqualTo(422));
        verifyNoInteractions(books);
    }

    @Test
    void wholeBookNameThatCannotFitItsExactReservedOffsetFailsClearlyBeforeDatabaseWork() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("B".repeat(249), "Description", "Story"),
                List.of(), false, null, false, false)));

        assertThatThrownBy(() -> service.extract(pdf(1, true), "source.pdf"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode().value()).isEqualTo(422);
                    assertThat(failure.getReason()).contains("reserved _offset", "248");
                });
        verifyNoInteractions(books);
    }

    @Test
    void missingRequiredTopicStartRetainsBookMetadataAndUsesReservedStorageOffset() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(
                new AuthorData("Writer"), new BookData("Reliable Book Metadata", "Known description"),
                List.of(new TopicData("Unknown pages", "A child", null, null, null, null)),
                true, null, false, true)));

        var result = service.extract(pdf(4, true), "source.pdf");

        assertWholeBook(result, 4);
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Writer");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("Known description");
        verify(books, times(1)).saveAndFlush(result.bookDetails());
    }

    @Test
    void emptyOrConflictingContentsCollapseBeforeSavingAnyPartialTopics() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Conflicting Contents", "Description"), List.of(printed("Late", null, 17),
                        printed("Earlier", null, 3)), true, null, false, true)));

        var result = service.extract(pdf(4, true), "source.pdf");

        assertWholeBook(result, 4);
        assertThat(result.fallbackReason()).contains("decrease");
        verify(books, times(1)).saveAndFlush(result.bookDetails());
    }

    @Test
    void noTopicsAndMissingControlFlagsRetainAvailableFirstBatchBookDetails() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(new AuthorData("Writer"),
                new BookData("Known Title", "Known description"), List.of(), null, null, null, null)));

        var result = service.extract(pdf(3, true), "source.pdf");

        assertWholeBook(result, 3);
        assertThat(result.bookDetails().getBookName()).isEqualTo("Known Title");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("Known description");
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Writer");
    }

    @Test
    void emptyCompletedModelContentsUseOnlyReservedStorageOffset() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("No chapter details", "A whole book"), List.of(), true, null, false, true)));

        assertWholeBook(service.extract(pdf(3, true), "source.pdf"), 3);
    }

    @Test
    void invalidTopicNameDoesNotLoseKnownAuthorAndBookMetadata() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(new AuthorData("Writer"),
                new BookData("Known Book", "Description"), List.of(new TopicData(null, "Missing title", null,
                        null, 1, null)), true, null, false, true)));

        var result = service.extract(pdf(3, true), "source.pdf");

        assertWholeBook(result, 3);
        assertThat(result.bookDetails().getBookName()).isEqualTo("Known Book");
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Writer");
    }

    @Test
    void exhaustedInvalidModelJsonIsAQualityFallbackRatherThanAProviderFailure() throws Exception {
        when(analyzer.analyze(any(), any())).thenThrow(new UnusableContentsException(
                "The model did not return valid extraction JSON after 2 attempts; no extraction data was saved."));

        var result = service.extract(pdf(3, true), "C:\\fakepath\\Notes.PDF");

        assertWholeBook(result, 3);
        assertThat(result.bookDetails().getBookName()).isEqualTo("Notes");
        assertThat(result.authorDetails().getAuthorName()).isNull();
        assertThat(result.bookDetails().getBookDetails()).isNull();
        verify(books, times(1)).saveAndFlush(result.bookDetails());
    }

    @Test
    void operationalAiFailureIsNotMisclassifiedAsBadContents() throws Exception {
        var failure = new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "The model could not analyze the PDF pages.", new IllegalStateException("Connection refused"));
        when(analyzer.analyze(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service.extract(pdf(3, true), "source.pdf")).isSameAs(failure);
        verifyNoInteractions(books);
    }

    @Test
    void sourceContentsAtEndOfDocumentWithoutFollowingSectionIsNotTreatedAsComplete() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Contents Only", "Description"), List.of(printed("Opening", "Basics", 1)),
                true, null, false, true)));

        var result = service.extract(pdf(3, true, Map.of(1, "Contents\n1 Opening ....... 1")), "source.pdf");

        assertWholeBook(result, 3);
        assertThat(result.fallbackReason()).contains("complete contents");
    }

    @Test
    void readablePdfWithoutExtractionPermissionUsesProtectedWholeBookCopy() throws Exception {
        byte[] source;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            var permission = new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
            permission.setCanExtractContent(false);
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-password", "", permission);
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            document.protect(policy);
            document.save(output);
            source = output.toByteArray();
        }

        var result = service.extract(source, "protected.pdf");

        assertWholeBook(result, 2);
        assertThat(result.bookDetails().getBookName()).isEqualTo("protected");
        verifyNoInteractions(analyzer);
        assertThat(result.bookDetails().getBookType()).isEqualTo("Unknown");
        assertThat(result.bookDetails().getBookDetails()).isNull();
        verify(books).saveAndFlush(result.bookDetails());
    }

    @Test
    void initialIntroductionWithoutContentsClassifiesInSameCallAndUsesReservedStorageOffset() throws Exception {
        byte[] source = pdf(8, true, Map.of(
                1, "A River of Stories\nBy Mira Author",
                2, "Introduction\nThis collection presents short fictional stories.",
                3, "Preface\nThe stories explore ordinary life.",
                6, "DO_NOT_READ_LATER_FOR_CLASSIFICATION"));
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(new AuthorData("Mira Author"),
                new BookData("A River of Stories", "A collection of short stories.", "Story"),
                List.of(), false, null, false, false)));

        var result = service.extract(source, "upload.pdf");

        ArgumentCaptor<PdfPageBatch> batch = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer).analyze(batch.capture(), any());
        assertThat(batch.getValue().text()).contains("Introduction", "fictional stories", "Preface", "--- PDF PAGE 5 ---")
                .doesNotContain("DO_NOT_READ_LATER_FOR_CLASSIFICATION");
        assertThat(result.bookDetails().getBookType()).isEqualTo("Story");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("A collection of short stories.");
        assertWholeBook(result, 8);
        var order = inOrder(books);
        order.verify(books).existsByCanonicalBookName("A River of Stories");
        order.verify(books).saveAndFlush(result.bookDetails());
        verifyNoMoreInteractions(analyzer, books);
    }

    @Test
    void initialMetadataIncludesPrefaceAfterContentsButOnlyContentsEntriesBecomeTopics() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(new AuthorData("Author"),
                new BookData("Java Basics", "A programming guide.", "Java Programming"),
                List.of(printed("Opening", "Basics", 1), printed("Invented prose topic", "Not a topic", 2)),
                true, null, true, false)));
        byte[] source = pdf(8, true, Map.of(
                1, "Java Basics",
                2, "Contents\n1 Opening ........ 1\n1.1 Basics ........ 1\nPreface\nThis book teaches Java programming.",
                3, "Introduction\nClasses and methods are explained.",
                6, "DO_NOT_SEND_LATER_BODY"));

        var result = service.extract(source, "source.pdf");

        ArgumentCaptor<PdfPageBatch> batch = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer).analyze(batch.capture(), any());
        assertThat(batch.getValue().text()).contains("Preface", "teaches Java", "Introduction", "Classes and methods")
                .doesNotContain("DO_NOT_SEND_LATER_BODY");
        assertThat(result.topicDetails()).extracting(topic -> topic.getTopicName()).containsExactly("Opening");
        assertThat(result.bookDetails().getBookType()).isEqualTo("Java Programming");
    }

    @Test
    void insufficientPrimaryMetadataLeavesUnknownTypeAndMissingDescriptionWithoutAnotherModelCall() throws Exception {
        byte[] source = pdf(2, true, Map.of(1, "Notes\nIntroduction"));
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Notes", " ", null), List.of(), false, null, false, false)));
        var result = service.extract(source, "source.pdf");

        assertThat(result.bookDetails().getBookType()).isEqualTo("Unknown");
        assertThat(result.bookDetails().getBookDetails() == null || result.bookDetails().getBookDetails().isBlank()).isTrue();
        assertWholeBook(result, 2);
        verify(analyzer).analyze(any(), any());
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName("Notes");
        verifyNoMoreInteractions(analyzer, books);
    }

    @Test
    void existingPrimaryDescriptionsAreSavedWithoutAdditionalModelCalls() throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(null,
                new BookData("Known Book", "  Keep this exact description.  ", "History"),
                List.of(printed("Opening", "Existing topic details", 1)), true, null, false, true)));
        var result = service.extract(pdf(3, true), "source.pdf");

        assertThat(result.bookDetails().getBookDetails()).isEqualTo("  Keep this exact description.  ");
        assertThat(result.topicDetails().getFirst().getDetails()).isEqualTo("Existing topic details");
        assertThat(result.bookDetails().getBookType()).isEqualTo("History");
        verify(analyzer).analyze(any(), any());
        verify(books).saveAndFlush(result.bookDetails());
        verify(books).existsByCanonicalBookName("Known Book");
        verifyNoMoreInteractions(analyzer, books);
    }

    @Test
    void combinedFirstBatchJsonSuppliesBookTypeAndAllDescriptionsBeforeOneSave() throws Exception {
        String bookDescription = "A programming guide explaining classes and methods.";
        String topicDescription = "Introduces classes, objects and method calls.";
        String combinedJson = json(new PdfBatchExtraction(new AuthorData("Primary Writer"),
                new BookData("A Programming Guide", bookDescription, "Java Programming"),
                List.of(printed("Classes and Objects", topicDescription, 1)), true, null, false, true));
        when(analyzer.analyze(any(), any())).thenAnswer(call -> {
            verifyNoInteractions(books);
            return combinedJson;
        });

        var result = service.extract(pdf(4, true), "guide.pdf");

        assertThat(result.wholeBook()).isFalse();
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Primary Writer");
        assertThat(result.bookDetails().getBookName()).isEqualTo("A Programming Guide");
        assertThat(result.bookDetails().getBookType()).isEqualTo("Java Programming");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo(bookDescription);
        assertThat(result.topicDetails()).singleElement().satisfies(topic -> {
            assertThat(topic.getTopicName()).isEqualTo("Classes and Objects");
            assertThat(topic.getDetails()).isEqualTo(topicDescription);
        });
        verify(analyzer, times(1)).analyze(any(), any());
        verify(books, times(1)).existsByCanonicalBookName("A Programming Guide");
        verify(books, times(1)).saveAndFlush(result.bookDetails());
        verifyNoMoreInteractions(analyzer, books);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingPrimaryDescriptionsDoNotTriggerAnotherModelCallOrInventFallbackText(String missing) throws Exception {
        when(analyzer.analyze(any(), any())).thenReturn(json(new PdfBatchExtraction(new AuthorData("Writer"),
                new BookData("Known History Book", missing, "History"),
                List.of(printed("Opening", missing, 1)), true, null, false, true)));

        var result = service.extract(pdf(3, true), "source.pdf");

        assertThat(result.wholeBook()).isFalse();
        assertThat(result.bookDetails().getBookType()).isEqualTo("History");
        assertThat(result.bookDetails().getBookDetails() == null || result.bookDetails().getBookDetails().isBlank()).isTrue();
        assertThat(result.topicDetails()).singleElement().satisfies(topic -> {
            assertThat(topic.getTopicName()).isEqualTo("Opening");
            assertThat(topic.getDetails() == null || topic.getDetails().isBlank()).isTrue();
        });
        verify(analyzer, times(1)).analyze(any(), any());
        verify(books, times(1)).existsByCanonicalBookName("Known History Book");
        verify(books, times(1)).saveAndFlush(result.bookDetails());
        verifyNoMoreInteractions(analyzer, books);
    }

    @Test
    void successfulTwoBatchExtractionPreservesPrimaryDescriptionsAndMakesOnlyTwoCalls() throws Exception {
        String primaryBookDescription = "  Preserve the primary book description exactly.  ";
        String primaryTopicDescription = "Preserve the first topic description exactly.";
        String first = json(new PdfBatchExtraction(new AuthorData("Primary Author"),
                new BookData("Primary Book", primaryBookDescription, "History"),
                List.of(printed("Opening", primaryTopicDescription, 1)), true, null, true, false));
        String second = json(new PdfTopicBatchExtraction(List.of(printed("Later", "Later topic details.", 5)),
                true, null, false, true));
        when(analyzer.analyze(any(), any())).thenReturn(first, second);

        var result = service.extract(pdf(8, true), "source.pdf");

        assertThat(result.wholeBook()).isFalse();
        assertThat(result.bookDetails().getBookType()).isEqualTo("History");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo(primaryBookDescription);
        assertThat(result.topicDetails()).extracting(topic -> topic.getDetails())
                .containsExactly(primaryTopicDescription, "Later topic details.");
        ArgumentCaptor<PdfPageBatch> batches = ArgumentCaptor.forClass(PdfPageBatch.class);
        verify(analyzer, times(2)).analyze(batches.capture(), any());
        assertThat(batches.getAllValues()).extracting(PdfPageBatch::startPage).containsExactly(1, 6);
        verify(books, times(1)).existsByCanonicalBookName("Primary Book");
        verify(books, times(1)).saveAndFlush(result.bookDetails());
        verifyNoMoreInteractions(analyzer, books);
    }

    private static void assertWholeBook(BookExtractionResult result, int pages) {
        assertThat(result.wholeBook()).isTrue();
        assertThat(result.fallbackReason()).isNotBlank();
        assertThat(result.topicDetails()).singleElement().satisfies(offset -> {
            assertThat(offset.getTopicName()).isEqualTo(result.bookDetails().getBookName() + "_offset");
            assertThat(offset.getDetails()).isEqualTo(result.bookDetails().getBookName() + "_offset detail");
            assertThat(offset.getStartPage()).isEqualTo(1);
            assertThat(offset.getEndPage()).isEqualTo(pages);
            assertThat(offset.getPrintedStartPage()).isNull();
            assertThat(offset.getPrintedEndPage()).isNull();
            assertThat(offset.getPdfPath()).isNull();
            assertThat(offset.getBookDetails()).isSameAs(result.bookDetails());
        });
        assertThat(result.bookDetails().getTopics()).containsExactlyElementsOf(result.topicDetails());
        assertThat(result.bookDetails().getTotalPages()).isEqualTo(pages);
    }

    private static byte[] pdf(int pages, boolean readable, Map<Integer, String> pageText) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (readable) {
                    try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(50, 600);
                        for (String line : pageText.getOrDefault(i, "Page " + i).split("\\R")) {
                            content.showText(line);
                            content.newLineAtOffset(0, -16);
                        }
                        content.endText();
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
