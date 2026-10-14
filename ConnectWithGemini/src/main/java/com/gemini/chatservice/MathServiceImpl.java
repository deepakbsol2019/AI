package com.gemini.chatservice;

import org.jspecify.annotations.Nullable;
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
			@Nullable
			String content = chatClient.prompt()
					//.system("")
					.user(question)
					.call()
					.content();
			System.out.println("contant ::"+content);
					//.entity(MathModel.class);
			 
			 return new MathModel(question, null, question, question, question, null, null, null);
		}catch(Exception e) {
			System.out.println("exception :: "+e);
			throw new CustException(e.getMessage());
		}
		
	}

}
