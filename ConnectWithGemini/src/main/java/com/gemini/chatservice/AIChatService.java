package com.gemini.chatservice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class AIChatService {
	
	private final ChatClient chatClient;
	
	 public AIChatService(ChatClient.Builder chatClientBuilder) {
		this.chatClient=chatClientBuilder.build();
	}
	 
	 public String chatWithGemini(String question) {
		 return chatClient.prompt(question).call().content();
	 }

}
