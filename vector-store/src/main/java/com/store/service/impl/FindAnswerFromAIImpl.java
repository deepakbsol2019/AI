package com.store.service.impl;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.store.service.FindAnswerFromAI;
import com.store.service.FindSimilarResult;
@Service
public class FindAnswerFromAIImpl implements FindAnswerFromAI{

	@Value("classpath:system_message.st")
	private Resource systemResource;
	
	@Value("classpath:user_message.st")
	private Resource userResource;
	
	private final FindSimilarResult findSimilarResult;
	private final ChatClient chatClient;
	public FindAnswerFromAIImpl(ChatClient chatClient,FindSimilarResult findSimilarResult) {
		this.chatClient=chatClient;
		this.findSimilarResult=findSimilarResult;
	}
	@Override
	public String findUserQuestionAnswer(String question) {
		List<String> data = findSimilarResult.findSimilarDataByQuestion(question);
		
		return chatClient.prompt()
		.system(system->system.text(systemResource).param("context", String.join(", ", data)))
		.user(u->u.text(userResource).param("question", question))
		.call()
		.content();
	}

}
