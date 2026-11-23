package com.book.constants;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Supported titles used to identify a PDF's contents section. */
public enum ContentsHeading {
    CONTENTS("Contents"),
    TABLE_OF_CONTENTS("Table of Contents"),
    BRIEF_CONTENTS("Brief Contents"),
    DETAILED_CONTENTS("Detailed Contents"),
    CONTENTS_AT_A_GLANCE("Contents at a Glance");

    private static final String PAGE_LABEL = "(?:[0-9]+|[ivxlcdm]+)";
    private final String heading;
    private final Pattern linePattern;

    ContentsHeading(String heading) {
        this.heading = heading;
        this.linePattern = Pattern.compile("^(?:" + PAGE_LABEL + "\\s+)?"
                + Pattern.quote(heading.toLowerCase(Locale.ROOT))
                + "\\s*:?\\s*(?:\\(?continued\\)?)?(?:\\s+" + PAGE_LABEL + ")?$",
                Pattern.CASE_INSENSITIVE);
    }

    public String getHeading() {
        return heading;
    }

    public static Optional<ContentsHeading> fromLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(line, Normalizer.Form.NFKC)
                .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
        return Arrays.stream(values()).filter(value -> value.linePattern.matcher(normalized).matches()).findFirst();
    }

    public static String headingList() {
        return Arrays.stream(values()).map(ContentsHeading::getHeading).collect(Collectors.joining(", "));
    }
}
