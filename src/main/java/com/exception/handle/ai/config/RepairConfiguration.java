package com.exception.handle.ai.config;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.exception.handle.ai.pipeline.RepairAiClient;
import com.exception.handle.repair.service.impl.RepairWorkerService;
import com.exception.handle.repair.service.impl.SourceFileAccess;
import com.exception.handle.repair.service.impl.SpringAiRepairClient;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnProperty(name = "repair.enabled", havingValue = "true")
public class RepairConfiguration {

	@Bean
	public ChatClient repairChatClient(ChatClient.Builder builder) {
		return builder.build();
	}

	@Bean
	public RepairAiClient repairAiClient(@Qualifier("repairChatClient") ChatClient client,
			ObjectMapper mapper) {
		return new SpringAiRepairClient(client, mapper);
	}

	@Bean
	public RepairWorkerService repairWorkerService(
			@Qualifier("repairAiClient") RepairAiClient ai,
			@Value("${repair.source-root}")
			String sourceRoot,

			@Value("${repair.backup-dir}")
			String backupDir,

			@Value("${repair.allowed-packages}")
			List<String> allowedPackages,

			@Value("${repair.test-root:}")
			String testRoot,
			@Value("${repair.max-attempts-per-error:2}") int maxAttemptsPerError,
			@Value("${repair.max-distinct-errors:5000}") int maxDistinctErrors) throws
	IOException {
		SourceFileAccess files = new SourceFileAccess(
		        Path.of(sourceRoot),
		        Path.of(backupDir),
		        allowedPackages,
		        testRoot == null || testRoot.isBlank()
		                ? null
		                : Path.of(testRoot)
		);
		return new RepairWorkerService(files, ai, maxAttemptsPerError, maxDistinctErrors);
	}
}

