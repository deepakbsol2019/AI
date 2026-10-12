package com.gemini.computerlang.service;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import com.gemini.custexception.CustException;
import com.gemini.model.ProgrammingLagModel;
@Service
public class ProgrammingLangServiceImpl implements ProgrammingLangService {

	private final ChatClient chatClient;
	public ProgrammingLangServiceImpl(ChatClient.Builder builder) {
		this.chatClient=builder.build();
	}
	@Override
	public ProgrammingLagModel getDtls(String userQuestion) {
		try {
			return chatClient.prompt()
					.system("""
			                You are a Computer Programming Language Expert.

			                Provide information about the programming
			                language requested by the user.

			                Rules:
			                1. languageName: Programming language name.
			                2. foundAt: First release date in yyyy-MM-dd format.
			                3. founder: Creator of the language.
			                4. topReferenceBookName: Popular reference book.
			                5. description: Brief language description.

			                Return accurate information.
			                If an exact date is unknown, use null.
			                """)
			.user(userQuestion)
			.call()
			.entity(ProgrammingLagModel.class);
			
			
		}catch(Exception e) {
			throw new CustException(e.getMessage());
		}
		
	}
	@Override
	public List<ProgrammingLagModel> getDtlsList(String userQuestion) {
		List<ProgrammingLagModel> entity =null;
		try {
			entity = chatClient.prompt()
					.system("""
						                You are a Computer Programming Language Expert.

						                Provide information about the programming
						                language requested by the user.

						                Rules:
						                1. languageName: Programming language name.
						                2. foundAt: First release date in yyyy-MM-dd format.
						                3. founder: Creator of the language.
						                4. topReferenceBookName: Popular reference book.
						                5. description: Brief language description.

						                Return accurate information.
						                If an exact date is unknown, use null.
						                """)
					.user(userQuestion)
					.call()
					.entity(new ParameterizedTypeReference<List<ProgrammingLagModel>>() {
					});
			
		}catch(Exception e) {
			throw new CustException(e.getMessage());
		}
		
		return entity;
	}

}
