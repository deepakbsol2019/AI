package com.helpdesk.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.helpdesk.pipeline.impl.AiModelPipeLineImpl;
import com.helpdesk.repo.HelpdeskUserRepo;
import com.helpdesk.service.TicketService;
import com.helpdesk.tools.GeneralTool;
import com.helpdesk.tools.TicketTools;
import com.helpdesk.tools.impl.TicketToolsImpl;

import tools.jackson.databind.json.JsonMapper;

class AiModelPipeLineImplTests {

    private static final String OLD_TICKET_CLAIM = "You already have an OPEN ticket #42.";

    private final ChatModel chatModel = mock(ChatModel.class);
    private final List<Prompt> modelPrompts = new ArrayList<>();
    private AiModelPipeLineImpl pipeline;

    @BeforeEach
    void setUp() {
        when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            modelPrompts.add(invocation.getArgument(0));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(OLD_TICKET_CLAIM))));
        });

        pipeline = newPipeline(mock(TicketTools.class));
    }

    @Test
    void sameConversationKeepsPreviousTurnsForFollowUp() {
        assertEquals(OLD_TICKET_CLAIM, pipeline.connectWithHelpDesk("My VPN fails", "session-a"));
        pipeline.connectWithHelpDesk("Please check that ticket", "session-a");

        assertEquals(List.of("My VPN fails", OLD_TICKET_CLAIM, "Please check that ticket"),
                conversationTexts(modelPrompts.get(1)));
    }

    @Test
    void differentConversationsDoNotShareAnOldTicketClaim() {
        pipeline.connectWithHelpDesk("My VPN fails", "session-a");
        pipeline.connectWithHelpDesk("My laptop will not start", "session-b");
        pipeline.connectWithHelpDesk("Any update on my VPN?", "session-a");

        assertEquals(List.of("My laptop will not start"), conversationTexts(modelPrompts.get(1)));
        assertEquals(List.of("My VPN fails", OLD_TICKET_CLAIM, "Any update on my VPN?"),
                conversationTexts(modelPrompts.get(2)));
    }

    @Test
    void callsWithoutAConversationIdStartIndependentConversations() {
        pipeline.connectWithHelpDesk("My VPN fails");
        pipeline.connectWithHelpDesk("My laptop will not start");

        assertEquals(List.of("My VPN fails"), conversationTexts(modelPrompts.get(0)));
        assertEquals(List.of("My laptop will not start"), conversationTexts(modelPrompts.get(1)));
    }

    @Test
    void blankQuestionOrConversationIdDoesNotCallTheModel() {
        assertThrows(IllegalArgumentException.class, () -> pipeline.connectWithHelpDesk(" ", "session-a"));
        assertThrows(IllegalArgumentException.class, () -> pipeline.connectWithHelpDesk("My VPN fails", " "));
        assertEquals(0, modelPrompts.size());
    }

    @Test
    void emptySummaryLookupReturnsToTheModelBeforeResponding() {
        var ticketService = mock(TicketService.class);
        when(ticketService.findTicketsBySummary("deepak", "printer")).thenReturn(List.of());
        pipeline = newPipeline(new TicketToolsImpl(ticketService, mock(HelpdeskUserRepo.class)));
        ReflectionTestUtils.setField(pipeline, "resource", new ClassPathResource("helpdesk.st"));
        String noMatchResponse = "No matching ticket was found. Would you like to create one?";

        doAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            modelPrompts.add(prompt);
            if (modelPrompts.size() == 1) {
                var assistantMessage = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("lookup-1", "function",
                                "findTicketsBySummary", "{\"userName\":\"deepak\",\"summary\":\"printer\"}")))
                        .build();
                return new ChatResponse(List.of(new Generation(assistantMessage)));
            }

            var toolResults = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .toList();
            assertEquals(1, toolResults.size());
            assertEquals("findTicketsBySummary", toolResults.getFirst().name());
            assertEquals("[]", toolResults.getFirst().responseData());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(noMatchResponse))));
        }).when(chatModel).call(any(Prompt.class));

        assertEquals(noMatchResponse,
                pipeline.connectWithHelpDesk("Check my printer ticket for deepak", "session-a"));
        verify(ticketService).findTicketsBySummary("deepak", "printer");
        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    private AiModelPipeLineImpl newPipeline(TicketTools ticketTools) {
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();
        var result = new AiModelPipeLineImpl(ChatClient.builder(chatModel).build(),
                ticketTools, JsonMapper.builder().build(), mock(GeneralTool.class),
                MessageChatMemoryAdvisor.builder(memory).build());
        ReflectionTestUtils.setField(result, "resource", new ByteArrayResource(
                "Use tools to verify current tickets.".getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    private List<String> conversationTexts(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(message -> message.getMessageType() != MessageType.SYSTEM)
                .map(message -> message.getText())
                .toList();
    }
}
