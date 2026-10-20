package com.tool.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.tool.service.TestService;
import com.tool.service.ToolService;
@Service
public class TestServiceImpl implements TestService {

	private final ChatClient chatClient;
	private final ToolService toolService;
	public TestServiceImpl(ChatClient chatClient,ToolService toolService) {
		this.chatClient=chatClient;
		this.toolService=toolService;
	}
	@Override
	public String setMyAlarm(String userInput) {
		return chatClient.prompt()
		.tools(toolService)
		.user(userInput)
		.call()
		.content();
	}

}
