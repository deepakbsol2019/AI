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
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.book.dto.BookExtractionResult;
import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfPageBatch;
import com.book.dto.PdfTopicBatchExtraction;
import com.book.repository.BookDetailsRepository;

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
            if (totalPages == 0 || !document.getCurrentAccessPermission().canExtractContent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The PDF has no pages or does not permit text extraction.");
            }
            // Uses printed margin evidence, not PDF length, for the final topic.
            merged.setLastPrintedPage(PrintedPageNumberResolver.findLastPrintedPage(document));
            for (int start = 1; start <= totalPages; start += PAGE_BATCH_SIZE) {
                int end = Math.min(start + PAGE_BATCH_SIZE - 1, totalPages);
                ReadBatch read = readBatch(document, start, end, boundaryDetector, merged.snapshot());
                if (read.boundaryFound()) {
                    logger.info("Book {}: Contents/Index heading changed; no pages after the boundary will be sent to AI",
                            extractionId);
                }
                PdfPageBatch batch = read.pages();
                if (batch == null) {
                    if (read.boundaryFound() || (read.contentsSeen() && end == totalPages)) {
                        merged.markContentsComplete();
                        return save(merged, extractionId, sourceName, totalPages);
                    }
                    continue;
                }
                logger.info("Extracting book {} from PDF pages {}-{}", extractionId, batch.startPage(), batch.endPage());
                pageReferences.accept(batch.text());
                String rawJson = analyzer.analyze(batch, merged.snapshot());

                boolean firstBatch = start == 1;
                PdfBatchExtraction response = parse(rawJson, firstBatch);
                if (response.indexFound() == null || response.requiredNextPageSet() == null
                        || response.contentsComplete() == null) {
                    throw ExtractionAccumulator.invalid("Required control flags are missing.");
                }
                // Once a real Contents heading is observed, the PDF section boundary,
                // rather than the model's completion flag, controls the loop.
                boolean sourceComplete = read.boundaryFound() || (read.contentsSeen() && end == totalPages);
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
                    return save(merged, extractionId, sourceName, totalPages);
                }
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Reached the end of the PDF before confirming the end of the contents/index section. Extraction: "
                            + extractionId);
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
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The model returned invalid extraction JSON; no extraction data was saved.", exception);
        }
    }

    private BookExtractionResult save(ExtractionAccumulator merged, String extractionId, String sourceName,
            int totalPages) {
        BookExtractionResult result = merged.toEntities(extractionId, sourceName, totalPages);
        bookRepository.saveAndFlush(result.bookDetails());
        return result;
    }

    private record ReadBatch(PdfPageBatch pages, boolean boundaryFound, boolean contentsSeen) { }

    private ReadBatch readBatch(PDDocument document, int start, int end, ContentsBoundaryDetector detector,
            PdfBatchExtraction previous) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        StringBuilder text = new StringBuilder();
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
            ContentsBoundaryDetector.PageScan scan = detector.scanPage(page, pageText, knownTopics);
            contentsSeen = scan.contentsSeen();
            if (!scan.textBeforeBoundary().isBlank()) {
                text.append("--- PDF PAGE ").append(page).append(" ---\n")
                        .append(scan.textBeforeBoundary()).append("\n");
                lastIncludedPage = page;
            }
            if (scan.boundaryFound()) {
                boundaryFound = true;
                break;
            }
        }
        if (start == 1 && !readable) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The first PDF pages contain no extractable text. Run OCR on scanned PDFs first.");
        }
        PdfPageBatch pages = text.isEmpty() ? null
                : new PdfPageBatch(start, lastIncludedPage, document.getNumberOfPages(), text.toString());
        return new ReadBatch(pages, boundaryFound, contentsSeen);
    }
}
