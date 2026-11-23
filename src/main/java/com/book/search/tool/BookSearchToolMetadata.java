package com.book.search.tool;

/** Shared annotation constants keep the complete search contract visible in the generated tool schema. */
public final class BookSearchToolMetadata {
    private BookSearchToolMetadata() { }

    public static final String CRITERIA_DESCRIPTION = """
            Required criteria object; every property inside it is optional. Use {} to list all books with
            default page=0 and size=20, or supply only pagination. Surrounding whitespace is trimmed and
            null, empty and whitespace-only string filters are absent. Use query alone for a broad term;
            explicit fields are only for filters specifically requested by the user and are combined with AND.
            page must be >= 0 and size must be 1..100. Validation is identical for REST and tool execution.
            """;

    public static final String DESCRIPTION = """
            Search or list uploaded books and obtain their stored book metadata and complete topics.
            Call this tool for book-search requests; its required criteria object has optional properties.

            For broad requests such as "find Java books", "books related to Java", or an unqualified
            "contains Java", put the search term only in criteria.query. Do not copy a broad search term
            into several explicit filters: all supplied explicit filters are combined with AND.

            Use bookName only when the user specifies the complete book name or explicitly requests a
            book-name match. Use topicName only for an explicit topic-name match. Use authorName only
            when the user specifies an author. These three name filters match the COMPLETE stored name,
            ignoring case; they do not perform substring, fuzzy, stemming or semantic matching. A requested
            substring match in a name field is outside this contract; do not misrepresent exact-name search
            as that capability. Use bookDetailsContains for requested text within book details and
            topicDetailsContains for requested text within topic details. Details matches are case-insensitive
            literal substrings: %, _ and the escape character are ordinary text, never wildcard instructions.

            General query is OR across five fields: exact complete book name, exact complete author name,
            literal substring of book details, exact complete name of any topic, or literal substring of any
            topic's details, all ignoring case. query=Java does not match Effective Java through its title
            alone, but that book may match through another eligible field. Missing authors/details/topics
            contribute no match and do not prevent a match through another field.

            Explicit filters are ANDed. If query and explicit filters are supplied, the full general-query
            OR group is ANDed with every explicit filter. When topicName and topicDetailsContains are both
            supplied, the SAME topic must satisfy both. The general query may independently match another
            topic or book field. Topic filters select books; every returned book still contains ALL its topics.

            Leave unrequested filters omitted. Do not guess authors, topic names, IDs or other filter values.
            Surrounding whitespace is trimmed; null, empty and whitespace-only filters are absent. For an
            explicit request to list all books, use an empty criteria object or provide only pagination.
            An unsuccessful filtered search returns an empty books array; it never falls back to all books.

            Results contain unique books by persistent bookId, ordered by bookId ascending. Different IDs
            remain distinct even if titles are identical. Deduplication occurs before database pagination.
            page defaults to 0 and must be >= 0; size defaults to 20 and must be 1..100. Invalid pagination
            is rejected, not clamped. A page is not necessarily the entire matching collection: totalElements
            counts distinct matching books and totalPages shows whether more pages exist. An out-of-range
            page has an empty books array but retains those real totals. Return metadata as stored without
            inventing missing authors/descriptions or changing original name casing.

            Representative tool arguments (the outer criteria object is required):
            Broad search: {"criteria":{"query":"Java"}}
            Exact book name: {"criteria":{"bookName":"Java"}}
            Author plus general search: {"criteria":{"authorName":"Asha Sharma","query":"Java"}}
            Topic-details substring: {"criteria":{"topicDetailsContains":"thread pool"}}
            List all, first page: {"criteria":{}}
            """;
}
