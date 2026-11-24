package com.book.metadata.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Captures property presence, including explicit null, without coercing JSON numbers/arrays to text. */
public final class MetadataPatchRequest {
    private static final Set<String> BOOK_FIELDS = Set.of("bookName", "bookType", "authorName", "bookDetails");
    private static final Set<String> TOPIC_FIELDS = Set.of("topicName", "topicDetails");
    private final Map<String, JsonNode> fields = new LinkedHashMap<>();
    private boolean duplicate;

    @JsonAnySetter
    public void capture(String field, JsonNode value) {
        duplicate |= fields.containsKey(field);
        fields.put(field, value);
    }

    public Change bookChange() { return change(BOOK_FIELDS); }
    public Change topicChange() { return change(TOPIC_FIELDS); }

    private Change change(Set<String> allowed) {
        if (duplicate || fields.size() != 1) {
            throw invalid("Provide exactly one editable metadata field.");
        }
        var field = fields.entrySet().iterator().next();
        if (!allowed.contains(field.getKey())) throw invalid("Field '" + field.getKey() + "' is not editable here.");
        JsonNode value = field.getValue();
        if (value != null && !value.isNull() && !value.isString()) {
            throw invalid("Field '" + field.getKey() + "' must be text or null.");
        }
        return new Change(field.getKey(), value == null || value.isNull() ? null : value.asString());
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Change(String field, String value) { }
}
