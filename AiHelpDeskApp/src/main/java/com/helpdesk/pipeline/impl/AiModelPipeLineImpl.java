package com.helpdesk.pipeline.impl;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.pipeline.AiModelPipeLine;
import com.helpdesk.tools.GeneralTool;
import com.helpdesk.tools.TicketTools;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AiModelPipeLineImpl implements AiModelPipeLine {

	@Value("classpath:helpdesk.st")
	private Resource resource;
	private final ChatClient chatClient;
	private final TicketTools ticketTools;
	private final JsonMapper jsonMapper;
	private final GeneralTool generalTool;
	private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
	public AiModelPipeLineImpl(ChatClient chatClient,TicketTools ticketTools,JsonMapper jsonMapper,
			GeneralTool generalTool,MessageChatMemoryAdvisor messageChatMemoryAdvisor) {
		this.chatClient=chatClient;
		this.ticketTools=ticketTools;
		this.jsonMapper=jsonMapper;
		this.generalTool=generalTool;
		this.messageChatMemoryAdvisor=messageChatMemoryAdvisor;
	}
	@Override
	public Ticket createUpdatePipeLine(String question) {
		System.out.println("user question :: create ticket ::"+question);
		String response= chatClient.prompt()
		.user(question)
		.system("""
                You are a helpdesk assistant.

                Use getTodayDate when the user asks about today.
                Use getCurrentDateTime when the current time is required.

                After obtaining the date, use the appropriate
                ticket search tool.

                Do not invent ticket information.
                Do not return an explanation instead of ticket data.
                """)
        
		.tools(ticketTools,generalTool)
		.call()
		.content();
		
		if (response == null || response.isBlank()) {
            throw new IllegalStateException(
                    "No ticket response was returned");
        }

        return jsonMapper.readValue(
                response,
                new TypeReference<Ticket>() {}
        );
        
	}
	@Override
	public List<Ticket> getTicketPipeLine(String question) {
		System.out.println("user question :: get ticket :: "+question);
		String response= chatClient.prompt()
				.user(question)
				.system("""
	                    You are a helpdesk assistant.

	                    Use getTodayDate when the user asks about today.
	                    Use getCurrentDateTime when the current time is required.

	                    After obtaining the date, use the appropriate
	                    ticket search tool.

	                    Do not invent ticket information.
	                    Do not return an explanation instead of ticket data.
	                    """)
	            
				.tools(ticketTools,generalTool)
				.call()
				.content();
		
		if (response == null || response.isBlank()) {
            throw new IllegalStateException(
                    "No ticket response was returned");
        }

        return jsonMapper.readValue(
                response,
                new TypeReference<List<Ticket>>() {}
        );
	}
	@Override
	public String connectWithHelpDesk(String question) {
		return helpdeskPrompt(question).call().content();
	}

	@Override
	public String connectWithHelpDesk(String question, String conversationId) {
		Assert.hasText(conversationId, "Conversation ID must not be blank");
		return helpdeskPrompt(question)
				.advisors(m->m.advisors(messageChatMemoryAdvisor).param(ChatMemory.CONVERSATION_ID, conversationId))
				.call()
				.content();
	}

	private ChatClient.ChatClientRequestSpec helpdeskPrompt(String question) {
		Assert.hasText(question, "Question must not be blank");
		return chatClient.prompt()
				.system(resource)
				.user(question)
				.tools(ticketTools,generalTool);
	}
	

}
