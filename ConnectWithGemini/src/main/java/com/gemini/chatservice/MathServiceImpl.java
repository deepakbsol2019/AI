package com.gemini.chatservice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.gemini.custexception.CustException;
import com.gemini.model.MathModel;

@Service
public class MathServiceImpl implements MathService{

	private final ChatClient chatClient;
	public MathServiceImpl(ChatClient chatClient) {
		this.chatClient=chatClient;
	}
	@Override
	public MathModel singleQuentionAns(String question) {
		try {
			return chatClient.prompt()
					//.system("")
					.user(question)
					.call()
					.entity(MathModel.class);
		}catch(Exception e) {
			throw new CustException(e.getMessage());
		}
	}

}
