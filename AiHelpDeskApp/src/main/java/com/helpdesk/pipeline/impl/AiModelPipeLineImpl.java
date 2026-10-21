package com.helpdesk.pipeline.impl;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.helpdesk.enitity.Ticket;
import com.helpdesk.pipeline.AiModelPipeLine;
import com.helpdesk.tools.GeneralTool;
import com.helpdesk.tools.TicketTools;
import com.helpdesk.tools.impl.GeneralToolImpl;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AiModelPipeLineImpl implements AiModelPipeLine {

	private final ChatClient chatClient;
	private final TicketTools ticketTools;
	private final JsonMapper jsonMapper;
	private final GeneralTool generalTool;
	public AiModelPipeLineImpl(ChatClient chatClient,TicketTools ticketTools,JsonMapper jsonMapper,GeneralTool generalTool) {
		this.chatClient=chatClient;
		this.ticketTools=ticketTools;
		this.jsonMapper=jsonMapper;
		this.generalTool=generalTool;
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

}
