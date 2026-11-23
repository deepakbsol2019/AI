package com.book.offset;

import java.util.List;
import java.util.Set;
import com.book.entity.TopicDetails;
import com.book.offset.ChapterDetection.PrintedAnchor;
import com.book.offset.SavedBookTopicSelector.Selection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageOffsetAssumptionValidatorTests {
    private final PageOffsetAssumptionValidator validator = new PageOffsetAssumptionValidator();

    @Test
    void physicalPage21AndZeroBasedIndex20ProduceExactlyOffset20() {
        var result = validator.validate(selection(1, 25, 50), found(20, 21, 50,
                new PrintedAnchor(21, 1, "visible_folio"), new PrintedAnchor(45, 25, "visible_folio")));
        assertThat(result.offsetNumber()).isEqualTo(20);
        assertThat(result.printedPageOffset()).isEqualTo(20);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void unnumberedTitlePageBeforePrintedOneIsReportedInsteadOfChangingTheFormula() {
        var result = validator.validate(selection(1, 25, 50), found(20, 21, 50,
                new PrintedAnchor(22, 1, "visible_folio")));
        assertThat(result.offsetNumber()).isNull();
        assertThat(result.issues()).extracting(BookPageOffsetResult.Issue::code)
                .contains("printed_anchor_mismatch");
    }

    @Test
    void laterNumberingDiscontinuityBlocksTheSingleOffsetUpdate() {
        var result = validator.validate(selection(1, 25, 50), found(20, 21, 50,
                new PrintedAnchor(21, 1, "visible_folio"), new PrintedAnchor(40, 18, "visible_folio")));
        assertThat(result.issues()).extracting(BookPageOffsetResult.Issue::code)
                .containsExactly("printed_anchor_mismatch");
    }

    @Test
    void selectedPrintedStartMustAgreeWithOpeningLabelAndAnchors() {
        var result = validator.validate(selection(3, 25, 50), found(20, 21, 50,
                new PrintedAnchor(21, 1, "pdf_page_label")));
        assertThat(result.issues()).extracting(BookPageOffsetResult.Issue::code)
                .contains("opening_printed_label_mismatch", "printed_anchor_mismatch");
    }

    @Test
    void printedChapterThreeOnPhysicalSixHasFivePrefixPagesAndPrintedOffsetThree() {
        ChapterDetection opening = foundWithLabel(5, 6, 90, "3",
                new PrintedAnchor(6, 3, "visible_folio"),
                new PrintedAnchor(14, 11, "visible_folio"),
                new PrintedAnchor(90, 87, "visible_folio"));
        var result = validator.validate(selection(3, 87, 90), opening);
        assertThat(result.issues()).isEmpty();
        assertThat(result.offsetNumber()).isEqualTo(5);
        assertThat(result.printedPageOffset()).isEqualTo(3);
    }

    @Test
    void supportsAnotherPositivePrintedStartWithoutConflatingItWithThePrefix() {
        var result = validator.validate(selection(3, 25, 50), foundWithLabel(20, 21, 50, "3",
                new PrintedAnchor(21, 3, "visible_folio"), new PrintedAnchor(43, 25, "visible_folio")));
        assertThat(result.issues()).isEmpty();
        assertThat(result.offsetNumber()).isEqualTo(20);
        assertThat(result.printedPageOffset()).isEqualTo(18);
    }

    @Test
    void supportsNegativePrintedOffsetWhenPdfStartsPartwayThroughPrintedNumbering() {
        var result = validator.validate(selection(10, 20, 15), foundWithLabel(1, 2, 15, "10",
                new PrintedAnchor(2, 10, "visible_folio"), new PrintedAnchor(12, 20, "visible_folio")));
        assertThat(result.issues()).isEmpty();
        assertThat(result.offsetNumber()).isEqualTo(1);
        assertThat(result.printedPageOffset()).isEqualTo(-8);
    }

    @Test
    void missingOrNonpositiveFirstPrintedReferenceIsNotTreatedAsZero() {
        for (Integer printed : new Integer[] {null, 0, -1}) {
            Selection selected = selection(1, 25, 50);
            selected.firstTopic().setPrintedStartPage(printed);
            var result = validator.validate(selected, found(20, 21, 50,
                    new PrintedAnchor(21, 1, "visible_folio")));
            assertThat(result.offsetNumber()).isNull();
            assertThat(result.printedPageOffset()).isNull();
            assertThat(result.issues()).extracting(BookPageOffsetResult.Issue::code)
                    .contains("invalid_first_printed_page");
            assertThat(result.issues().getFirst().topicId()).isEqualTo(12L);
        }
    }

    @Test
    void anchorValidationStopsAtRelevantPhysicalEndUsingPrintedOffset() {
        var result = validator.validate(selection(3, 10, 50), foundWithLabel(5, 6, 50, "3",
                new PrintedAnchor(6, 3, "visible_folio"),
                new PrintedAnchor(14, 1, "pdf_page_label")));
        // Printed end10 maps to physical13. A separate numbering regime at14 is outside this topic set.
        assertThat(result.issues()).isEmpty();
        assertThat(result.printedPageOffset()).isEqualTo(3);
    }

    @Test
    void paddedNumericPrintedLabelsKeepTheirNumericMeaning() {
        var result = validator.validate(selection(3, 25, 50), foundWithLabel(20, 21, 50, "003",
                new PrintedAnchor(21, 3, "visible_folio")));
        assertThat(result.issues()).isEmpty();
        assertThat(result.printedPageOffset()).isEqualTo(18);
    }

    @Test
    void unreadablePrintedAnchorsDoNotSilentlyConfirmTheMapping() {
        var result = validator.validate(selection(1, 25, 50), found(20, 21, 50));
        assertThat(result.offsetNumber()).isNull();
        assertThat(result.issues()).extracting(BookPageOffsetResult.Issue::code)
                .containsExactly("unverified_printed_anchor");
    }

    @Test
    void romanLabelAtTheOpeningContradictsPrintedPageOne() {
        ChapterDetection roman = new ChapterDetection(ChapterDetection.Status.found, 20, 21, "ix", null,
                "strong heading", List.of(), List.of(), 50,
                List.of(new PrintedAnchor(22, 2, "visible_folio")));
        var result = validator.validate(selection(1, 25, 50), roman);
        assertThat(result.offsetNumber()).isNull();
        assertThat(result.issues()).extracting(BookPageOffsetResult.Issue::code)
                .contains("opening_printed_label_mismatch");
    }

    @Test
    void mismatchedPdfAndInconsistentIndexAreRejected() {
        var result = validator.validate(selection(1, 25, 51), found(19, 21, 50,
                new PrintedAnchor(21, 1, "visible_folio")));
        assertThat(result.issues()).extracting(BookPageOffsetResult.Issue::code)
                .contains("invalid_detection_position", "pdf_page_count_mismatch");
    }

    @Test
    void zeroOffsetIsAConfirmedValue() {
        var result = validator.validate(selection(1, 25, 50), found(0, 1, 50,
                new PrintedAnchor(1, 1, "visible_folio")));
        assertThat(result.offsetNumber()).isZero();
        assertThat(result.printedPageOffset()).isZero();
        assertThat(result.issues()).isEmpty();
    }

    private Selection selection(int printedStart, int printedEnd, int total) {
        TopicDetails topic = new TopicDetails();
        topic.setTopicId(12L);
        topic.setTopicName("Real Numbers");
        topic.setPrintedStartPage(printedStart);
        topic.setPrintedEndPage(printedEnd);
        return new Selection(7L, "Book", total, topic, List.of(topic), Set.of(12L));
    }

    private ChapterDetection found(int index, int page, int total, PrintedAnchor... anchors) {
        return foundWithLabel(index, page, total, "1", anchors);
    }

    private ChapterDetection foundWithLabel(int index, int page, int total, String label, PrintedAnchor... anchors) {
        return new ChapterDetection(ChapterDetection.Status.found, index, page, label, null,
                "strong: heading and content", List.of(), List.of(), total, List.of(anchors));
    }
}
