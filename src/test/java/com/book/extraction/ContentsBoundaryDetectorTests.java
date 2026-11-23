package com.book.extraction;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.book.constants.ContentsHeading;

import static org.assertj.core.api.Assertions.assertThat;

class ContentsBoundaryDetectorTests {
    @ParameterizedTest
    @EnumSource(ContentsHeading.class)
    void everyEnumHeadingStartsContentsAndRetainsHeaderlessSubtopicsUntilPreface(ContentsHeading heading) {
        ContentsBoundaryDetector detector = new ContentsBoundaryDetector();
        var first = detector.scanPage(5, heading.getHeading()
                + "\n1 Opening . . . . . 1\n1.1 Basics . . . . . 1", List.of());
        assertThat(first.contentsSeen()).isTrue();
        assertThat(first.boundaryFound()).isFalse();

        var continued = detector.scanPage(6, "1.2 Setup . . . . . 3\n2 Advanced . . . . . 17",
                List.of("Opening"));
        assertThat(continued.contentsSeen()).isTrue();
        assertThat(continued.boundaryFound()).isFalse();
        assertThat(continued.textBeforeBoundary()).contains("Setup", "Advanced");

        var followingSection = detector.scanPage(7, "Preface\nThe following section begins here.", List.of());
        assertThat(followingSection.boundaryFound()).isTrue();
        assertThat(followingSection.textBeforeBoundary()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ContentsHeading.class)
    void repeatedEnumHeadingWithFolioContinuesContentsInsteadOfEndingIt(ContentsHeading heading) {
        ContentsBoundaryDetector detector = new ContentsBoundaryDetector();
        detector.scanPage(5, heading.getHeading() + "\n1 Opening . . . 1", List.of());

        var repeated = detector.scanPage(6,
                "vi " + heading.getHeading().toUpperCase(Locale.ROOT) + " (continued)"
                        + "\n1.2 Setup . . . . . 3", List.of("Opening"));

        assertThat(repeated.contentsSeen()).isTrue();
        assertThat(repeated.boundaryFound()).isFalse();
        assertThat(repeated.textBeforeBoundary()).contains("Setup");
    }

    @Test
    void retainsThinkJavaRomanHeadersAndContentsEntriesAcrossPages() {
        ContentsBoundaryDetector detector = new ContentsBoundaryDetector();
        assertThat(detector.scanPage(5, "Contents\nPreface xiii\n1 Computer Programming 1",
                List.of()).boundaryFound()).isFalse();

        var pageSix = detector.scanPage(6, """
                vi CONTENTS
                2.5 Arithmetic Operators . . . . . . . . . . . 21
                2.6 Floating-Point Numbers . . . . . . . . . . 23
                3 Input and Output 33
                3.1 The System Class . . . . . . . . . . . . . 33
                """, List.of("Computer Programming", "Input and Output"));
        var pageSeven = detector.scanPage(7, """
                CONTENTS vii
                4.4 Multiple Parameters . . . . . . . . . . . . 55
                5 Conditionals and Logic 71
                """, List.of("Conditionals and Logic"));

        assertThat(pageSix.contentsSeen()).isTrue();
        assertThat(pageSix.boundaryFound()).isFalse();
        assertThat(pageSix.textBeforeBoundary()).contains("Arithmetic Operators", "3 Input and Output 33");
        assertThat(pageSeven.boundaryFound()).isFalse();
    }

    @Test
    void stopsBeforePrefaceAndAllFollowingPages() {
        ContentsBoundaryDetector detector = started();

        var boundary = detector.scanPage(13, "xiii\nPreface\nThis book introduces programming.",
                List.of());

        assertThat(boundary.boundaryFound()).isTrue();
        assertThat(boundary.textBeforeBoundary()).isEmpty();
        assertThat(detector.scanPage(14, "More preface text", List.of()).textBeforeBoundary()).isEmpty();
        assertThat(detector.scanPage(14, "More preface text", List.of()).boundaryFound()).isTrue();
    }

    @Test
    void retainsPartialContentsPageBeforeFollowingSectionHeading() {
        var scan = started().scanPage(8, """
                CONTENTS viii
                2.4 Operators . . . . . . . . . . . . . 25
                2.5 Exercises . . . . . . . . . . . . . 29
                Foreword
                These are words from the following section.
                """, List.of());

        assertThat(scan.boundaryFound()).isTrue();
        assertThat(scan.textBeforeBoundary()).contains("2.4 Operators", "2.5 Exercises")
                .doesNotContain("Foreword", "following section");
    }

    @Test
    void retainsHeaderlessContinuedSubtopicsAndWrappedReferences() {
        ContentsBoundaryDetector detector = started();
        var scan = detector.scanPage(6, """
                Operators for Strings . . . . . . . . . 25
                Introduction
                33
                3.1 System Class . . . . . . . . . . . 34
                """, List.of("Introduction"));

        assertThat(scan.boundaryFound()).isFalse();
        assertThat(scan.textBeforeBoundary()).contains("Introduction", "33", "Operators for Strings");
    }

    @Test
    void identifiesChapterAndKnownTopicBodyHeadingsWithoutPageReferences() {
        assertThat(started().scanPage(21, "Chapter 1\nComputer Programming\nActual chapter body.",
                List.of("Computer Programming")).boundaryFound()).isTrue();
        assertThat(started().scanPage(21, "Computer Programming\nActual chapter body.",
                List.of("Computer Programming")).boundaryFound()).isTrue();
        assertThat(started().scanPage(21, "1 Computer Programming\nActual chapter body.",
                List.of("Computer Programming")).boundaryFound()).isTrue();
    }

    @Test
    void doesNotConsiderFrontMatterBeforeContentsABoundary() {
        ContentsBoundaryDetector detector = new ContentsBoundaryDetector();
        var frontMatter = detector.scanPage(2, "Preface\nSome front matter.", List.of());
        assertThat(frontMatter.contentsSeen()).isFalse();
        assertThat(frontMatter.boundaryFound()).isFalse();
        assertThat(frontMatter.textBeforeBoundary()).contains("Preface");
    }

    @Test
    void blankPagesAndPageLabelsNeverEndContents() {
        ContentsBoundaryDetector detector = started();
        assertThat(detector.scanPage(6, " \n\n", List.of()).boundaryFound()).isFalse();
        assertThat(detector.scanPage(7, "vii\n", List.of()).boundaryFound()).isFalse();
        assertThat(detector.scanPage(8, "Page 8\n", List.of()).boundaryFound()).isFalse();
        assertThat(detector.scanPage(9, "Contents (continued)\nTopic . . . 10", List.of()).boundaryFound()).isFalse();
    }

    @Test
    void detectsIndexAndContentsLabelsWithoutAcceptingInlineTopicTextAsAHeading() {
        ContentsBoundaryDetector index = new ContentsBoundaryDetector();
        assertThat(index.scanPage(5, "INDEX\nOpening 1", List.of()).contentsSeen()).isTrue();
        assertThat(index.scanPage(6, "Introduction\nBody text.", List.of()).boundaryFound()).isTrue();
        ContentsBoundaryDetector inline = new ContentsBoundaryDetector();
        assertThat(inline.scanPage(5, "Contents: Opening 1; Basics", List.of()).contentsSeen()).isFalse();
        assertThat(inline.scanPage(6, "Preface", List.of()).boundaryFound()).isFalse();
        assertThat(inline.scanPage(7, "Contents:\nOpening 1", List.of()).contentsSeen()).isTrue();
    }

    @Test
    void initialContentsHeadingIsRetainedButLaterHeaderAndFolioOnlyPagesAreSkipped() {
        ContentsBoundaryDetector detector = new ContentsBoundaryDetector();
        assertThat(detector.scanPage(5, "Contents", List.of()).textBeforeBoundary()).isEqualTo("Contents");
        var repeated = detector.scanPage(6, "vi CONTENTS\nvi", List.of());
        assertThat(repeated.textBeforeBoundary()).isEmpty();
        assertThat(repeated.contentsSeen()).isTrue();
        assertThat(repeated.boundaryFound()).isFalse();
        assertThat(detector.scanPage(7, "Page 7\n", List.of()).textBeforeBoundary()).isEmpty();
        var nextSection = detector.scanPage(8, "viii\nPreface\nBody of the preface.", List.of());
        assertThat(nextSection.boundaryFound()).isTrue();
        assertThat(nextSection.textBeforeBoundary()).isEmpty();
    }

    @Test
    void unknownHeadingRequiresSupportingProse() {
        assertThat(started().scanPage(9, "Using This Volume\nThis section explains how to read the book and complete all the practice activities.",
                List.of()).boundaryFound()).isTrue();
        assertThat(started().scanPage(9, "A Wrapped Topic Name\ncontinued subtopic text",
                List.of()).boundaryFound()).isFalse();
    }

    private ContentsBoundaryDetector started() {
        ContentsBoundaryDetector detector = new ContentsBoundaryDetector();
        detector.scanPage(5, "Table of Contents\n1 Opening . . . . 1", List.of());
        return detector;
    }
}
