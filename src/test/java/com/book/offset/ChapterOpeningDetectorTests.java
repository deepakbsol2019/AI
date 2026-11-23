package com.book.offset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.book.offset.ChapterDetection.Status;

class ChapterOpeningDetectorTests {
    private static final String BODY = "This chapter introduces the natural numbers and explains their properties using several useful mathematical examples.";
    private final ChapterOpeningDetector detector = new ChapterOpeningDetector();

    @Test
    void ignoresContentsAndBodyMentionsBeforeOpeningAndRepeatedMarginHeadersAfterIt() throws Exception {
        byte[] bytes = pdf(
                List.of(line("Contents", 24, 700), line("Real Numbers ......... 1", 12, 650), line("Whole Numbers ......... 17", 12, 625)),
                List.of(line("The next chapter, Real Numbers, introduces important mathematical concepts.", 12, 650)),
                opening(1),
                List.of(line("Real Numbers", 12, 750), line(BODY, 12, 680), line("2", 12, 40)),
                List.of(line("Real Numbers", 12, 750), line(BODY, 12, 680), line("3", 12, 40)));

        ChapterDetection result = detector.detect(bytes, "Real Numbers");

        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(3);
        assertThat(result.pdfPageIndex()).isEqualTo(2);
        assertThat(result.printedPageLabel()).isEqualTo("1");
        assertThat(result.matchedHeading().text()).contains("Real Numbers");
        assertThat(result.matchedHeading().fontSize()).isGreaterThan(20);
        assertThat(result.alternatives()).anySatisfy(candidate -> assertThat(candidate.reasons().toString()).contains("running header"));
    }

