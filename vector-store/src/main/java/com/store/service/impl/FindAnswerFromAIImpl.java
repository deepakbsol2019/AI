package com.store.service.impl;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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
	
	private final VectorStore vectorStore;
	private final FindSimilarResult findSimilarResult;
	private final ChatClient chatClient;
	public FindAnswerFromAIImpl(ChatClient chatClient,FindSimilarResult findSimilarResult,VectorStore vectorStore) {
		this.chatClient=chatClient;
		this.findSimilarResult=findSimilarResult;
		this.vectorStore=vectorStore;
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
	@Override
	public String searchAdv(String question) {
		QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
		.searchRequest(searchRequest(question)).build();
		
		ChatMemoryRepository chatMemoryRepository=new InMemoryChatMemoryRepository();
		ChatMemory chatMemory=MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(10)
				.build();
		MessageChatMemoryAdvisor memoryAdvisor= MessageChatMemoryAdvisor.builder(chatMemory)
				.build();
		
		return chatClient.prompt()
		.user(question)
		.advisors(questionAnswerAdvisor)
		.advisors(e->e.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, "deepak"))
		.call()
		.content();
	}

	public SearchRequest searchRequest(String question) {
		return  SearchRequest.builder()
		.topK(5)
		.query(question)
		.similarityThreshold(0.4)
		.build();
	}
	@Override
	public String getByRetrivalArgementionAdvisor(String question) {
		
		ChatMemoryRepository chatMemoryRepository=new InMemoryChatMemoryRepository();
		ChatMemory chatMemory=MessageWindowChatMemory.builder()
				.chatMemoryRepository(chatMemoryRepository)
				.maxMessages(10)
				.build();
		MessageChatMemoryAdvisor messageChatMemoryAdvisor=MessageChatMemoryAdvisor.builder(chatMemory).build();
		
		VectorStoreDocumentRetriever vectorStoreDocumentRetriever = VectorStoreDocumentRetriever.builder()
		.topK(5)
		.similarityThreshold(0.4)
		.vectorStore(vectorStore)
		.build();
		
		RetrievalAugmentationAdvisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
		.documentRetriever(vectorStoreDocumentRetriever)
		.build();
		return chatClient.prompt()
				.advisors(retrievalAugmentationAdvisor)
				.advisors(e->e.advisors(messageChatMemoryAdvisor).param(ChatMemory.CONVERSATION_ID, "deepak"))
				.user(question)
				.call()
				.content();
	}
}
