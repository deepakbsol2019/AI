package com.book.download;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.book.download.dto.DownloadBookModel;
import com.book.download.pdf.BookPdfMerger;
import com.book.download.pdf.FrontMatterRenderer;
import com.book.download.pdf.TopicPdfSource;
import com.book.download.service.impl.DownloadServiceImpl;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import com.book.entity.TopicDetails;
import com.book.repository.BookDetailsRepository;
import com.book.repository.TopicDetailsRepository;

/** Exercises actual PDFBox assembly and stored-file reads with distinct page markers. */
class DownloadServiceTests extends com.book.user.TenantAwareTest {
    @TempDir Path storage;
    private final BookDetailsRepository books = mock(BookDetailsRepository.class);
    private final TopicDetailsRepository topics = mock(TopicDetailsRepository.class);
    private final FrontMatterRenderer frontMatter = mock(FrontMatterRenderer.class);
    private DownloadServiceImpl service;
    private BookDetails book;

    @BeforeEach
    void setUp() {
        service = new DownloadServiceImpl(books, topics, new TopicPdfSource(storage.toString()),
                frontMatter, new BookPdfMerger());
        book = new BookDetails();
        book.setBookId(1L);
        book.setBookName("Algorithms");
        book.setBookDetails("A guide to algorithms.");
        book.setTotalPages(4);
        AuthorDetails author = new AuthorDetails();
        author.setAuthorName("Asha Sharma, Meera Rao");
        book.setAuthorDetails(author);
        when(books.findById(1L)).thenReturn(Optional.of(book));
    }

    @Test
    void fullBookPlacesAllOffsetPagesFirstThenSortsTopicIdsNumerically() throws Exception {
        TopicDetails ten = stored(10L, "Using_offset notes", "TEN");
        TopicDetails offset = stored(999L, "algorithm.pdf_offset", "ORIGINAL-COVER", "ORIGINAL-CONTENTS");
        TopicDetails two = stored(2L, "Searching", "TWO");
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of(ten, offset, two));

