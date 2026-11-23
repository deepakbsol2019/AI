package com.book.config;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AiConfigClass {

	@Bean(name = "bookUploadExecutor")
	@DependsOn({"topicPdfExecutor", "entityManagerFactory"})
	public ThreadPoolTaskExecutor bookUploadExecutor(
			@Value("${app.book-upload.pool-size:2}") int poolSize,
			@Value("${app.book-upload.queue-capacity:8}") int queueCapacity) {
		if (poolSize < 1 || queueCapacity < 0) {
			throw new IllegalArgumentException("Book upload pool size must be positive and queue capacity nonnegative.");
		}
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("book-upload-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		return executor;
	}

	@Bean(name = "topicPdfExecutor")
	public ThreadPoolTaskExecutor topicPdfExecutor(
			@Value("${app.topic-pdf.pool-size:4}") int poolSize,
			@Value("${app.topic-pdf.queue-capacity:256}") int queueCapacity) {
		if (poolSize < 1 || queueCapacity < 0) {
			throw new IllegalArgumentException("Topic PDF pool size must be positive and queue capacity nonnegative.");
		}
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("topic-pdf-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(60);
		return executor;
	}

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
