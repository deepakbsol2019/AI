package com.adv.rag.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.adv.rag.service.SearchAnswer;

@RestController
@RequestMapping("/api")
public class AdvanceRagImp {
	
	private final SearchAnswer searchAnswer;
	public AdvanceRagImp(SearchAnswer searchAnswer) {
		this.searchAnswer=searchAnswer;
	}
	@GetMapping("/ans-rag-adv")
	public String getAnswer(String question) {
		return searchAnswer.getAnswer(question);
	}
}
