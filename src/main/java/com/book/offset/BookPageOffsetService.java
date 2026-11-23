package com.book.offset;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.book.dto.BookExtractionResult;
import com.book.entity.TopicDetails;
import com.book.offset.BookPageOffsetResult.Issue;
import com.book.offset.BookPageOffsetResult.UpdateOutcome;
import com.book.offset.BookPageOffsetResult.UpdateStatus;
import com.book.offset.SavedBookTopicSelector.Selection;
import com.book.offset.SavedBookTopicSelector.SelectionException;
import com.book.offset.TopicPageOffsetWriter.OffsetWriteException;
import com.book.offset.TopicPageOffsetWriter.SourceSnapshot;

/** Post-save phase. Detection is read-only; the writer joins the caller's upload transaction. */
@Service
public class BookPageOffsetService {
    private static final Logger logger = LoggerFactory.getLogger(BookPageOffsetService.class);
    private final SavedBookTopicSelector selector;
    private final ChapterOpeningDetector detector;
    private final PageOffsetAssumptionValidator assumptions;
    private final TopicPageOffsetWriter writer;

    public BookPageOffsetService(SavedBookTopicSelector selector, ChapterOpeningDetector detector,
            PageOffsetAssumptionValidator assumptions, TopicPageOffsetWriter writer) {
        this.selector = selector;
        this.detector = detector;
        this.assumptions = assumptions;
        this.writer = writer;
    }

    public BookExtractionResult populatePdfPages(BookExtractionResult saved, byte[] pdfBytes) {
        Long bookId = saved == null || saved.bookDetails() == null ? null : saved.bookDetails().getBookId();
        Selection selection;
        try {
            selection = selector.select(saved);
        }
        catch (SelectionException exception) {
            List<Issue> issues = exception.issues().stream()
                    .map(message -> new Issue(null, "invalid_saved_response", message)).toList();
            return result(saved, saved == null ? List.of() : saved.topicDetails(),
                    new BookPageOffsetResult(bookId, null, null, null,
                            unavailable("The saved response is incomplete or invalid."),
                            new UpdateOutcome(UpdateStatus.not_attempted, 0, null, null, issues)));
        }
        catch (RuntimeException exception) {
            logger.error("Saved topic response could not be verified for book {}", bookId, exception);
            return result(saved, saved == null ? List.of() : saved.topicDetails(),
                    new BookPageOffsetResult(bookId, null, null, null,
                            unavailable("The saved topic collection could not be verified."),
                            new UpdateOutcome(UpdateStatus.not_attempted, 0, null, null,
                                    List.of(new Issue(null, "saved_response_verification_failed",
                                            "Could not verify the complete saved topic list; no page updates were attempted.")))));
        }
        Map<Long, SourceSnapshot> sources = new LinkedHashMap<>();
        for (TopicDetails topic : selection.realTopics()) {
            sources.put(topic.getTopicId(), new SourceSnapshot(topic.getTopicId(), topic.getTopicName(),
                    topic.getPrintedStartPage(), topic.getPrintedEndPage()));
        }
        ChapterDetection detection;
        try {
            detection = detector.detect(pdfBytes, selection.firstTopic().getTopicName(),
                    selection.firstTopic().getPrintedStartPage());
        }
        catch (RuntimeException exception) {
            logger.error("Chapter detection failed for saved book {}", bookId, exception);
            return withOutcome(saved, selection, unavailable("PDF layout could not be processed."), null,
                    new UpdateOutcome(UpdateStatus.not_attempted, 0, null, null,
                            List.of(new Issue(null, "detection_failed", "PDF layout detection failed; no page updates were attempted."))),
                    selection.realTopics());
        }
        if (detection.status() != ChapterDetection.Status.found) {
            return withOutcome(saved, selection, detection, null,
                    new UpdateOutcome(UpdateStatus.not_attempted, 0, null, null,
                            List.of(new Issue(selection.firstTopic().getTopicId(), "unconfirmed_opening",
                                    "Chapter detection for '" + selection.firstTopic().getTopicName() + "' returned "
                                            + detection.status() + ". " + String.join(" ", detection.evidence())))),
                    selection.realTopics());
        }
        PageOffsetAssumptionValidator.Validation validation = assumptions.validate(selection, detection);
        if (!validation.issues().isEmpty()) {
            return withOutcome(saved, selection, detection, null,
                    new UpdateOutcome(UpdateStatus.not_attempted, 0, null, null, validation.issues()),
                    selection.realTopics());
        }
        int offset = validation.offsetNumber();
        int printedPageOffset = validation.printedPageOffset();
        try {
            var written = writer.apply(selection.bookId(), selection.bookName(), selection.realTopicIds(),
                    offset, detection.totalPdfPages(), Map.copyOf(sources), printedPageOffset);
            logger.info("Book {} chapter found on physical PDF page {}; front-matter pages {}; printed-page offset {}; page update {}; {} topics; offset row {} ({})",
                    bookId, detection.pdfPageNumber(), offset, printedPageOffset, written.status(), written.recordsUpdated(),
                    written.offsetEntryId(), written.offsetEntryStatus());
            return withOutcome(saved, selection, detection, offset, printedPageOffset,
                    new UpdateOutcome(UpdateStatus.valueOf(written.status().name()), written.recordsUpdated(),
                            written.offsetEntryId(), written.offsetEntryStatus().name(), List.of()), written.realTopics());
        }
        catch (OffsetWriteException exception) {
            List<Issue> issues = exception.issues().stream()
                    .map(issue -> new Issue(issue.topicId(), issue.code(), issue.message())).toList();
            return withOutcome(saved, selection, detection, offset, printedPageOffset,
                    new UpdateOutcome(UpdateStatus.failed, 0, null, null, issues), selection.realTopics());
        }
        catch (RuntimeException exception) {
            // The call crossed the transactional writer proxy; an insert/update/commit failure rolls back both phases.
            logger.error("PDF page update rolled back for saved book {}", bookId, exception);
            return withOutcome(saved, selection, detection, offset, printedPageOffset,
                    new UpdateOutcome(UpdateStatus.failed, 0, null, null,
                            List.of(new Issue(null, "persistence_failed", "The page update transaction failed and was rolled back."))),
                    selection.realTopics());
        }
    }

    private BookExtractionResult withOutcome(BookExtractionResult saved, Selection selection,
            ChapterDetection detection, Integer offset, UpdateOutcome update, List<TopicDetails> topics) {
        return withOutcome(saved, selection, detection, offset, offset, update, topics);
    }

    private BookExtractionResult withOutcome(BookExtractionResult saved, Selection selection,
            ChapterDetection detection, Integer offset, Integer printedPageOffset,
            UpdateOutcome update, List<TopicDetails> topics) {
        return result(saved, topics, new BookPageOffsetResult(selection.bookId(), selection.firstTopic().getTopicId(),
                selection.firstTopic().getTopicName(), offset, detection, update, printedPageOffset));
    }

    private BookExtractionResult result(BookExtractionResult saved, List<TopicDetails> topics,
            BookPageOffsetResult outcome) {
        return new BookExtractionResult(saved == null ? null : saved.authorDetails(),
                saved == null ? null : saved.bookDetails(),
                topics == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(topics)), outcome);
    }

    private ChapterDetection unavailable(String reason) {
        return new ChapterDetection(ChapterDetection.Status.insufficient_data, null, null, null, null,
                "unconfirmed", List.of(reason), List.of(), 0, List.of());
    }
}
