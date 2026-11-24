package com.book.extraction;

import java.util.List;
import org.junit.jupiter.api.Test;

import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfBatchExtraction.AuthorData;
import com.book.dto.PdfBatchExtraction.BookData;
import com.book.dto.PdfBatchExtraction.TopicData;
import com.book.entity.TopicDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionAccumulatorTests {
    @Test
    void continuationUsesOriginalParentStartRatherThanNextBatchFirstSubtopicPage() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(
                topic("Variables and Operators", "Declaring Variables, Printing Variables", 17, 20)),
                true);
        accumulator.merge(batch(
                topic(" variables and operators ", "Printing Variables, Arithmetic Operators", 21, 31),
                topic("Input and Output", "The System Class", 33, 40)), false);

        var partial = accumulator.snapshot().topicDetails();
        assertThat(partial).hasSize(2);
        assertThat(partial.getFirst().printedStartPage()).isEqualTo(17);
        assertThat(partial.getFirst().details())
                .isEqualTo("Declaring Variables, Printing Variables, Arithmetic Operators");
        assertThat(partial).allSatisfy(value -> assertThat(value.printedEndPage()).isNull());

        var topics = finish(accumulator);
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(17, 33);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(32, null);
    }

    @Test
    void calculatesEndsOnlyAfterAllBatchesHaveSuppliedTheFollowingParent() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(
                topic("Computer Programming", "Basics", 1, 4),
                topic("Variables", "Declaring Variables", 17, 20)), true);

        assertThat(accumulator.snapshot().topicDetails())
                .allSatisfy(value -> assertThat(value.printedEndPage()).isNull());
        accumulator.merge(batch(topic("Variables", "Operators", 21, 24)), false);
        assertThat(accumulator.snapshot().topicDetails())
                .allSatisfy(value -> assertThat(value.printedEndPage()).isNull());
        accumulator.merge(batch(topic("Input and Output", "Reading Input", 33, 48)), false);

        var topics = finish(accumulator);
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(1, 17, 33);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(16, 32, null);
        assertThat(topics.get(1).getDetails()).isEqualTo("Declaring Variables, Operators");
    }

    @Test
    void neverUsesGuessedFinalTopicEndOrPdfPageCountAsPrintedEnd() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(topic("Final Chapter", "Review, Exercises", 101, 120)), true);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(1);
        assertThat(topics.getFirst().getPrintedStartPage()).isEqualTo(101);
        assertThat(topics.getFirst().getPrintedEndPage()).isNull();
    }

    @Test
    void initializesPhysicalPagesAndPdfPathToNullEvenWhenModelSuppliesThem() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(new PdfBatchExtraction(new AuthorData("Author"),
                new BookData("Book", "Description"), List.of(
                        new TopicData("Opening", "Basics", 12, 27, 1, 16),
                        new TopicData("Next Chapter", "Examples", 28, 200, 17, 200)),
                true, 12, false, true), true);

        var topics = finish(accumulator);

        assertThat(topics).allSatisfy(value -> {
            assertThat(value.getStartPage()).isNull();
            assertThat(value.getEndPage()).isNull();
            assertThat(value.getPdfPath()).isNull();
        });
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(1, 17);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(16, null);
    }

    @Test
    void preservesDistinctSameNamedParentsWithinOneBatch() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(
                topic("Practice", "Basic exercises", 1, 4),
                topic("Practice", "Advanced exercises", 17, 25)), true);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(1, 17);
        assertThat(topics).extracting(TopicDetails::getDetails)
                .containsExactly("Basic exercises", "Advanced exercises");
    }

    @Test
    void distinctChapterNumbersPreserveRepeatedTitlesAcrossBatches() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(new TopicData("Practice", "Basics", null, null, 1, null, "1", null)), true);
        accumulator.merge(batch(new TopicData("Practice", "Advanced", null, null, 17, null, "2", null)), false);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(1, 17);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(16, null);
    }

    @Test
    void explicitContinuationCanAppendToLastParentWithoutRepeatingItsHeading() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(new TopicData("Opening", "Basics", null, null, 1, 3, "1", null)), true);
        accumulator.merge(batch(new TopicData(null, "Setup", null, null, 5, 7, null, true)), false);
        accumulator.merge(batch(new TopicData("Reworded Opening", "Examples", null, null, 9, 11, "1", true)),
                false);

        var partial = accumulator.snapshot().topicDetails();
        assertThat(partial).hasSize(1);
        assertThat(partial.getFirst().topicName()).isEqualTo("Opening");
        assertThat(partial.getFirst().printedStartPage()).isEqualTo(1);
        assertThat(partial.getFirst().details()).isEqualTo("Basics, Setup, Examples");
        assertThat(partial.getFirst().printedEndPage()).isNull();

        var topics = finish(accumulator);
        assertThat(topics).hasSize(1);
        assertThat(topics.getFirst().getPrintedStartPage()).isEqualTo(1);
        assertThat(topics.getFirst().getPrintedEndPage()).isNull();
    }

    @Test
    void explicitNewParentFlagPreventsSameTitleContinuationHeuristic() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(topic("Practice", "Basics", 1, 3)), true);
        accumulator.merge(batch(new TopicData("Practice", "Advanced", null, null, 17, 20, null, false)),
                false);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(1, 17);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(16, null);
    }

    @Test
    void distinctNumberedParentsSharingOnePrintedPageHaveAnInclusiveSinglePageRange() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(
                new TopicData("Practice", "Basics", null, null, 5, null, "1", false),
                new TopicData("Practice", "Advanced", null, null, 5, null, "2", false)), true);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(5, 5);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(5, null);
    }

    @Test
    void rejectsDecreasingPrintedParentStartsInsteadOfMaskingThem() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(topic("Opening", "Basics", 17, null),
                topic("Next Chapter", "Examples", 1, null)), true);

        assertThatThrownBy(() -> finish(accumulator))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void missingImmediateNextParentStartDoesNotUseAStillLaterChapterToGuessTheEnd() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(
                topic("Opening", "Basics", 1, null),
                topic("Unknown Start", "Examples", null, null),
                topic("Later Chapter", "Review", 33, null)), true);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(3);
        assertThat(topics.getFirst().getPrintedStartPage()).isEqualTo(1);
        assertThat(topics.getFirst().getPrintedEndPage()).isNull();
        assertThat(topics.get(1).getPrintedStartPage()).isNull();
    }

    @Test
    void noIndexFallbackCreatesOnlyTheExistingReservedOffsetStorageRow() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(new PdfBatchExtraction(new AuthorData("Author"), new BookData("Book", "Description"),
                List.of(new TopicData("Model fallback", "Ignored", 1, 372, 1, 372)),
                false, null, false, false), true);

        var topics = finish(accumulator);

        assertThat(topics).singleElement().satisfies(offset -> {
            assertThat(offset.getTopicName()).isEqualTo("Book_offset");
            assertThat(offset.getDetails()).isEqualTo("Book_offset detail");
            assertThat(offset.getStartPage()).isEqualTo(1);
            assertThat(offset.getEndPage()).isEqualTo(372);
            assertThat(offset.getPrintedStartPage()).isNull();
            assertThat(offset.getPrintedEndPage()).isNull();
            assertThat(offset.getPdfPath()).isNull();
        });
        assertThat(accumulator.snapshot().topicDetails()).isEmpty();
    }

    @Test
    void firstNonblankBookMetadataIsRetainedAndBookTypeDefaultsToUnknown() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.captureMetadata(new PdfBatchExtraction(new AuthorData("Author"),
                new BookData("Book", "  Original description.  ", "Java Programming"),
                List.of(), false, null, false, false), true);
        accumulator.captureMetadata(new PdfBatchExtraction(new AuthorData("Replacement"),
                new BookData("Replacement", "Regenerated description", "Story"),
                List.of(), false, null, false, false), true);
        var result = accumulator.toEntities("id", "source.pdf", 5);
        assertThat(result.bookDetails().getBookName()).isEqualTo("Book");
        assertThat(result.authorDetails().getAuthorName()).isEqualTo("Author");
        assertThat(result.bookDetails().getBookDetails()).isEqualTo("  Original description.  ");
        assertThat(result.bookDetails().getBookType()).isEqualTo("Java Programming");
        assertThat(result.topicDetails()).singleElement().satisfies(offset -> {
            assertThat(offset.getTopicName()).isEqualTo("Book_offset");
            assertThat(offset.getDetails()).isEqualTo("Book_offset detail");
            assertThat(offset.getBookDetails()).isSameAs(result.bookDetails());
        });
        assertThat(new ExtractionAccumulator().toEntities("id", "source.pdf", 5)
                .bookDetails().getBookType()).isEqualTo("Unknown");
    }

    private static PdfBatchExtraction batch(TopicData... topics) {
        return new PdfBatchExtraction(new AuthorData("Author"), new BookData("Book", "Description"),
                List.of(topics), true, null, true, false);
    }

    @Test
    void printedReferencesComeFromContentsEvenWhenAiOmitsEveryPageNumber() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(new TopicData("Variables", "Model details", null, null, null, null, "2", null)),
                true);
        accumulator.applyContentsReferences(List.of(
                new ContentsPageReferenceParser.Entry("2", "Variables and Operators", 17, null, "Declaring Variables"),
                new ContentsPageReferenceParser.Entry("3", "Input and Output", 33, null, "Reading Input"),
                new ContentsPageReferenceParser.Entry(null, "Index", 341, null, null)));
        accumulator.setLastPrintedPage(349);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(3);
        assertThat(topics).extracting(TopicDetails::getPrintedStartPage).containsExactly(17, 33, 341);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(32, 340, 349);
        assertThat(topics.getFirst().getDetails()).isEqualTo("Declaring Variables, Model details");
        assertThat(topics).allSatisfy(topic -> {
            assertThat(topic.getStartPage()).isNull();
            assertThat(topic.getEndPage()).isNull();
        });
    }

    @Test
    void actualParentReferenceCorrectsWrongModelPageAndKeepsContinuedSubtopics() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(new TopicData("Variables", "Declaring Variables", null, null, 21, 28, "2", false)),
                true);
        accumulator.applyContentsReferences(List.of(
                new ContentsPageReferenceParser.Entry("2", "Variables and Operators", 17, null, "Declaring Variables")));
        accumulator.merge(batch(new TopicData("Variables", "Arithmetic Operators", null, null, 25, 31, "2", true),
                new TopicData("Input", "Reading", null, null, null, null, "3", false)), false);
        accumulator.applyContentsReferences(List.of(
                new ContentsPageReferenceParser.Entry("2", "Variables and Operators", 17, null,
                        "Declaring Variables, Arithmetic Operators"),
                new ContentsPageReferenceParser.Entry("3", "Input and Output", 33, null, "Reading")));

        var topics = finish(accumulator);

        assertThat(topics).hasSize(2);
        assertThat(topics.getFirst().getPrintedStartPage()).isEqualTo(17);
        assertThat(topics.getFirst().getPrintedEndPage()).isEqualTo(32);
        assertThat(topics.getFirst().getDetails()).isEqualTo("Declaring Variables, Arithmetic Operators");
    }

    @Test
    void explicitRangePrintedInContentsIsPreservedInsteadOfExtendingAcrossAGap() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(), true);
        accumulator.applyContentsReferences(List.of(
                new ContentsPageReferenceParser.Entry("1", "Opening", 1, 12, null),
                new ContentsPageReferenceParser.Entry("2", "Next", 17, 25, null)));
        accumulator.setLastPrintedPage(349);

        var topics = finish(accumulator);

        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(12, 25);
    }

    @Test
    void unknownParentStartIsNotInventedFromAContinuedSubtopicReference() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(new TopicData("Variables", "Declaring Variables", null, null,
                null, null, "2", false)), true);
        accumulator.merge(batch(new TopicData("Variables", "Arithmetic Operators", null, null,
                21, 31, "2", true), new TopicData("Input", "Reading", null, null,
                33, null, "3", false)), false);

        var topics = finish(accumulator);

        assertThat(topics.getFirst().getPrintedStartPage()).isNull();
        assertThat(topics.getFirst().getPrintedEndPage()).isEqualTo(32);
        assertThat(topics.getFirst().getDetails()).isEqualTo("Declaring Variables, Arithmetic Operators");
    }

    @Test
    void explicitNewParentFlagAppliesToEveryEntryEvenWithSameTitleAndPage() {
        ExtractionAccumulator accumulator = new ExtractionAccumulator();
        accumulator.merge(batch(
                new TopicData("Practice", "Basics", null, null, 1, null, null, false),
                new TopicData("Practice", "Advanced", null, null, 1, null, null, false)), true);

        var topics = finish(accumulator);

        assertThat(topics).hasSize(2);
        assertThat(topics).extracting(TopicDetails::getPrintedEndPage).containsExactly(1, null);
    }

    private static TopicData topic(String name, String details, Integer start, Integer partialEnd) {
        return new TopicData(name, details, null, null, start, partialEnd);
    }

    private static List<TopicDetails> finish(ExtractionAccumulator accumulator) {
        accumulator.markContentsComplete();
        return accumulator.toEntities("extraction-id", "book.pdf", 372).topicDetails();
    }
}
