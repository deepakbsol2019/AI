package com.book.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.ToolParam;

/** Optional search inputs shared by REST model binding and the searchBooks tool. */
public record BookSearchCriteria(
        @Nullable
        @Schema(nullable = true)
        @ToolParam(required = false, description = "Case-insensitive literal SUBSTRING of the stored book name. Java matches Effective Java, Java Programming and JavaScript. Percent, underscore and the escape character are literal text. Use this for an explicitly requested title filter; use query for a broad request.")
        String bookName,

        @Nullable
        @Schema(nullable = true)
        @ToolParam(required = false, description = "Case-insensitive literal SUBSTRING of a stored topic name. Set for an explicit topic-name filter. If topicDetailsContains is also supplied, the SAME topic must satisfy both filters. The returned book still contains all topics.")
        String topicName,

        @Nullable
        @Schema(nullable = true)
        @ToolParam(required = false, description = "Case-insensitive literal SUBSTRING of the stored credited author name. Set only when the user specifies an author filter. Leave omitted rather than guessing an author; preserve the project's stored author representation.")
        String authorName,

        @Nullable
        @Schema(nullable = true)
        @ToolParam(required = false, description = "Case-insensitive literal SUBSTRING within book details, only when the user asks for text in book details. Percent signs, underscores and escape characters are literal text, not wildcards. Combined with all other supplied explicit filters using AND.")
        String bookDetailsContains,

        @Nullable
        @Schema(nullable = true)
        @ToolParam(required = false, description = "Case-insensitive literal SUBSTRING within topic details, only when the user asks for text in topic details. Percent signs, underscores and escape characters are literal text. If topicName is supplied, one topic must satisfy both; all topics are still returned for each matching book.")
        String topicDetailsContains,

        @Nullable
        @Schema(nullable = true)
        @ToolParam(required = false, description = "General search term for broad requests such as find Java books, books related to Java, or an unqualified contains Java. Search with OR across literal substrings of book, author and topic names and book and topic details, ignoring case. Put a broad term ONLY here, not in several explicit filters. If explicit filters are also requested, the whole query OR group is ANDed with those filters.")
        String query,

        @Nullable
        @Schema(nullable = true, minimum = "0")
        @ToolParam(required = false, description = "Zero-based page number, default 0 when omitted or null. Must be at least 0; invalid values are rejected rather than clamped. Books are ordered by persistent bookId ascending. A page beyond the results returns an empty books array while retaining the actual matching totals.")
        Integer page,

        @Nullable
        @Schema(nullable = true, minimum = "1", maximum = "100")
        @ToolParam(required = false, description = "Number of distinct books per page, tool default 20 when omitted or null. The REST homepage listing instead defaults to 5. Must be from 1 through 100; invalid values are rejected rather than clamped. Results are deduplicated by bookId before pagination, and every returned book includes its complete topic list.")
        Integer size) { }
