package com.gemini.chatservice;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Service;

@Service
public class AIChatService {
	
	private final ChatClient chatClient;
	
	 public AIChatService(ChatClient.Builder chatClientBuilder) {
		this.chatClient=chatClientBuilder.build();
	}
	 
	 public String chatWithGemini(String question) {
		 //return chatClient.prompt(question).call().content();
		ChatResponse chatResponse = chatClient.prompt()
		 .user(question)
		 .system("cricket questions and answers as expert")
		 .call()
		 .chatResponse();
		 
		 System.out.println("chatResponse :: "+chatResponse);
		 System.out.println("===================================================");
		 ChatResponseMetadata metadata = chatResponse.getMetadata();
		 
		 System.out.println("metadata :: "+metadata);
		 
		 System.out.println("===========================================================");
		 
		 Generation result = chatResponse.getResult();
		 System.out.println("result :: "+result);
		 
		 List<Generation> data = chatResponse.getResults();
		 
		 System.out.println("data :: "+data);
		 
		 AssistantMessage assistantMessage = result.getOutput();
		 System.out.println("assistantMessage ::"+assistantMessage);
		 return assistantMessage.getText();
	 }

}
