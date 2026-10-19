package com.adv.rag.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.adv.rag.service.SearchAnswer;

@Service
public class SearchAnswerImpl implements SearchAnswer {

	private final ChatClient chatClient;
	private final VectorStore vectorStore;
	public SearchAnswerImpl(ChatClient chatClient,VectorStore vectorStore) {
		this.chatClient=chatClient;
		this.vectorStore=vectorStore;
	}
	@Override
	public String getAnswer(String question) {
		return chatClient.prompt()
				.advisors(retrievalAugmentationAdvisor())
				.user(question)
				.call()
				.content();
	}
	
	private RetrievalAugmentationAdvisor retrievalAugmentationAdvisor() {
		return RetrievalAugmentationAdvisor.builder()
				.queryTransformers(RewriteQueryTransformer.builder().chatClientBuilder(chatClient.mutate().clone()).build())
				.queryExpander(MultiQueryExpander.builder().chatClientBuilder(
                        chatClient.mutate().clone()
                    ).build())
				.documentRetriever(
						VectorStoreDocumentRetriever.builder()
						.vectorStore(vectorStore)
						.similarityThreshold(0.4)
						.topK(5)
						.build()
						)
				.documentJoiner(new ConcatenationDocumentJoiner())
				.queryAugmenter(ContextualQueryAugmenter.builder().build())
				.build();
	}

}
