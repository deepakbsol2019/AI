package com.book.extraction;

import java.util.List;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

class ContentsPageReferenceParserTests {
    @Test
    @EnabledIfSystemProperty(named = "book.contents.fixture", matches = ".+")
    void actualThinkJavaContentsSuppliesAllTwentyTwoParentReferencesAcrossFivePageBatches() throws Exception {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        try (PDDocument document = Loader.loadPDF(Path.of(System.getProperty("book.contents.fixture")).toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int start = 1; start <= 14; start += 5) {
                stripper.setStartPage(start);
                stripper.setEndPage(Math.min(start + 4, 14));
                parser.accept(stripper.getText(document));
            }
        }

        assertThat(parser.entries()).hasSize(22);
        assertThat(parser.entries()).extracting(ContentsPageReferenceParser.Entry::printedStartPage)
                .containsExactly(1, 17, 33, 51, 71, 89, 107, 127, 147, 167, 183, 201, 217, 233,
                        249, 267, 277, 297, 309, 319, 327, 341);
        assertThat(parser.entries().get(1).topicName()).isEqualTo("Variables and Operators");
        assertThat(parser.entries().get(1).details()).contains("Declaring Variables", "Arithmetic Operators", "Exercises");
        assertThat(parser.entries().get(17).topicNumber()).isEqualTo("A");
        assertThat(parser.entries().get(20).topicNumber()).isEqualTo("D");
        assertThat(parser.entries().getLast().topicName()).isEqualTo("Index");
        assertThat(parser.entries().getLast().printedStartPage()).isEqualTo(341);
    }

    @Test
    void parentStartComesFromItsHeaderAndContinuationChildrenNeverReplaceIt() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("""
                --- PDF PAGE 5 ---
                Contents
                Preface xiii
                1 Computer Programming 1
                1.1 What Is a Computer? . . . . 1
                2 Variables and Operators 17
                2.1 Declaring Variables . . . . . 17
                2.4 Printing Variables . . . . . . 20
                """);
        parser.accept("""
                --- PDF PAGE 6 ---
                vi CONTENTS
                2.5 Arithmetic Operators . . . . . 21
                2.6 Floating-Point Numbers . . . . 23
                3 Input and Output 33
                3.1 The System Class . . . . . . . 33
                """);

        assertThat(parser.entries()).extracting(ContentsPageReferenceParser.Entry::topicNumber)
                .containsExactly("1", "2", "3");
        assertThat(parser.entries()).extracting(ContentsPageReferenceParser.Entry::printedStartPage)
                .containsExactly(1, 17, 33);
        assertThat(parser.entries().getFirst().details()).isEqualTo("What Is a Computer?");
        assertThat(parser.entries().get(1).details()).isEqualTo(
                "Declaring Variables, Printing Variables, Arithmetic Operators, Floating-Point Numbers");
    }

    @Test
    void doesNotReadReferencesBeforeAContentsHeading() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("Copyright 2026\n1 Example 99\nPublished 2025");
        assertThat(parser.entries()).isEmpty();
    }

    @Test
    void preservesWrappedParentAndChildTitlesAcrossBatchesAndPageLabels() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("Table of Contents\n2 Variables and\n");
        parser.accept("vi CONTENTS\nOperators ........ 17\n2.1 Declaring\nVariables ......... 18");
        parser.accept("2.2 Memory Diagrams\n19\n3 Input and Output\n33");

        assertThat(parser.entries()).hasSize(2);
        assertThat(parser.entries().getFirst().topicName()).isEqualTo("Variables and Operators");
        assertThat(parser.entries().getFirst().printedStartPage()).isEqualTo(17);
        assertThat(parser.entries().getFirst().details()).isEqualTo("Declaring Variables, Memory Diagrams");
        assertThat(parser.entries().getLast().printedStartPage()).isEqualTo(33);
    }

    @Test
    void supportsChapterPrefixesAppendicesAndExplicitPrintedRanges() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("""
                Detailed Contents
                Chapter 1 Opening ........ 1-16
                1.1 Basics ........ 1
                Appendix A Graphics ........ 339-348
                A.1 Drawing ........ 340
                Index ........ 349
                """);

        assertThat(parser.entries()).extracting(ContentsPageReferenceParser.Entry::topicName)
                .containsExactly("Opening", "Graphics", "Index");
        assertThat(parser.entries()).extracting(ContentsPageReferenceParser.Entry::printedStartPage)
                .containsExactly(1, 339, 349);
        assertThat(parser.entries()).extracting(ContentsPageReferenceParser.Entry::explicitPrintedEndPage)
                .containsExactly(16, 348, null);
        assertThat(parser.entries().get(1).details()).isEqualTo("Drawing");
    }

    @Test
    void mergesBriefAndDetailedEntriesByStableChapterNumberWithoutDuplicatingDetails() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("Brief Contents\n2 Variables 17\n3 Input and Output 33");
        parser.accept("Detailed Contents\n2 Variables and Operators 17\n2.1 Declaring Variables . . . 17");
        parser.accept("2.1 Declaring Variables . . . 17\n3 Input and Output 33");

        assertThat(parser.entries()).hasSize(2);
        assertThat(parser.entries().getFirst().topicName()).isEqualTo("Variables and Operators");
        assertThat(parser.entries().getFirst().details()).isEqualTo("Declaring Variables");
    }

    @Test
    void supportsUnnumberedParentReferencesAndIndentedChildren() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("""
                Contents at a Glance
                Getting Started ....... 7
                  Installing Java ....... 8
                  Running a Program ....... 9
                Advanced Usage ....... 21
                """);

        assertThat(parser.entries()).extracting(ContentsPageReferenceParser.Entry::topicName)
                .containsExactly("Getting Started", "Advanced Usage");
        assertThat(parser.entries().getFirst().details()).isEqualTo("Installing Java, Running a Program");
    }

    @Test
    void ignoresRomanFrontMatterFoliosInvalidNumbersAndBodySentences() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("""
                Contents
                iv CONTENTS
                Preface xiii
                Copyright 0
                This sentence describes how the examples were compiled in 2025.
                1 Opening ....... 1
                1.1 Basics ....... 2
                Page 5
                v
                """);

        assertThat(parser.entries()).hasSize(1);
        assertThat(parser.entries().getFirst().topicName()).isEqualTo("Opening");
    }

    @Test
    void numberedOrphanChildrenAreRetainedUntilTheirParentHeaderIsAvailable() {
        ContentsPageReferenceParser parser = new ContentsPageReferenceParser();
        parser.accept("Contents\n2.5 Arithmetic Operators . . . 21");
        assertThat(parser.entries()).isEmpty();

        parser.accept("2 Variables and Operators 17");

        assertThat(parser.entries()).hasSize(1);
        assertThat(parser.entries().getFirst().printedStartPage()).isEqualTo(17);
        assertThat(parser.entries().getFirst().details()).isEqualTo("Arithmetic Operators");
    }
}
