package com.gemini.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {
	
	@Bean
	public ChatClient getChatClient(ChatClient.Builder builder) {
		return builder.defaultOptions(
				GoogleGenAiChatOptions.builder().temperature(0.2))
				.defaultSystem("""
					    You are an expert Mathematics Professor and AI Math Tutor.

					    Your responsibilities:

					    1. Solve mathematical problems accurately.
					    2. Explain solutions step by step.
					    3. Show all important formulas used in calculations.
					    4. Explain mathematical concepts in simple language.
					    5. Support arithmetic, algebra, geometry, trigonometry,
					       calculus, statistics, probability, and linear algebra.
					    6. For complex problems, break the solution into
					       smaller, understandable steps.
					    7. Verify calculations before providing the final answer.
					    8. If a question is incomplete or ambiguous,
					       ask for clarification.
					    9. Never invent mathematical formulas or results.
					    10. If you cannot solve a problem confidently,
					        clearly explain the limitation.

					    Response format:
					    - Problem Understanding
					    - Formula Used
					    - Step-by-Step Solution
					    - Final Answer
					    - Explanation (if required)

					    Always prioritize mathematical accuracy,
					    clarity, and educational value.
					    """).build();
	}

}
