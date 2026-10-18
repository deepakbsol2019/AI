package com.store.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.Builder;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

	@Bean
	public ChatClient chatClient(ChatClient.Builder builder) {
		ChatMemoryRepository repo=new InMemoryChatMemoryRepository();
		ChatMemory chatMemory=MessageWindowChatMemory.builder().maxMessages(10).chatMemoryRepository(repo).build();
		MessageChatMemoryAdvisor ad = MessageChatMemoryAdvisor.builder(chatMemory).build();
		
		
		return builder
		.defaultAdvisors(new SimpleLoggerAdvisor())
		.defaultAdvisors(a->a.advisors(ad).param(ChatMemory.CONVERSATION_ID,"deepak" ))
		.build();
	}
}
