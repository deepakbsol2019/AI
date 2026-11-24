package com.book.extraction;

import java.io.IOException;
import java.util.UUID;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.book.dto.BookExtractionResult;
import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfPageBatch;
import com.book.dto.PdfTopicBatchExtraction;
import com.book.repository.BookDetailsRepository;
import com.book.entity.BookDetails;
import com.book.entity.BookUploadStatus;

@Service
public class BookMetadataExtractionService {
    private static final int PAGE_BATCH_SIZE = 5;
    private static final Logger logger = LoggerFactory.getLogger(BookMetadataExtractionService.class);
    private final BookPageAnalyzer analyzer;
    private final BookDetailsRepository bookRepository;
    private final ObjectMapper objectMapper;
    public BookMetadataExtractionService(BookPageAnalyzer analyzer, BookDetailsRepository bookRepository,
            ObjectMapper objectMapper) {
        this.analyzer = analyzer;
        this.bookRepository = bookRepository;
        this.objectMapper = objectMapper;
    }

    // Accumulation is local to this upload. No database writes occur during model calls.
    // The completed book, author and topics are saved together by the repository.
    public BookExtractionResult extract(byte[] pdfData, String fileName) {
        return extract(pdfData, fileName, null);
    }

    public BookExtractionResult extract(byte[] pdfData, String fileName, Long bookUploadId) {
        if (bookUploadId != null && bookUploadId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The upload tracking ID must be positive.");
        }
        if (pdfData == null || pdfData.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a non-empty PDF.");
        }
        String sourceName = fileName == null || fileName.isBlank() ? "Untitled book" : fileName;
        if (sourceName.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file name is too long.");
        }
        String extractionId = UUID.randomUUID().toString();
        ExtractionAccumulator merged = new ExtractionAccumulator();
        ContentsBoundaryDetector boundaryDetector = new ContentsBoundaryDetector();
        ContentsPageReferenceParser pageReferences = new ContentsPageReferenceParser();
        try (PDDocument document = Loader.loadPDF(pdfData)) {
            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The PDF has no pages.");
            }
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                // Keep the source file's protections intact; a whole-book copy does not extract its content.
                merged.useWholeBook("The PDF does not permit contents text extraction or topic splitting.");
                return save(merged, extractionId, sourceName, totalPages, bookUploadId);
            }
            // Uses printed margin evidence, not PDF length, for the final topic.
            merged.setLastPrintedPage(PrintedPageNumberResolver.findLastPrintedPage(document));
            try {
            for (int start = 1; start <= totalPages; start += PAGE_BATCH_SIZE) {
                int end = Math.min(start + PAGE_BATCH_SIZE - 1, totalPages);
                ReadBatch read = readBatch(document, start, end, boundaryDetector, merged.snapshot());
                if (start == 1 && !read.readable()) {
                    merged.useWholeBook("The first PDF pages have no extractable contents text.");
                    return save(merged, extractionId, sourceName, totalPages, bookUploadId);
                }
                if (read.boundaryFound()) {
                    logger.info("Book {}: Contents/Index heading changed; later contents batches stop at this boundary",
                            extractionId);
                }
                PdfPageBatch batch = read.pages();
                if (batch == null) {
                    if (read.boundaryFound()) {
                        merged.markContentsComplete();
                        return save(merged, extractionId, sourceName, totalPages, bookUploadId);
                    }
                    continue;
                }
                logger.info("Extracting book {} from PDF pages {}-{}", extractionId, batch.startPage(), batch.endPage());
                pageReferences.accept(read.contentsText());
                String rawJson = analyzer.analyze(batch, merged.snapshot());

                boolean firstBatch = start == 1;
                PdfBatchExtraction response = parse(rawJson, firstBatch);
                merged.captureMetadata(response, firstBatch);
                if (response.indexFound() == null || response.requiredNextPageSet() == null
                        || response.contentsComplete() == null) {
                    throw ExtractionAccumulator.invalid("Required control flags are missing.");
                }
                // Once a real Contents heading is observed, the PDF section boundary,
                // rather than the model's completion flag, controls the loop.
                boolean sourceComplete = read.boundaryFound();
                boolean complete = read.contentsSeen() ? sourceComplete : Boolean.TRUE.equals(response.contentsComplete());
                response = new PdfBatchExtraction(response.authorDetails(), response.bookDetails(),
                        response.topicDetails(), read.contentsSeen() || Boolean.TRUE.equals(response.indexFound()),
                        response.firstTopicStartPage(), !complete, complete);
                Integer opening = response.firstTopicStartPage();
                if (opening != null && (opening < batch.startPage() || opening > batch.endPage())) {
                    // Optional page guesses must not prevent saving a completed index.
                    response = new PdfBatchExtraction(response.authorDetails(), response.bookDetails(),
                            response.topicDetails(), response.indexFound(), null,
                            response.requiredNextPageSet(), response.contentsComplete());
                }
                merged.merge(response, firstBatch);
                merged.applyContentsReferences(pageReferences.entries());
                PdfBatchExtraction state = merged.snapshot();
                boolean fallback = start == 1 && !state.indexFound();
                // The following section heading ends extraction, even if the model's
                // next-page flag is stale or the first chapter has not been reached.
                boolean needsNext = !fallback && !Boolean.TRUE.equals(state.contentsComplete());
                logger.info("Book {} pages {}-{}: contentsComplete={}, firstTopicStartPage={}, requiredNextPageSet={}",
                        extractionId, start, end, state.contentsComplete(), state.firstTopicStartPage(), needsNext);
                if (!needsNext) {
                    return save(merged, extractionId, sourceName, totalPages, bookUploadId);
                }
            }
            throw new UnusableContentsException(
                    "The PDF ended before a complete contents section could be confirmed.");
            }
            catch (UnusableContentsException exception) {
                merged.useWholeBook(exception.getReason());
                logger.info("Book {} will retain the complete PDF in its reserved offset row: {}", extractionId,
                        exception.getReason());
                return save(merged, extractionId, sourceName, totalPages, bookUploadId);
            }
        }
        catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The file must be a readable, unencrypted PDF.", exception);
        }
    }

    private PdfBatchExtraction parse(String rawJson, boolean firstBatch) {
        if (rawJson == null || rawJson.isBlank()) {
            throw ExtractionAccumulator.invalid("The model returned no JSON.");
        }
        try {
            PdfBatchExtraction response;
            if (firstBatch) {
                response = objectMapper.readValue(rawJson, PdfBatchExtraction.class);
            }
            else {
                PdfTopicBatchExtraction topics = objectMapper.readValue(rawJson, PdfTopicBatchExtraction.class);
                response = topics == null ? null : topics.toBatchExtraction();
            }
            if (response == null) {
                throw ExtractionAccumulator.invalid("The model returned null.");
            }
            return response;
        }
        catch (JacksonException exception) {
            throw new UnusableContentsException("The model returned invalid extraction JSON.", exception);
        }
    }

    private BookExtractionResult save(ExtractionAccumulator merged, String extractionId, String sourceName,
            int totalPages, Long bookUploadId) {
        BookExtractionResult result = merged.toEntities(extractionId, sourceName, totalPages);
        if (!result.wholeBook() && (result.topicDetails().isEmpty()
                || result.topicDetails().stream().anyMatch(topic -> topic.getPrintedStartPage() == null
                        || topic.getPrintedStartPage() <= 0))) {
            merged.useWholeBook("The contents do not provide a verified printed start for every topic.");
            result = merged.toEntities(extractionId, sourceName, totalPages);
        }
        if (bookUploadId != null) {
            // The acknowledgement row is already committed. This reference joins it without cascading status changes.
            BookUploadStatus uploadStatus = new BookUploadStatus();
            uploadStatus.setBookUploadId(bookUploadId);
            result.bookDetails().setBookUploadStatus(uploadStatus);
        }
        String bookName = result.bookDetails().getBookName().trim();
        if (bookName.isBlank() || bookName.length() > BookDetails.MAX_BOOK_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The book name must contain 1 to " + BookDetails.MAX_BOOK_NAME_LENGTH + " characters.");
        }
        result.bookDetails().setBookName(bookName);
        if (bookRepository.existsByCanonicalBookName(bookName)) {
            throw duplicateBook(bookName, null);
        }
        try {
            bookRepository.saveAndFlush(result.bookDetails());
        }
        catch (DataIntegrityViolationException exception) {
            // The database constraint closes the race between the friendly precheck and the insert.
            // Other integrity errors (for example, extraction IDs) must retain their original cause.
            if (isBookNameConstraint(exception)) {
                throw duplicateBook(bookName, exception);
            }
            throw exception;
        }
        return result;
    }

    private static ResponseStatusException duplicateBook(String bookName, Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "A book named '" + bookName + "' already exists.", cause);
    }

    private static boolean isBookNameConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(java.util.Locale.ROOT)
                        .contains("uk_book_details_book_name");
            }
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().toLowerCase(java.util.Locale.ROOT)
                    .contains("uk_book_details_book_name")) {
                return true;
            }
        }
        return false;
    }

    private record ReadBatch(PdfPageBatch pages, String contentsText, boolean boundaryFound,
            boolean contentsSeen, boolean readable) { }

    private ReadBatch readBatch(PDDocument document, int start, int end, ContentsBoundaryDetector detector,
            PdfBatchExtraction previous) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        StringBuilder text = new StringBuilder();
        StringBuilder metadataText = new StringBuilder();
        boolean readable = false;
        boolean boundaryFound = false;
        boolean contentsSeen = false;
        int lastIncludedPage = start;
        List<String> knownTopics = previous.topicDetails().stream().map(PdfBatchExtraction.TopicData::topicName).toList();
        for (int page = start; page <= end; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(document);
            readable |= !pageText.isBlank();
            if (start == 1 && !pageText.isBlank()) {
                metadataText.append("--- PDF PAGE ").append(page).append(" ---\n")
                        .append(pageText).append("\n");
            }
            // Initial metadata/classification uses all of pages 1-5, including
            // preface/introduction after Contents. Those pages never become topics.
            if (boundaryFound) continue;
            ContentsBoundaryDetector.PageScan scan = detector.scanPage(page, pageText, knownTopics);
            contentsSeen = scan.contentsSeen();
            if (!scan.textBeforeBoundary().isBlank()) {
                text.append("--- PDF PAGE ").append(page).append(" ---\n")
                        .append(scan.textBeforeBoundary()).append("\n");
                lastIncludedPage = page;
            }
            if (scan.boundaryFound()) {
                boundaryFound = true;
                if (start != 1) break;
            }
        }
        String batchText = start == 1 ? metadataText.toString() : text.toString();
        PdfPageBatch pages = batchText.isEmpty() ? null
                : new PdfPageBatch(start, start == 1 ? end : lastIncludedPage,
                        document.getNumberOfPages(), batchText);
        return new ReadBatch(pages, text.toString(), boundaryFound, contentsSeen, readable);
    }
}
