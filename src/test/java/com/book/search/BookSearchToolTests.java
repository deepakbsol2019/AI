package com.book.search;

import java.util.List;

import com.book.dto.BookSearchCriteria;
import com.book.search.repository.BookSearchQueryRepository;
import com.book.search.repository.BookSearchQueryRepository.SearchPage;
import com.book.search.service.SearchService;
import com.book.search.service.impl.SearchServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the installed Spring AI callback/schema machinery without a model or chat client. */
class BookSearchToolTests {
    private final BookSearchQueryRepository repository = mock(BookSearchQueryRepository.class);
    private final SearchServiceImpl service = new SearchServiceImpl(repository);
    private final JsonMapper json = JsonMapper.builder().build();
    private ToolCallback tool;

    @BeforeEach
    void discoverActualImplementationTool() {
        @SuppressWarnings("unchecked")
        var accessProvider = (org.springframework.beans.factory.ObjectProvider<com.book.tools.ServiceToolAccess>) mock(org.springframework.beans.factory.ObjectProvider.class);
        when(accessProvider.getObject()).thenReturn(mock(com.book.tools.ServiceToolAccess.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "toolAccess", accessProvider);
        when(repository.search(any(BookSearchCriteria.class))).thenReturn(new SearchPage(List.of(), 0L));
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder().toolObjects(service).build().getToolCallbacks();
        assertThat(callbacks).hasSize(1);
        tool = callbacks[0];
    }

    @Test
    void actualSchemaHasRequiredCriteriaAndEightDescribedOptionalProperties() {
        assertThat(tool.getToolDefinition().name()).isEqualTo("searchBooks");
        assertThat(tool.getToolMetadata().returnDirect()).isFalse();
        JsonNode schema = json.readTree(tool.getToolDefinition().inputSchema());
        assertThat(schema.path("required").toString()).isEqualTo("[\"criteria\"]");
        JsonNode criteria = schema.path("properties").path("criteria");
        assertThat(criteria.path("description").asText()).contains("every property inside it is optional");
        JsonNode properties = criteria.path("properties");
        assertThat(properties.size()).isEqualTo(8);
        assertThat(criteria.path("required").size()).isZero();
        for (String property : List.of("bookName", "topicName", "authorName", "bookDetailsContains",
                "topicDetailsContains", "query", "page", "size")) {
            assertThat(properties.has(property)).as("nested property %s", property).isTrue();
            assertThat(properties.path(property).path("description").asText()).as("description for %s", property)
                    .isNotBlank();
        }
        assertThat(properties.path("bookName").path("description").asText()).contains("SUBSTRING", "Effective Java");
        assertThat(properties.path("topicDetailsContains").path("description").asText()).contains("literal", "one topic");
        assertThat(properties.path("query").path("description").asText()).contains("OR", "ONLY here");
        assertThat(properties.path("page").path("description").asText()).contains("default 0");
        assertThat(properties.path("size").path("description").asText()).contains("default 20", "1 through 100");
    }

    @Test
    void executableToolAnnotationLivesOnTheConcreteSharedServiceMethod() throws Exception {
        assertThat(SearchService.class.isAssignableFrom(SearchServiceImpl.class)).isTrue();
        var implementation = SearchServiceImpl.class.getMethod("searchBooksTool", BookSearchCriteria.class);
        assertThat(implementation.getDeclaredAnnotation(Tool.class)).isNotNull();
        assertThat(SearchService.class.getMethod("searchBooks", BookSearchCriteria.class)
                .getDeclaredAnnotation(Tool.class)).isNull();
    }

    @Test
    void toolCannotSearchWhenEndpointAuthorizationFails() {
        var access = mock(com.book.tools.ServiceToolAccess.class);
        @SuppressWarnings("unchecked")
        var provider = (org.springframework.beans.factory.ObjectProvider<com.book.tools.ServiceToolAccess>) mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(access);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(access).require("GET", "/api/search/books");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "toolAccess", provider);
        assertThatThrownBy(() -> tool.call("{\"criteria\":{}}"))
                .isInstanceOfAny(ToolExecutionException.class, ResponseStatusException.class);
        verify(repository, never()).search(any());
    }

    @Test
    void modelFacingDescriptionContainsRoutingRulesAndSchemaCompatibleExamples() {
        String description = tool.getToolDefinition().description();
        assertThat(description).contains("find Java books", "books related to Java", "contains Java",
                "only in criteria.query", "explicit filters are combined with AND", "SAME topic", "ALL its topics",
                "Do not guess", "case-insensitive literal substring matching", "unique books",
                "before database pagination", "totalElements", "totalPages",
                "{\"criteria\":{\"query\":\"Java\"}}",
                "{\"criteria\":{\"bookName\":\"Java\"}}",
                "{\"criteria\":{\"authorName\":\"Asha Sharma\",\"query\":\"Java\"}}",
                "{\"criteria\":{\"topicDetailsContains\":\"thread pool\"}}",
                "{\"criteria\":{}}");
    }

    @Test
    void emptyNestedCriteriaDeserializeAndRunTheSameDefaultListingMethod() {
        JsonNode response = json.readTree(tool.call("{\"criteria\":{}}"));

        assertThat(response.path("books").isArray()).isTrue();
        assertThat(response.path("books").size()).isZero();
        assertThat(response.path("page").asInt()).isZero();
        assertThat(response.path("size").asInt()).isEqualTo(20);
        assertThat(response.path("totalElements").asLong()).isZero();
        assertThat(response.path("totalPages").asLong()).isZero();
        verify(repository).search(new BookSearchCriteria(null, null, null, null, null, null, 0, 20));
    }

    @Test
    void partialNestedCriteriaDeserializeNormalizeAndPreserveExplicitAndGeneralFilters() {
        String response = tool.call("""
                {"criteria":{"authorName":"  Asha Sharma  ","query":"  JAVA  ","page":2,"size":5}}
                """);

        ArgumentCaptor<BookSearchCriteria> captured = ArgumentCaptor.forClass(BookSearchCriteria.class);
        verify(repository).search(captured.capture());
        assertThat(captured.getValue()).isEqualTo(new BookSearchCriteria(null, null, "asha sharma", null, null,
                "java", 2, 5));
        assertThat(json.readTree(response).path("page").asInt()).isEqualTo(2);
        assertThat(json.readTree(response).path("size").asInt()).isEqualTo(5);
    }

    @Test
    void nullableAndBlankOptionalValuesMeanAbsentFiltersWithDefaultPagination() {
        tool.call("""
                {"criteria":{"bookName":null,"topicName":"  ","authorName":"", "query":null,"page":null,"size":null}}
                """);
        verify(repository).search(new BookSearchCriteria(null, null, null, null, null, null, 0, 20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"criteria\":{\"page\":-1}}", "{\"criteria\":{\"size\":0}}",
            "{\"criteria\":{\"size\":101}}", "{\"criteria\":null}", "{}"})
    void toolCallsRejectInvalidOrMissingCriteriaWithoutReturningAnEmptySuccessfulSearch(String arguments) {
        assertThatThrownBy(() -> tool.call(arguments))
                .isInstanceOfSatisfying(ToolExecutionException.class, failure ->
                        assertThat(failure.getCause()).isInstanceOfSatisfying(ResponseStatusException.class,
                                invalid -> assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)));
        verifyNoInteractions(repository);
    }
}
