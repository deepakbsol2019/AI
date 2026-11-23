package com.book.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfigClass {

	@Bean
	public ChatClient chatClient(ChatClient.Builder builder,
		@Value("${app.ai.restricted-keywords:}") String restrictedKeywords) {
		List<String> keywords = Arrays.stream(restrictedKeywords.split(","))
				.map(String::trim)
				.filter(keyword -> !keyword.isEmpty())
				.toList();

		return builder
				.defaultAdvisors(
						// Log both normal responses and refusals from the safeguard.
						SimpleLoggerAdvisor.builder().order(-1).build(),
						SafeGuardAdvisor.builder()
								.sensitiveWords(keywords)
								.failureResponse("Your message contains restricted keywords. Please rephrase it.")
								.order(0)
								.build())
				.build();
	}

}
