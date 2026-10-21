package com.helpdesk.pipeline.impl;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.pipeline.AiModelPipeLine;
import com.helpdesk.tools.TicketTools;

@Service
public class AiModelPipeLineImpl implements AiModelPipeLine {

	private final ChatClient chatClient;
	private final TicketTools ticketTools;
	public AiModelPipeLineImpl(ChatClient chatClient,TicketTools ticketTools) {
		this.chatClient=chatClient;
		this.ticketTools=ticketTools;
	}
	@Override
	public Ticket createUpdatePipeLine(String question) {
		System.out.println("user question :: create ticket ::"+question);
		return chatClient.prompt()
		.user(question)
		.tools(ticketTools)
		.call()
		.entity(Ticket.class);
	}
	@Override
	public List<Ticket> getTicketPipeLine(String question) {
		System.out.println("user question :: get ticket :: "+question);
		return chatClient.prompt()
				.user(question)
				.tools(ticketTools)
				.call()
				.entity(new ParameterizedTypeReference<List<Ticket>>() {});
	}

}
