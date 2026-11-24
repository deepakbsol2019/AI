package com.book.extraction;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import com.book.dto.PdfBatchExtraction;
import com.book.dto.PdfPageBatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class BookPageAnalyzerTests {
    private static final String JSON_RESPONSE = """
            {"topicDetails":[],"indexFound":true,"firstTopicStartPage":null,
             "contentsComplete":false,"requiredNextPageSet":true}
            """;
    @Test
    void rendersStSystemInstructionsWithJsonSchemaAndPreviousContext() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(JSON_RESPONSE)))));
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(),
                JsonMapper.builder().build(), new ClassPathResource("prompts/book-extraction-system.st"),
                16384, 4096, 2, "qwen2.5-coder:7b");
        PdfBatchExtraction previous = new PdfBatchExtraction(null, null, List.of(), true, null, true, false);

        assertThat(analyzer.analyze(new PdfPageBatch(6, 10, 40, "--- PDF PAGE 6 ---\nContents"), previous))
                .isEqualTo(JSON_RESPONSE);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getSystemMessage().getText())
                .contains("complete PDF has 40", "pages 6 through 10", "requiredNextPageSet", "\"indexFound\":true")
                .doesNotContain("{totalPages}", "{previousExtraction}", "{format}", "{contentsHeadings}");
        assertThat(prompt.getValue().getUserMessage().getText()).contains("--- PDF PAGE 6 ---");
        String instructions = prompt.getValue().getSystemMessage().getText();
        String schema = instructions.substring(instructions.indexOf("JSON schema and formatting requirements:"));
        assertThat(instructions).contains("Is this the first batch: false",
                "return ONLY topicDetails", "Contents may start on PDF page 5");
        assertThat(schema).contains("\"topicDetails\"", "\"requiredNextPageSet\"", "\"contentsComplete\"")
                .doesNotContain("\"authorDetails\"", "\"bookDetails\"");
    }

    @Test
    void firstBatchSchemaIncludesAuthorBookAndTopics() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(JSON_RESPONSE)))));
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(),
                JsonMapper.builder().build(), new ClassPathResource("prompts/book-extraction-system.st"),
                16384, 4096, 2, "qwen2.5-coder:7b");

        analyzer.analyze(new PdfPageBatch(1, 5, 40, "--- PDF PAGE 1 ---\nBook"), null);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String instructions = prompt.getValue().getSystemMessage().getText();
        String schema = instructions.substring(instructions.indexOf("JSON schema and formatting requirements:"));
        assertThat(instructions).contains("Is this the first batch: true");
        assertThat(schema).contains("\"authorDetails\"", "\"bookDetails\"", "\"topicDetails\"",
                "\"contentsComplete\"", "\"bookType\"");
        assertThat(instructions).contains("ALL credited authors/writers", "short factual",
                "Brief Contents", "Detailed Contents", "Contents at a Glance",
                "contents has started but is incomplete",
                "Stop at ANY such following section", "do not wait",
                "firstTopicStartPage is never a condition for stopping",
                "printedEndPage MUST be null", "17-32");
        assertThat(instructions).contains("SAME first-batch response", "introduction, preface",
                "Computer", "Java Programming", "Story", "History", "Physics", "Chemistry", "Math",
                "\"Unknown\"", "topicDetails=[]")
                .doesNotContain("Return one topic:");
        assertThat(instructions).contains("SAME existing extraction JSON response",
                "bookDetails.bookType, bookDetails.bookDetails, and topicDetails[].details",
                "no later description-enrichment request", "only a general description of that category",
                "never replace them with a regenerated summary", "only unfinished contents requires one");
    }

    @Test
    void successfulInitialBatchReturnsTypeAndBothDescriptionsFromExactlyOneModelCall() {
        String response = """
                {"authorDetails":{"authorName":"A Writer"},
                 "bookDetails":{"bookName":"Java Basics","bookType":"Java Programming",
                    "bookDetails":"An introduction to Java programming."},
                 "topicDetails":[{"topicName":"Variables","details":"Names, Types, Assignment",
                    "startPage":null,"endPage":null,"printedStartPage":1,"printedEndPage":null}],
                 "indexFound":true,"firstTopicStartPage":null,
                 "contentsComplete":false,"requiredNextPageSet":true}
                """;
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(response)))));
        JsonMapper mapper = JsonMapper.builder().build();
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(), mapper,
                new ClassPathResource("prompts/book-extraction-system.st"), 16384, 4096, 2, "qwen2.5-coder:7b");

        String returned = analyzer.analyze(new PdfPageBatch(1, 5, 40,
                "Java Basics\nIntroduction to Java programming\nContents\n1 Variables 1\nNames, Types, Assignment"), null);

        PdfBatchExtraction extracted = mapper.readValue(returned, PdfBatchExtraction.class);
        assertThat(extracted.bookDetails().bookType()).isEqualTo("Java Programming");
        assertThat(extracted.bookDetails().bookDetails()).isEqualTo("An introduction to Java programming.");
        assertThat(extracted.topicDetails().getFirst().details()).isEqualTo("Names, Types, Assignment");
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void noContentsStillReturnsTypeAndGeneralBookDescriptionInTheSameSingleCall() {
        String response = """
                {"authorDetails":null,"bookDetails":{"bookName":"Poems","bookType":"Poetry",
                    "bookDetails":"Poetry uses expressive language, imagery and rhythm."},
                 "topicDetails":[],"indexFound":false,"firstTopicStartPage":null,
                 "contentsComplete":false,"requiredNextPageSet":false}
                """;
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(response)))));
        JsonMapper mapper = JsonMapper.builder().build();
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(), mapper,
                new ClassPathResource("prompts/book-extraction-system.st"), 16384, 4096, 2, "qwen2.5-coder:7b");

        String returned = analyzer.analyze(new PdfPageBatch(1, 1, 1, "Poems\nA poetry collection."), null);

        PdfBatchExtraction extracted = mapper.readValue(returned, PdfBatchExtraction.class);
        assertThat(extracted.bookDetails().bookType()).isEqualTo("Poetry");
        assertThat(extracted.bookDetails().bookDetails()).contains("Poetry");
        assertThat(extracted.topicDetails()).isEmpty();
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void insufficientEvidenceCanReturnUnknownAndNullDescriptionWithoutAnyEnrichmentRetry() {
        String response = """
                {"authorDetails":null,"bookDetails":{"bookName":"Notes","bookType":"Unknown","bookDetails":null},
                 "topicDetails":[],"indexFound":false,"firstTopicStartPage":null,
                 "contentsComplete":false,"requiredNextPageSet":false}
                """;
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(response)))));
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(), JsonMapper.builder().build(),
                new ClassPathResource("prompts/book-extraction-system.st"), 16384, 4096, 2, "qwen2.5-coder:7b");

        assertThat(analyzer.analyze(new PdfPageBatch(1, 1, 1, "Notes"), null)).isEqualTo(response);

        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void retriesSameBatchWhenModelReturnsProseInsteadOfJson() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                        "This is a table of contents for a book on programming, specifically focusing on Java.")))),
                        new ChatResponse(List.of(new Generation(new AssistantMessage(JSON_RESPONSE)))));
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(),
                JsonMapper.builder().build(), new ClassPathResource("prompts/book-extraction-system.st"),
                16384, 4096, 2, "qwen2.5-coder:7b");

        assertThat(analyzer.analyze(new PdfPageBatch(6, 10, 372, "--- PDF PAGE 6 ---\nContents"), null))
                .isEqualTo(JSON_RESPONSE);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(prompt ->
                assertThat(prompt.getUserMessage().getText()).contains("--- PDF PAGE 6 ---"));
        assertThat(prompts.getValue().getUserMessage().getText()).contains("previous attempt did not return valid");
    }

    @Test
    void stopsAfterConfiguredAttemptsWhenModelNeverReturnsExtractionJson() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("This is a summary.")))));
        BookPageAnalyzer analyzer = new BookPageAnalyzer(ChatClient.builder(model).build(),
                JsonMapper.builder().build(), new ClassPathResource("prompts/book-extraction-system.st"),
                16384, 4096, 2, "qwen2.5-coder:7b");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> analyzer.analyze(
                new PdfPageBatch(6, 10, 372, "Contents"), null))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
        verify(model, times(2)).call(any(Prompt.class));
    }
}