        assertThat(pageTexts(service.downloadBook(1L)))
                .containsExactly("ORIGINAL-COVER", "ORIGINAL-CONTENTS", "TWO", "TEN");
        verifyNoInteractions(frontMatter);
    }

    @Test
    void fullBookOffsetDownloadsEveryOriginalPageWithoutGeneratingACover() throws Exception {
        var uploadReceipt=new com.book.entity.BookUploadStatus();
        uploadReceipt.setUploadedBy(999L);book.setBookUploadStatus(uploadReceipt);
        TopicDetails offset = stored(70L, book.getBookName() + "_offset", "STANDALONE", "SECOND");
        offset.setDetails(book.getBookName() + "_offset detail");
        offset.setStartPage(1);
        offset.setEndPage(2);
        offset.setPrintedStartPage(null);
        offset.setPrintedEndPage(null);
        book.setTotalPages(2);
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of(offset));

        try(var scope=com.book.user.security.TenantContext.use(com.book.user.security.TenantContext.requireTenantId(),42L,false)) {
            assertThat(pageTexts(service.downloadBook(1L))).containsExactly("STANDALONE", "SECOND");
        }
        verifyNoInteractions(frontMatter);
    }

    @Test
    void fullBookRejectsAmbiguousOffsetRowsInsteadOfPickingOne() throws Exception {
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of(
                stored(10L, "First_offset", "FIRST"), stored(11L, "Second_offset", "SECOND")));
        assertStatus(() -> service.downloadBook(1L), HttpStatus.CONFLICT, "multiple");
        verifyNoInteractions(frontMatter);
    }

    @Test
    void middleOffsetTextDoesNotReplaceTheRequiredOffsetSuffix() throws Exception {
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L))
                .thenReturn(List.of(stored(10L, "Algorithms_offset notes", "CHAPTER")));
        assertStatus(() -> service.downloadBook(1L), HttpStatus.CONFLICT, "missing");
    }

    @Test
    void legitimateWholeBookFallbackNeedsNoOffsetOrGeneratedFrontMatter() throws Exception {
        TopicDetails whole = stored(10L, book.getBookName(), "ONE", "TWO", "THREE", "FOUR");
        whole.setDetails(book.getBookDetails());
        whole.setStartPage(1);
        whole.setEndPage(book.getTotalPages());
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of(whole));

        assertThat(pageTexts(service.downloadBook(1L))).containsExactly("ONE", "TWO", "THREE", "FOUR");
        verifyNoInteractions(frontMatter);
    }

    @Test
    void oneNormalTopicWithPrintedNumberingCannotHideAMissingOffset() throws Exception {
        TopicDetails chapter = stored(10L, book.getBookName(), "CHAPTER");
        chapter.setDetails(book.getBookDetails());
        chapter.setStartPage(1);
        chapter.setEndPage(book.getTotalPages());
        chapter.setPrintedStartPage(1);
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of(chapter));
        assertStatus(() -> service.downloadBook(1L), HttpStatus.CONFLICT, "missing");
    }

    @Test
    void zeroPrefixUploadRetainsItsEmptyOffsetAndDownloadsRealPages() throws Exception {
        TopicDetails offset = stored(999L, "Algorithms_offset");
        offset.setStartPage(1);
        offset.setEndPage(0);
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of(
                stored(2L, "Searching", "TWO"), offset));
        assertThat(pageTexts(service.downloadBook(1L))).containsExactly("TWO");
    }

    @Test
    void emptyBookIsNotASuccessfulEmptyPdf() {
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of());
        assertStatus(() -> service.downloadBook(1L), HttpStatus.CONFLICT, "no saved topic");
    }

    @Test
    void offsetOnlyBookDoesNotReturnJustTheOriginalFrontPagesAsACompleteBook() throws Exception {
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L))
                .thenReturn(List.of(stored(999L, "Algorithms_offset", "ORIGINAL-COVER")));
        assertStatus(() -> service.downloadBook(1L), HttpStatus.CONFLICT, "no saved chapter");
    }

    @Test
    void wholeBookTitleEndingInOffsetIsStillAValidWholeBookFallback() throws Exception {
        book.setBookName("Algorithms_offset");
        TopicDetails whole = stored(10L, book.getBookName(), "ONE", "TWO", "THREE", "FOUR");
        whole.setDetails(book.getBookDetails());
        whole.setStartPage(1);
        whole.setEndPage(book.getTotalPages());
        when(topics.findAllByBookDetailsBookIdOrderByTopicIdAsc(1L)).thenReturn(List.of(whole));
        assertThat(pageTexts(service.downloadBook(1L))).containsExactly("ONE", "TWO", "THREE", "FOUR");
    }

    @Test
    void selectedTopicOrderControlsBothTheTemplateModelAndOriginalPageOrder() throws Exception {
        TopicDetails graphs = stored(102L, "Graphs", "GRAPHS-ONE", "GRAPHS-TWO");
        TopicDetails searching = stored(100L, "Searching", "SEARCHING");
        TopicDetails sorting = stored(101L, "Sorting", "SORTING");
        List<Long> requested = List.of(102L, 100L, 101L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, requested))
                .thenReturn(List.of(searching, sorting, graphs));
        when(frontMatter.render(any())).thenReturn(pdf("GENERATED-COVER", "GENERATED-CONTENTS"));

        assertThat(pageTexts(service.downloadTopics(1L, requested))).containsExactly(
                "GENERATED-COVER", "GENERATED-CONTENTS", "GRAPHS-ONE", "GRAPHS-TWO", "SEARCHING", "SORTING");
        ArgumentCaptor<DownloadBookModel> model = ArgumentCaptor.forClass(DownloadBookModel.class);
        verify(frontMatter).render(model.capture());
        assertThat(model.getValue().selectedTopics()).extracting(DownloadBookModel.Topic::name)
                .containsExactly("Graphs", "Searching", "Sorting");
        assertThat(model.getValue().selectedTopics()).extracting(DownloadBookModel.Topic::id)
                .containsExactly(102L, 100L, 101L);
        assertThat(model.getValue().book().name()).isEqualTo(book.getBookName());
        assertThat(model.getValue().author().name()).isEqualTo("Asha Sharma, Meera Rao");
        verify(topics, never()).findAllByBookDetailsBookIdOrderByTopicIdAsc(anyLong());
    }

    @Test
    void oneSelectedTopicUsesTheSameServiceAndDoesNotAddOffset() throws Exception {
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, List.of(100L)))
                .thenReturn(List.of(stored(100L, "Searching", "SEARCHING")));
        when(frontMatter.render(any())).thenReturn(pdf("COVER", "CONTENTS"));
        assertThat(pageTexts(service.downloadTopics(1L, List.of(100L))))
                .containsExactly("COVER", "CONTENTS", "SEARCHING");
        verify(topics, never()).findAllByBookDetailsBookIdOrderByTopicIdAsc(anyLong());
    }

    @Test
    void explicitlySelectedOffsetStaysAtItsRequestedPosition() throws Exception {
        List<Long> requested = List.of(100L, 999L, 101L);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, requested)).thenReturn(List.of(
                stored(999L, "Algorithms_offset", "ORIGINAL-COVER"),
                stored(101L, "Sorting", "SORTING"), stored(100L, "Searching", "SEARCHING")));
        when(frontMatter.render(any())).thenReturn(pdf("COVER", "CONTENTS"));
        assertThat(pageTexts(service.downloadTopics(1L, requested)))
                .containsExactly("COVER", "CONTENTS", "SEARCHING", "ORIGINAL-COVER", "SORTING");
    }

    @Test
    void absentAuthorStaysAbsentInTheTypedTemplateModel() throws Exception {
        book.setAuthorDetails(null);
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, List.of(100L)))
                .thenReturn(List.of(stored(100L, "Searching", "SEARCHING")));
        when(frontMatter.render(any())).thenReturn(pdf("COVER", "CONTENTS"));
        service.downloadTopics(1L, List.of(100L));
        ArgumentCaptor<DownloadBookModel> model = ArgumentCaptor.forClass(DownloadBookModel.class);
        verify(frontMatter).render(model.capture());
        assertThat(model.getValue().author()).isNull();
    }

    @Test
    void missingOrForeignBookIdCannotProduceAPartialSelection() throws Exception {
        List<Long> requested = List.of(100L, 200L);
        // The scoped query excludes ID 200, whether it is absent or belongs to another book.
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, requested))
                .thenReturn(List.of(stored(100L, "Searching", "SEARCHING")));
        assertStatus(() -> service.downloadTopics(1L, requested), HttpStatus.NOT_FOUND, "Every requested topic");
        verify(topics).findAllByBookDetailsBookIdAndTopicIdIn(1L, requested);
        verifyNoInteractions(frontMatter);
    }

    @Test
    void duplicatesAreRejectedBeforeAnyRepositoryOrRendererCalls() {
        assertStatus(() -> service.downloadTopics(1L, List.of(100L, 100L)), HttpStatus.BAD_REQUEST, "duplicate");
        verifyNoInteractions(books, topics, frontMatter);
    }

    @Test
    void emptyAndNullSelectionsAreBadRequests() {
        assertStatus(() -> service.downloadTopics(1L, List.of()), HttpStatus.BAD_REQUEST, "at least one");
        assertStatus(() -> service.downloadTopics(1L, null), HttpStatus.BAD_REQUEST, "at least one");
        verifyNoInteractions(books, topics, frontMatter);
    }

    @Test
    void nullTopicIdIsABadRequest() {
        assertStatus(() -> service.downloadTopics(1L, Arrays.asList(100L, null)), HttpStatus.BAD_REQUEST, "positive");
        verifyNoInteractions(books, topics, frontMatter);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonpositiveBookAndTopicIdsAreBadRequests(Long invalid) {
        assertStatus(() -> service.downloadBook(invalid), HttpStatus.BAD_REQUEST, "positive");
        assertStatus(() -> service.downloadTopics(1L, List.of(invalid)), HttpStatus.BAD_REQUEST, "positive");
        verifyNoInteractions(books, topics, frontMatter);
    }

    @Test
    void nullBookIdIsABadRequest() {
        assertStatus(() -> service.downloadBook(null), HttpStatus.BAD_REQUEST, "positive");
        assertStatus(() -> service.downloadTopics(null, List.of(100L)), HttpStatus.BAD_REQUEST, "positive");
        verifyNoInteractions(books, topics, frontMatter);
    }

    @Test
    void missingBookFailsBeforeLookingUpItsTopics() {
        when(books.findById(77L)).thenReturn(Optional.empty());
        assertStatus(() -> service.downloadBook(77L), HttpStatus.NOT_FOUND, "not found");
        assertStatus(() -> service.downloadTopics(77L, List.of(100L)), HttpStatus.NOT_FOUND, "not found");
        verifyNoInteractions(topics, frontMatter);
    }

    @Test
    void unavailablePdfFailsInsteadOfReturningASelectionWithOmittedPages() throws Exception {
        TopicDetails topic = stored(100L, "Searching", "SEARCHING");
        Files.delete(Path.of(topic.getPdfPath()));
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, List.of(100L))).thenReturn(List.of(topic));
        assertStatus(() -> service.downloadTopics(1L, List.of(100L)), HttpStatus.INTERNAL_SERVER_ERROR, "missing");
        verifyNoInteractions(frontMatter);
    }

    @Test
    void corruptSecondSourceFailsTheEntireMerge() throws Exception {
        TopicDetails corrupt = stored(101L, "Sorting", "SORTING");
        Files.writeString(Path.of(corrupt.getPdfPath()), "This is not a PDF");
        when(topics.findAllByBookDetailsBookIdAndTopicIdIn(1L, List.of(100L, 101L)))
                .thenReturn(List.of(stored(100L, "Searching", "SEARCHING"), corrupt));
        when(frontMatter.render(any())).thenReturn(pdf("COVER", "CONTENTS"));
        assertStatus(() -> service.downloadTopics(1L, List.of(100L, 101L)),
                HttpStatus.INTERNAL_SERVER_ERROR, "Could not read or merge");
    }

    private TopicDetails stored(Long id, String name, String... pageMarkers) throws Exception {
        Path path = Files.createDirectories(storage.resolve("1")).resolve("legacy-name-" + id + ".pdf");
        Files.write(path, pdf(pageMarkers));
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(id);
        topic.setTopicName(name);
        topic.setBookDetails(book);
        topic.setPdfPath(path.toString());
        return topic;
    }

    private static byte[] pdf(String... pageMarkers) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String marker : pageMarkers) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 650);
                    content.showText(marker);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static List<String> pageTexts(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            java.util.ArrayList<String> text = new java.util.ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                text.add(stripper.getText(document).strip());
            }
            return text;
        }
    }

    private static void assertStatus(Runnable operation, HttpStatus status, String message) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
            assertThat(failure.getStatusCode()).isEqualTo(status);
            assertThat(failure.getReason()).containsIgnoringCase(message);
        });
    }
}
