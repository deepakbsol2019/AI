package com.book.extraction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfPageBatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.ExpectedCount.once;

class OllamaExtractionRequestTests {
    private static final String BASE_URL = "http://ollama.test";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void firstBatchSendsNativeFullSchemaAndAllowsUnknownPhysicalPages() {
        verifyNativeRequest(true, true);
    }

    @Test
    void continuationSendsNativeTopicsOnlySchemaAndRetainsNullPhysicalPages() {
        verifyNativeRequest(false, true);
    }

    @Test
    void noTopicFirstBatchReturnsBookTypeAndDescriptionInOneNativeExtractionRequest() {
        verifyNativeRequest(true, false);
    }

    private void verifyNativeRequest(boolean firstBatch, boolean withTopics) {
        RestClient.Builder restClient = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
        OllamaApi api = OllamaApi.builder().baseUrl(BASE_URL).restClientBuilder(restClient).build();
        OllamaChatModel model = OllamaChatModel.builder().ollamaApi(api)
                .options(OllamaChatOptions.builder().model("qwen2.5-coder:7b").build()).build();
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(), mapper,
                new ClassPathResource("prompts/book-extraction-system.st"), 16384, 4096, 2, "qwen2.5-coder:7b");
        String response = extractionJson(firstBatch, withTopics);
        String ollamaResponse = mapper.writeValueAsString(Map.of(
                "model", "qwen2.5-coder:7b",
                "created_at", "2026-11-22T16:30:00Z",
                "message", Map.of("role", "assistant", "content", response),
                "done", true,
                "done_reason", "stop",
                "prompt_eval_count", 2500,
                "eval_count", 300));

        server.expect(once(), requestTo(BASE_URL + "/api/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    JsonNode payload = mapper.readTree(body);
                    JsonNode format = payload.path("format");
                    assertThat(format.isObject()).isTrue();
                    assertThat(format.path("type").asString()).isEqualTo("object");
                    JsonNode properties = format.path("properties");
                    assertThat(properties.has("topicDetails")).isTrue();
                    assertThat(properties.has("contentsComplete")).isTrue();
                    assertThat(properties.has("requiredNextPageSet")).isTrue();
                    assertThat(properties.has("authorDetails")).isEqualTo(firstBatch);
                    assertThat(properties.has("bookDetails")).isEqualTo(firstBatch);
                    if (firstBatch) {
                        JsonNode bookProperties = properties.path("bookDetails").path("properties");
                        assertThat(bookProperties.has("bookType")).isTrue();
                        assertThat(bookProperties.has("bookDetails")).isTrue();
                        assertThat(bookProperties.path("bookType").has("enum")).isFalse();
                        assertThat(bookProperties.path("bookDetails").path("description").asString())
                                .contains("same initial extraction JSON", "No invented chapters");
                    }
                    assertAllowsNullInteger(properties.path("firstTopicStartPage"));
                    JsonNode topicProperties = properties.path("topicDetails").path("items").path("properties");
                    assertThat(topicProperties.has("details")).isTrue();
                    assertThat(topicProperties.path("details").path("description").asString())
                            .contains("same extraction response", "comma-separated subtopic");
                    assertAllowsNullInteger(topicProperties.path("startPage"));
                    assertAllowsNullInteger(topicProperties.path("endPage"));
                    assertAllowsNullInteger(topicProperties.path("printedStartPage"));
                    assertAllowsNullInteger(topicProperties.path("printedEndPage"));
                    JsonNode options = payload.path("options");
                    assertThat(options.path("num_ctx").asInt()).isEqualTo(16384);
                    assertThat(options.path("num_predict").asInt()).isEqualTo(4096);
                    assertThat(options.path("temperature").asDouble()).isZero();
                    assertThat(payload.path("model").asString()).isEqualTo("qwen2.5-coder:7b");
                    String instructions = payload.path("messages").get(0).path("content").asString();
                    assertThat(instructions).contains("SAME existing extraction JSON response",
                            "bookDetails.bookType, bookDetails.bookDetails, and topicDetails[].details",
                            "only a general description of that category", "topicDetails=[]");
                })
                .andRespond(withSuccess(ollamaResponse, MediaType.APPLICATION_JSON));

        PdfBatchExtraction previous = firstBatch ? null
                : new PdfBatchExtraction(null, null, List.of(), true, null, true, false);
        PdfPageBatch batch = firstBatch
                ? new PdfPageBatch(1, 5, 40, withTopics ? "--- PDF PAGE 5 ---\nContents"
                        : "--- PDF PAGE 1 ---\nPoems\nIntroduction: A poetry collection.")
                : new PdfPageBatch(6, 10, 40, "--- PDF PAGE 6 ---\nContents continued");
        String returned = analyzer.analyze(batch, previous);

        JsonNode parsed = mapper.readTree(returned);
        assertThat(parsed.path("firstTopicStartPage").isNull()).isTrue();
        if (withTopics) {
            assertThat(parsed.path("topicDetails").get(0).path("startPage").isNull()).isTrue();
            assertThat(parsed.path("topicDetails").get(0).path("endPage").isNull()).isTrue();
            assertThat(parsed.path("topicDetails").get(0).path("printedStartPage").asInt()).isEqualTo(1);
            assertThat(parsed.path("topicDetails").get(0).path("details").asString())
                    .isEqualTo("What Is Programming?, The Hello World Program");
        } else {
            assertThat(parsed.path("topicDetails").isEmpty()).isTrue();
            assertThat(parsed.path("indexFound").asBoolean()).isFalse();
        }
        assertThat(parsed.path("requiredNextPageSet").asBoolean()).isEqualTo(withTopics);
        if (firstBatch) {
            assertThat(parsed.path("bookDetails").path("bookType").asString())
                    .isEqualTo(withTopics ? "Java Programming" : "Poetry");
            assertThat(parsed.path("bookDetails").path("bookDetails").asString()).isNotBlank();
        }
        server.verify();
    }

    private void assertAllowsNullInteger(JsonNode fieldSchema) {
        JsonNode type = fieldSchema.path("type");
        assertThat(type.isArray()).isTrue();
        assertThat(type.toString()).contains("\"integer\"", "\"null\"");
    }

    private String extractionJson(boolean firstBatch, boolean withTopics) {
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("topicName", "Computer Programming");
        topic.put("details", "What Is Programming?, The Hello World Program");
        topic.put("startPage", null);
        topic.put("endPage", null);
        topic.put("printedStartPage", 1);
        topic.put("printedEndPage", null);
        Map<String, Object> response = new LinkedHashMap<>();
        if (firstBatch) {
            response.put("authorDetails", Map.of("authorName", "Writer One, Writer Two"));
            response.put("bookDetails", Map.of("bookName", withTopics ? "Programming" : "Poems", "bookDetails",
                    withTopics ? "A programming guide." : "Poetry uses expressive language, imagery and rhythm.",
                    "bookType", withTopics ? "Java Programming" : "Poetry"));
        }
        response.put("topicDetails", withTopics ? List.of(topic) : List.of());
        response.put("indexFound", withTopics);
        response.put("firstTopicStartPage", null);
        response.put("requiredNextPageSet", withTopics);
        response.put("contentsComplete", false);
        return mapper.writeValueAsString(response);
    }
}
