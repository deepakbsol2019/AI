package com.helpdesk.config;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {
	
	@Bean
	public MessageChatMemoryAdvisor chatMemoryAdvisor() {
		
		return MessageChatMemoryAdvisor.builder(getMessageWindowChatMesmory())
		.build();
	}

	@Bean
	public ChatMemoryRepository getInMemoryChatMemoryRepository() {
		return new InMemoryChatMemoryRepository();
	}
	
	@Bean
	public ChatMemory getMessageWindowChatMesmory() {
		return MessageWindowChatMemory.builder()
		.chatMemoryRepository(getInMemoryChatMemoryRepository())
		.maxMessages(10)
		.build();
	}
}
