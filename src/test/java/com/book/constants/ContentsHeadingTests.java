package com.book.constants;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ContentsHeadingTests {
    @ParameterizedTest
    @EnumSource(ContentsHeading.class)
    void recognizesEachSupportedHeading(ContentsHeading heading) {
        assertThat(ContentsHeading.fromLine(heading.getHeading())).contains(heading);
    }

    @ParameterizedTest
    @EnumSource(ContentsHeading.class)
    void recognizesCaseFolioColonAndContinuationDecorations(ContentsHeading heading) {
        assertThat(ContentsHeading.fromLine("vi " + heading.getHeading().toUpperCase(Locale.ROOT) + ":"))
                .contains(heading);
        assertThat(ContentsHeading.fromLine(heading.getHeading() + " (continued) vii")).contains(heading);
        assertThat(ContentsHeading.fromLine("12 " + heading.getHeading() + " 13")).contains(heading);
    }

    @ParameterizedTest
    @EnumSource(ContentsHeading.class)
    void normalizesPdfWhitespace(ContentsHeading heading) {
        String pdfHeading = heading.getHeading().replace(" ", "\u00a0\t\u2003");
        assertThat(ContentsHeading.fromLine("  " + pdfHeading + "  ")).contains(heading);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "The contents of this book explain programming.",
            "Contents: Opening 1; Basics",
            "Brief Contents . . . 3",
            "My Detailed Contents Notes",
            "Table of Contents 1 Opening 1",
            "Contents at a Glance discusses several concepts."
    })
    void rejectsProseMentionsAndContentsEntries(String line) {
        assertThat(ContentsHeading.fromLine(line)).isEmpty();
    }

    @Test
    void nullIsNotAContentsHeading() {
        assertThat(ContentsHeading.fromLine(null)).isEmpty();
    }
}
