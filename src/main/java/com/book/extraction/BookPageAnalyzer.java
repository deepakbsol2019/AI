package com.book.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfPageBatch;
import com.book.dto.PdfTopicBatchExtraction;
import com.book.constants.ContentsHeading;

@Service
public class BookPageAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(BookPageAnalyzer.class);
    private final ChatClient chatClient;
    private final Resource systemInstructions;
    private final ObjectMapper objectMapper;
    private final int contextWindow;
    private final int maxOutputTokens;
    private final int maxAttempts;
    private final String modelName;
    private final BeanOutputConverter<PdfBatchExtraction> converter =
            new BeanOutputConverter<>(PdfBatchExtraction.class);
    private final BeanOutputConverter<PdfTopicBatchExtraction> topicConverter =
            new BeanOutputConverter<>(PdfTopicBatchExtraction.class);

    public BookPageAnalyzer(ChatClient chatClient, ObjectMapper objectMapper,
            @Value("classpath:prompts/book-extraction-system.st") Resource systemInstructions,
            @Value("${app.ai.extraction.context-window:16384}") int contextWindow,
            @Value("${app.ai.extraction.max-output-tokens:4096}") int maxOutputTokens,
            @Value("${app.ai.extraction.max-attempts:2}") int maxAttempts,
            @Value("${spring.ai.ollama.chat.options.model:mistral}") String modelName) {
        if (contextWindow <= maxOutputTokens || maxOutputTokens < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("Extraction requires context-window > max-output-tokens > 0 and max-attempts > 0.");
        }
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.systemInstructions = systemInstructions;
        this.contextWindow = contextWindow;
        this.maxOutputTokens = maxOutputTokens;
        this.maxAttempts = maxAttempts;
        this.modelName = modelName;
    }

    public String analyze(PdfPageBatch batch, PdfBatchExtraction previous) {
        BeanOutputConverter<?> selectedConverter = batch.startPage() == 1 ? converter : topicConverter;
        OllamaChatOptions options = OllamaChatOptions.builder()
                // A fresh Ollama options builder otherwise defaults to mistral.
                .model(modelName)
                .outputSchema(selectedConverter.getJsonSchema())
                .numCtx(contextWindow)
                .numPredict(maxOutputTokens)
                .temperature(0.0)
                .build();
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                String instruction = attempt == 1
                        ? "Extract these PDF pages as the requested JSON object. Do not summarize them in prose."
                        : "The previous attempt did not return valid extraction JSON. Re-extract the SAME pages. "
                                + "Return the schema fields and control flags only; no prose or Markdown.";
                String response = chatClient.prompt()
                        .options(options.mutate())
                        .system(spec -> spec.text(systemInstructions)
                                .param("totalPages", batch.totalPages())
                                .param("startPage", batch.startPage())
                                .param("endPage", batch.endPage())
                                .param("firstBatch", batch.startPage() == 1)
                                .param("contentsHeadings", ContentsHeading.headingList())
                                .param("previousExtraction", objectMapper.writeValueAsString(previous))
                                .param("format", selectedConverter.getJsonSchema()))
                        .user(instruction + "\n\n" + batch.text())
                        .call()
                        .content();
                if (isExtractionJson(response, batch.startPage() == 1)) {
                    return response;
                }
                logger.warn("Model returned invalid extraction JSON for PDF pages {}-{} (attempt {}/{})",
                        batch.startPage(), batch.endPage(), attempt, maxAttempts);
            }
            throw new UnusableContentsException(
                    "The model did not return valid extraction JSON after " + maxAttempts
                            + " attempts; no extraction data was saved.");
        }
        catch (ResponseStatusException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The model could not analyze the PDF pages.", exception);
        }
    }

    private boolean isExtractionJson(String response, boolean firstBatch) {
        if (response == null || response.isBlank()) {
            return false;
        }
        try {
            JsonNode json = objectMapper.readTree(response);
            if (json == null || !json.isObject() || !json.path("topicDetails").isArray()
                    || !json.path("indexFound").isBoolean() || !json.path("contentsComplete").isBoolean()
                    || !json.path("requiredNextPageSet").isBoolean()) {
                return false;
            }
            if (firstBatch) {
                objectMapper.readValue(response, PdfBatchExtraction.class);
            }
            else {
                objectMapper.readValue(response, PdfTopicBatchExtraction.class);
            }
            return true;
        }
        catch (JacksonException exception) {
            return false;
        }
    }
}