    @Test
    void validatesMidPageHeadingWithoutAssumingTopOfPage() throws Exception {
        var result = detector.detect(pdf(List.of(line(BODY, 12, 700), line("Real Numbers", 28, 400), line(BODY, 12, 340))), "REAL  NUMBERS");
        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.matchedHeading().y()).isGreaterThan(300);
    }

    @Test
    void matchesTheTitlePortionOfAnEnlargedIntegerNumberedChapterHeading() throws Exception {
        var result = detector.detect(pdf(List.of(line("Contents", 24, 700),
                        line("1 Introduction ......... 3", 12, 650)),
                List.of(line("1 Introduction", 28, 650), line(BODY, 12, 580), line("3", 12, 40)),
                List.of(line(BODY, 12, 650), line("4", 12, 40))), "Introduction");

        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(2);
        assertThat(result.printedPageLabel()).isEqualTo("3");
        assertThat(result.matchedHeading().text()).isEqualTo("1 Introduction");
        assertThat(result.evidence().toString()).contains("integer chapter label");
    }

    @Test
    void ordinaryNumberedListAndDecimalSubsectionAreNotPromotedToTheChapterTitle() throws Exception {
        var plain = detector.detect(pdf(List.of(line("1 Introduction", 12, 650), line(BODY, 12, 580))), "Introduction");
        assertThat(plain.status()).isEqualTo(Status.not_found);

        var subsection = detector.detect(pdf(List.of(line("1.1 Introduction", 28, 650), line(BODY, 12, 580))), "Introduction");
        assertThat(subsection.status()).isEqualTo(Status.not_found);
    }

    @Test
    void combinesChapterNumberAndMultilineTitleAndAcceptsBodyOnFollowingPage() throws Exception {
        var result = detector.detect(pdf(List.of(line("CHAPTER", 20, 650), line("1", 24, 600),
                        line("REAL", 28, 540), line("NUMBERS", 28, 500)),
                List.of(line(BODY, 12, 650))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(1);
        assertThat(result.matchedHeading().text()).isEqualTo("CHAPTER\n1\nREAL\nNUMBERS");
        assertThat(result.evidence().toString()).contains("Standalone opening-title page");
    }

    @Test
    void physicalPageTwentyOneAndZeroBasedIndexTwentyDescribeTheSameOpening() throws Exception {
        List<List<Draw>> pages = new ArrayList<>();
        for (int i = 0; i < 20; i++) pages.add(List.of());
        pages.add(opening(1));
        var result = detector.detect(pdf(pages), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(21);
        assertThat(result.pdfPageIndex()).isEqualTo(20);
        assertThat(result.pdfPageNumber() - 1).isEqualTo(result.pdfPageIndex());
    }

    @Test
    void aContentsHeadingDoesNotInvalidateAnActualOpeningFartherDownTheSamePage() throws Exception {
        var result = detector.detect(pdf(List.of(line("Contents", 24, 740), line("Other title ......... 1", 12, 700),
                line("Another title ......... 17", 12, 680), line("Chapter 1", 18, 460),
                line("Real Numbers", 28, 410), line(BODY, 12, 340))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.found);
    }

    @Test
    void bodySentenceContainingTopicNameIsNotAFullHeadingMatch() throws Exception {
        var result = detector.detect(pdf(List.of(line("We will study Real Numbers in a later chapter of this book.", 12, 650),
                line(BODY, 12, 630))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.not_found);
        assertThat(result.pdfPageNumber()).isNull();
    }

    @Test
    void isolatedPlainBodyTextIsNotPromotedToAHeadingByWhitespaceAlone() throws Exception {
        var result = detector.detect(pdf(List.of(line("Real Numbers", 12, 650), line(BODY, 12, 580))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.not_found);
        assertThat(result.pdfPageNumber()).isNull();
    }

    @Test
    void repeatedTopMarginTitleIsNotAnOpening() throws Exception {
        var result = detector.detect(pdf(List.of(line("Real Numbers", 12, 750), line(BODY, 12, 680)),
                List.of(line("Real Numbers", 12, 750), line(BODY, 12, 680))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.not_found);
        assertThat(result.alternatives()).isNotEmpty();
    }

    @Test
    void mathematicalExamplesAndTheirAdjacentTitleDoNotBecomeChapterHeadings() throws Exception {
        var result = detector.detect(pdf(List.of(line("Example 1", 18, 650), line("Real Numbers", 18, 620),
                line("x + y = 1", 12, 580), line("y + z = 2", 12, 560), line(BODY, 12, 520))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.not_found);
        assertThat(result.alternatives()).anySatisfy(candidate -> assertThat(candidate.reasons().toString()).contains("example"));
    }

    @Test
    void mathematicalBodyCanConfirmGenuineChapterHeading() throws Exception {
        var result = detector.detect(pdf(List.of(line("Chapter 1", 18, 650), line("Real Numbers", 28, 590),
                line("x + y = 1", 12, 530), line("y + z = 2", 12, 510))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.evidence().toString()).contains("mathematical expressions");
    }

    @Test
    void imageOnlyOrMissingLayoutRequiresOcrInsteadOfInventingAStart() throws Exception {
        var result = detector.detect(pdf(List.<Draw>of()), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.insufficient_data);
        assertThat(result.pdfPageIndex()).isNull();
        assertThat(result.matchedHeading()).isNull();
    }

    @Test
    void titleWithoutBodyInsideLookAheadRemainsProvisional() throws Exception {
        var limited = new ChapterOpeningDetector(new ChapterOpeningDetector.Settings(0, 1.18f, 10, .15f, 80, 4));
        var result = limited.detect(pdf(List.of(line("Chapter 1", 18, 650), line("Real Numbers", 28, 590)),
                List.of(line(BODY, 12, 650))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.insufficient_data);
        assertThat(result.pdfPageNumber()).isNull();
        assertThat(result.alternatives()).anySatisfy(candidate -> assertThat(candidate.outcome()).isEqualTo("provisional"));
    }

    @Test
    void unresolvedFullOpeningLayoutsAreAmbiguous() throws Exception {
        var result = detector.detect(pdf(opening(null), opening(null)), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.ambiguous);
        assertThat(result.pdfPageNumber()).isNull();
        assertThat(result.alternatives()).hasSize(2);
    }

    @Test
    void duplicateOpeningEditionsWithSeparatePrintedOneAnchorsRemainAmbiguous() throws Exception {
        var result = detector.detect(pdf(opening(1),
                List.of(line(BODY, 12, 650), line("2", 12, 40)), opening(1),
                List.of(line(BODY, 12, 650), line("2", 12, 40))), "Real Numbers");

        assertThat(result.anchors()).filteredOn(anchor -> anchor.printedPageNumber() == 1)
                .extracting(ChapterDetection.PrintedAnchor::pdfPageNumber).containsExactly(1, 3);
        assertThat(result.status()).isEqualTo(Status.ambiguous);
        assertThat(result.pdfPageIndex()).isNull();
        assertThat(result.pdfPageNumber()).isNull();
        assertThat(result.alternatives()).filteredOn(candidate -> candidate.outcome().equals("plausible_opening"))
                .hasSize(2);
    }

    @Test
    void laterRepriseWithContinuingPrintedNumbersDoesNotDisplacePrintedOneOpening() throws Exception {
        var result = detector.detect(pdf(opening(1),
                List.of(line(BODY, 12, 650), line("2", 12, 40)), opening(3),
                List.of(line(BODY, 12, 650), line("4", 12, 40))), "Real Numbers");

        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(1);
        assertThat(result.alternatives()).anySatisfy(candidate -> {
            assertThat(candidate.outcome()).isEqualTo("later_reprise");
            assertThat(candidate.heading().pdfPageNumber()).isEqualTo(3);
        });
    }

    @Test
    void competingOpeningsWithTheSameSourcePrintedPageThreeRemainAmbiguous() throws Exception {
        var result = detector.detect(pdf(opening(3),
                List.of(line(BODY, 12, 650), line("4", 12, 40)), opening(3),
                List.of(line(BODY, 12, 650), line("4", 12, 40))), "Real Numbers", 3);

        assertThat(result.anchors()).filteredOn(anchor -> anchor.printedPageNumber() == 3)
                .extracting(ChapterDetection.PrintedAnchor::pdfPageNumber).containsExactly(1, 3);
        assertThat(result.status()).isEqualTo(Status.ambiguous);
        assertThat(result.pdfPageNumber()).isNull();
        assertThat(result.alternatives()).filteredOn(candidate -> candidate.outcome().equals("plausible_opening"))
                .hasSize(2);
    }

    @Test
    void sourcePrintedPageThreeOpeningIsCorroboratedBeforeLaterPrintedFiveReprise() throws Exception {
        var result = detector.detect(pdf(opening(3),
                List.of(line(BODY, 12, 650), line("4", 12, 40)), opening(5),
                List.of(line(BODY, 12, 650), line("6", 12, 40))), "Real Numbers", 3);

        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(1);
        assertThat(result.printedPageLabel()).isEqualTo("3");
        assertThat(result.alternatives()).anySatisfy(candidate -> {
            assertThat(candidate.outcome()).isEqualTo("later_reprise");
            assertThat(candidate.reasons().toString()).contains("printed page 3");
        });
    }

    @Test
    void missingPrintedStartHintDoesNotInventAnAnchorToResolveMultipleOpenings() throws Exception {
        var result = detector.detect(pdf(opening(3),
                List.of(line(BODY, 12, 650), line("4", 12, 40)), opening(5),
                List.of(line(BODY, 12, 650), line("6", 12, 40))), "Real Numbers", null);

        assertThat(result.status()).isEqualTo(Status.ambiguous);
        assertThat(result.pdfPageNumber()).isNull();
    }

    @Test
    void conservativeOcrSubstitutionRequiresAdditionalHeadingEvidence() throws Exception {
        var result = detector.detect(pdf(List.of(line("Chapter 1", 18, 650), line("Real Num8ers", 28, 590),
                line(BODY, 12, 520))), "Real Numbers");
        assertThat(result.status()).isEqualTo(Status.found);
        assertThat(result.confidence()).startsWith("moderate:");

        var uncertain = detector.detect(pdf(List.of(line("Real Num8ers", 28, 590), line(BODY, 12, 520))), "Real Numbers");
        assertThat(uncertain.status()).isEqualTo(Status.insufficient_data);
        assertThat(uncertain.pdfPageNumber()).isNull();
    }

    @Test
    @EnabledIfSystemProperty(named = "book.contents.fixture", matches = ".+")
    void detectsActualThinkJavaChapterOpeningRatherThanItsContentsEntry() throws Exception {
        var result = detector.detect(Files.readAllBytes(Path.of(System.getProperty("book.contents.fixture"))), "Computer Programming");
        assertThat(result.status()).withFailMessage(result.toString()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(23);
        assertThat(result.pdfPageIndex()).isEqualTo(22);
        assertThat(result.printedPageLabel()).isEqualTo("1");
        assertThat(result.anchors()).anySatisfy(anchor -> {
            assertThat(anchor.pdfPageNumber()).isEqualTo(23);
            assertThat(anchor.printedPageNumber()).isEqualTo(1);
        });
        assertThat(result.anchors()).filteredOn(anchor -> anchor.pdfPageNumber() >= 23)
                .allSatisfy(anchor -> assertThat(anchor.pdfPageNumber() - anchor.printedPageNumber()).isEqualTo(22));
    }

    @Test
    @EnabledIfSystemProperty(named = "book.algorithms.fixture", matches = ".+")
    void detectsActualAlgorithmsNumberedOpeningAndPreservesPrintedPageThree() throws Exception {
        var result = detector.detect(Files.readAllBytes(Path.of(System.getProperty("book.algorithms.fixture"))), "Introduction", 3);
        assertThat(result.status()).withFailMessage(result.toString()).isEqualTo(Status.found);
        assertThat(result.pdfPageNumber()).isEqualTo(6);
        assertThat(result.pdfPageIndex()).isEqualTo(5);
        assertThat(result.printedPageLabel()).isEqualTo("3");
        assertThat(result.matchedHeading().text()).isEqualTo("1 Introduction");
        assertThat(result.anchors()).filteredOn(anchor -> anchor.pdfPageNumber() >= 6)
                .allSatisfy(anchor -> assertThat(anchor.pdfPageNumber() - anchor.printedPageNumber()).isEqualTo(3));
    }

    @Test
    void normalizationMatchesEquivalentPunctuationButNeverASubstring() {
        assertThat(ChapterTitleMatcher.match("Introduction: Real-Numbers", "Introduction — Real Numbers")).isEqualTo(ChapterTitleMatcher.Match.exact);
        assertThat(ChapterTitleMatcher.match("This is Real Numbers today", "Real Numbers")).isEqualTo(ChapterTitleMatcher.Match.none);
        assertThat(ChapterTitleMatcher.titlePortion("CHAPTER\n1\nREAL NUMBERS")).isEqualTo("REAL NUMBERS");
        assertThat(ChapterTitleMatcher.numberedTitlePortion("1 Introduction")).isEqualTo("Introduction");
        assertThat(ChapterTitleMatcher.numberedTitlePortion("1.1 Introduction")).isEqualTo("1.1 Introduction");
    }

    private static List<Draw> opening(Integer printed) {
        List<Draw> result = new ArrayList<>(List.of(line("Chapter 1", 18, 650), line("Real Numbers", 28, 590), line(BODY, 12, 520)));
        if (printed != null) result.add(line(printed.toString(), 12, 40));
        return result;
    }

    private static Draw line(String text, int size, int y) { return new Draw(text, size, y); }
    private record Draw(String text, int size, int y) { }

    @SafeVarargs
    private static byte[] pdf(List<Draw>... pages) throws Exception { return pdf(List.of(pages)); }

    private static byte[] pdf(List<List<Draw>> pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (List<Draw> draw : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    for (Draw line : draw) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), line.size());
                        stream.newLineAtOffset(50, line.y());
                        stream.showText(line.text());
                        stream.endText();
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
